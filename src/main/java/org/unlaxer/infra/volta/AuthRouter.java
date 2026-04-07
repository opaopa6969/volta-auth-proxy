package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.util.*;

import static io.javalin.rendering.template.TemplateUtil.model;

public final class AuthRouter {
    private final AppConfig config;
    private final SqlStore store;
    private final AuthService authService;
    private final SessionStore sessionStore;
    private final AuditService auditService;
    private final OidcService oidcService;
    private final SamlService samlService;
    private final AppRegistry appRegistry;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final PolicyEngine policy;
    private final RateLimiter rateLimiter;
    private final DeviceTrustService deviceTrustService;

    public AuthRouter(AppConfig config, SqlStore store, AuthService authService,
                      SessionStore sessionStore, AuditService auditService,
                      OidcService oidcService, SamlService samlService,
                      AppRegistry appRegistry, NotificationService notificationService,
                      ObjectMapper objectMapper, PolicyEngine policy,
                      RateLimiter rateLimiter, DeviceTrustService deviceTrustService) {
        this.config = config;
        this.store = store;
        this.authService = authService;
        this.sessionStore = sessionStore;
        this.auditService = auditService;
        this.oidcService = oidcService;
        this.samlService = samlService;
        this.appRegistry = appRegistry;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.rateLimiter = rateLimiter;
        this.deviceTrustService = deviceTrustService;
    }

    public void register(Javalin app) {
        // --- Magic Link (Passwordless Email) ---
        app.post("/auth/magic-link/send", ctx -> {
            com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(ctx.body());
            String email = body.path("email").asText("");
            if (email.isBlank() || !email.contains("@")) {
                throw new ApiException(400, "BAD_REQUEST", "Valid email required");
            }
            String token = store.createMagicLink(email, 10);
            String link = config.baseUrl() + "/auth/magic-link/verify?token=" + token;
            String payload = objectMapper.writeValueAsString(Map.of("to", email, "magicLink", link));
            store.enqueueOutboxEvent(null, "notification.magic_link", payload);
            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("ok", true);
            response.put("message", "Login link sent to " + email);
            if (config.devMode()) {
                response.put("token", token);
                response.put("link", link);
            }
            ctx.json(response);
        });

        app.get("/auth/magic-link/verify", ctx -> {
            String token = ctx.queryParam("token");
            if (token == null || token.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "Token required");
            }
            SqlStore.MagicLinkRecord ml = store.consumeMagicLink(token)
                    .orElseThrow(() -> new ApiException(401, "LINK_INVALID", "Link is expired or already used"));

            // Find or create user (upsert handles both cases)
            UserRecord user = store.upsertUser(ml.email(), null, null);
            TenantRecord tenant = resolveTenant(store, user, null);
            MembershipRecord membership = store.findMembership(user.id(), tenant.id())
                    .orElseThrow(() -> new ApiException(403, "TENANT_ACCESS_DENIED", "No tenant membership"));

            AuthPrincipal principal = new AuthPrincipal(
                    user.id(), user.email(), user.displayName(),
                    tenant.id(), tenant.name(), tenant.slug(),
                    List.of(membership.role()), false);

            UUID sessionId = authService.issueSession(principal, null, HttpSupport.clientIp(ctx), ctx.userAgent());
            setSessionCookie(ctx, sessionId, config.sessionTtlSeconds());
            auditService.log(ctx, "LOGIN_SUCCESS", principal, "SESSION", sessionId.toString(), Map.of("via", "magic_link"));
            checkDeviceAndNotify(principal, ctx, store, objectMapper);

            ctx.redirect("/console/");
        });

        app.get("/auth/saml/login", ctx -> {
            String tenantRaw = ctx.queryParam("tenant_id");
            if (tenantRaw == null || tenantRaw.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "tenant_id is required");
            }
            UUID tenantId = UUID.fromString(tenantRaw);
            SqlStore.IdpConfigRecord saml = store.findIdpConfig(tenantId, "SAML")
                    .orElseThrow(() -> new ApiException(404, "IDP_NOT_FOUND", "SAML IdP 設定が見つかりません。"));
            String entry = saml.metadataUrl();
            if (entry == null || entry.isBlank()) {
                entry = saml.issuer();
            }
            if (entry == null || entry.isBlank()) {
                throw new ApiException(400, "IDP_INVALID", "SAML エントリーポイントが未設定です。");
            }
            String returnToRaw = ctx.queryParam("return_to");
            String returnTo = HttpSupport.isAllowedReturnTo(returnToRaw, config.allowedRedirectDomains()) ? returnToRaw : null;
            String requestId = "_" + SecurityUtils.randomUrlSafe(16);
            String relay = samlService.encodeRelayState(Map.of(
                    "tenant_id", tenantId.toString(),
                    "return_to", returnTo == null ? "" : returnTo,
                    "request_id", requestId
            ));
            String redirect = entry + (entry.contains("?") ? "&" : "?") + "RelayState="
                    + java.net.URLEncoder.encode(relay, java.nio.charset.StandardCharsets.UTF_8);
            ctx.redirect(redirect);
        });

        app.post("/auth/saml/callback", ctx -> {
            setNoStore(ctx);
            String samlResponse = ctx.formParam("SAMLResponse");
            SamlService.RelayState relayState = samlService.decodeRelayState(ctx.formParam("RelayState"));
            UUID tenantId = relayState.tenantId() == null || relayState.tenantId().isBlank()
                    ? null : UUID.fromString(relayState.tenantId());
            if (tenantId == null) {
                throw new ApiException(400, "BAD_REQUEST", "tenant_id is required in RelayState");
            }
            SqlStore.IdpConfigRecord saml = store.findIdpConfig(tenantId, "SAML")
                    .orElseThrow(() -> new ApiException(404, "IDP_NOT_FOUND", "SAML IdP 設定が見つかりません。"));
            SamlService.SamlIdentity identity = samlService.parseIdentity(
                    samlResponse,
                    saml,
                    config.devMode(),
                    config.samlSkipSignature(),
                    config.baseUrl() + "/auth/saml/callback",
                    relayState.requestId()
            );
            String providerSub = "saml:" + SecurityUtils.sha256Hex((identity.issuer() == null ? "" : identity.issuer()) + "|" + identity.email().toLowerCase(Locale.ROOT));
            UserRecord user = store.upsertUser(identity.email(), identity.displayName(), providerSub);
            TenantRecord tenant = store.findTenantById(tenantId)
                    .orElseThrow(() -> new ApiException(404, "TENANT_NOT_FOUND", "テナントが見つかりません。"));
            MembershipRecord membership = store.findMembership(user.id(), tenant.id())
                    .orElseThrow(() -> new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant membership not found"));
            if (!membership.active()) {
                throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant membership not active");
            }
            AuthPrincipal principal = new AuthPrincipal(
                    user.id(),
                    user.email(),
                    user.displayName(),
                    tenant.id(),
                    tenant.name(),
                    tenant.slug(),
                    List.of(membership.role()),
                    false
            );
            UUID sessionId = authService.issueSession(principal, relayState.returnTo(), HttpSupport.clientIp(ctx), ctx.userAgent());
            setSessionCookie(ctx, sessionId, config.sessionTtlSeconds());
            auditService.log(ctx, "LOGIN_SUCCESS", principal, "SESSION", sessionId.toString(), Map.of(
                    "via", "saml",
                    "issuer", identity.issuer() == null ? "" : identity.issuer()
            ));
            checkDeviceAndNotify(principal, ctx, store, objectMapper);
            String returnTo = relayState.returnTo();
            if (!HttpSupport.isAllowedReturnTo(returnTo, config.allowedRedirectDomains())) {
                returnTo = null;
            }
            String redirectTo = (returnTo == null || returnTo.isBlank()) ? "/select-tenant" : returnTo;
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                ctx.json(Map.of("redirect_to", redirectTo));
            } else {
                ctx.redirect(redirectTo);
            }
        });

        app.post("/auth/refresh", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                throw new ApiException(401, "SESSION_EXPIRED", "セッションの有効期限が切れました。再ログインしてください。");
            }
            AuthPrincipal principal = principalOpt.get();
            String token = authService.issueJwt(principal);
            auditService.log(ctx, "SESSION_REFRESH", principal, "USER", principal.userId().toString(), Map.of());
            ctx.json(Map.of("token", token));
        });

        // GET logout for browser redirects (ForwardAuth, volta-console etc.)
        app.get("/auth/logout", ctx -> {
            setNoStore(ctx);
            String cookie = ctx.cookie(AuthService.SESSION_COOKIE);
            if (cookie != null) {
                try { sessionStore.revokeSession(UUID.fromString(cookie)); }
                catch (IllegalArgumentException ignored) {}
            }
            authService.clearSessionCookie(ctx);
            ctx.redirect(config.baseUrl() + "/login");
        });

        app.post("/auth/logout", ctx -> {
            setNoStore(ctx);
            AuthPrincipal principal = authService.authenticate(ctx).orElse(null);
            String cookie = ctx.cookie(AuthService.SESSION_COOKIE);
            if (cookie != null) {
                try {
                    sessionStore.revokeSession(UUID.fromString(cookie));
                } catch (IllegalArgumentException ignored) {
                }
            }
            auditService.log(ctx, "LOGOUT", principal, "SESSION", cookie, Map.of());
            authService.clearSessionCookie(ctx);
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                ctx.json(Map.of("ok", true));
            } else {
                ctx.redirect("/login");
            }
        });

        app.post("/auth/switch-account", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                ctx.redirect("/login");
                return;
            }
            String returnTo = ctx.formParam("return_to");

            // return_to validation: only /invite/ paths allowed
            String safeReturnTo = "/";
            if (returnTo != null) {
                try {
                    String normalized = java.net.URI.create(returnTo).normalize().getPath();
                    if (normalized.startsWith("/invite/")) {
                        safeReturnTo = normalized;
                    }
                } catch (Exception ignored) {}
            }

            // Revoke session (same pattern as logout)
            String cookie = ctx.cookie(AuthService.SESSION_COOKIE);
            if (cookie != null) {
                try {
                    sessionStore.revokeSession(UUID.fromString(cookie));
                } catch (IllegalArgumentException ignored) {}
            }
            auditService.log(ctx, "ACCOUNT_SWITCH", principalOpt.get(), "SESSION", cookie, Map.of(
                    "return_to", safeReturnTo
            ));
            authService.clearSessionCookie(ctx);

            ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(safeReturnTo, java.nio.charset.StandardCharsets.UTF_8));
        });

        app.get("/select-tenant", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                    throw new ApiException(401, "AUTHENTICATION_REQUIRED", "Authentication required");
                }
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            List<SqlStore.UserTenantInfo> infos = store.findTenantInfosByUser(principal.userId());
            List<Map<String, Object>> tenants = new ArrayList<>();
            for (SqlStore.UserTenantInfo info : infos) {
                tenants.add(Map.of(
                        "id", info.id().toString(),
                        "name", info.name(),
                        "slug", info.slug(),
                        "role", info.role(),
                        "isLast", info.id().equals(principal.tenantId())
                ));
            }
            ctx.render("auth/tenant-select.jte", model(
                    "title", "Tenant Select",
                    "tenants", tenants,
                    "returnTo", ctx.queryParam("return_to"),
                    "csrfToken", currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.post("/auth/switch-tenant", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            String oldSessionRaw = ctx.cookie(AuthService.SESSION_COOKIE);
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID tenantId = UUID.fromString(body.path("tenantId").asText());
            MembershipRecord m = store.findMembership(principal.userId(), tenantId)
                    .orElseThrow(() -> new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied"));
            if (!m.active()) {
                throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied");
            }
            TenantRecord tenant = store.findTenantById(tenantId).orElseThrow();
            AuthPrincipal switched = new AuthPrincipal(
                    principal.userId(),
                    principal.email(),
                    principal.displayName(),
                    tenant.id(),
                    tenant.name(),
                    tenant.slug(),
                    List.of(m.role()),
                    false
            );
            UUID sessionId = authService.issueSession(switched, null, HttpSupport.clientIp(ctx), ctx.userAgent());
            setSessionCookie(ctx, sessionId, config.sessionTtlSeconds());
            if (oldSessionRaw != null) {
                try {
                    sessionStore.revokeSession(UUID.fromString(oldSessionRaw));
                } catch (IllegalArgumentException ignored) {
                }
            }
            auditService.log(ctx, "TENANT_SWITCH", switched, "TENANT", tenantId.toString(), Map.of());
            ctx.json(Map.of("ok", true, "tenantId", tenantId.toString()));
        });
    }

    // --- Shared helpers used by this router ---

    static TenantRecord resolveTenant(SqlStore store, UserRecord user, String inviteCode) {
        if (inviteCode != null) {
            InvitationRecord invitation = store.findInvitationByCode(inviteCode)
                    .orElseThrow(() -> new ApiException(404, "INVITATION_NOT_FOUND", "招待リンクが見つかりません。"));
            return store.findTenantById(invitation.tenantId()).orElseThrow();
        }
        List<TenantRecord> tenants = store.findTenantsByUser(user.id());
        if (tenants.isEmpty()) {
            return store.createPersonalTenant(user);
        }
        return tenants.getFirst();
    }

    static AuthPrincipal requireAuth(io.javalin.http.Context ctx, AuthService authService) {
        return authService.authenticate(ctx)
                .orElseThrow(() -> new ApiException(401, "AUTHENTICATION_REQUIRED", "Authentication required"));
    }

    static void setSessionCookie(io.javalin.http.Context ctx, UUID sessionId, int sessionTtlSeconds) {
        String cookieDomain = System.getenv("COOKIE_DOMAIN");
        String cookie = AuthService.SESSION_COOKIE + "=" + sessionId
                + "; Path=/; Max-Age=" + sessionTtlSeconds
                + "; HttpOnly; SameSite=Lax";
        if (cookieDomain != null && !cookieDomain.isEmpty()) {
            cookie += "; Domain=" + cookieDomain;
        }
        if (ctx.req().isSecure()) {
            cookie += "; Secure";
        }
        ctx.header("Set-Cookie", cookie);
    }

    static void setNoStore(io.javalin.http.Context ctx) {
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
        ctx.header("Pragma", "no-cache");
    }

    static String currentCsrfToken(io.javalin.http.Context ctx, SessionStore sessionStore) {
        String sessionRaw = ctx.cookie(AuthService.SESSION_COOKIE);
        if (sessionRaw == null) {
            return "";
        }
        try {
            return sessionStore.findSession(UUID.fromString(sessionRaw))
                    .map(SessionRecord::csrfToken)
                    .orElse("");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static void checkDeviceAndNotify(AuthPrincipal principal, io.javalin.http.Context ctx,
                                      SqlStore store, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        try {
            String ua = ctx.userAgent();
            String fp = inferBrowser(ua) + "/" + inferOs(ua);
            String label = inferBrowser(ua) + " on " + inferOs(ua);
            String ip = HttpSupport.clientIp(ctx);
            int existing = store.countKnownDevices(principal.userId());
            boolean isNew = store.upsertKnownDevice(principal.userId(), fp, label, ip);
            if (isNew && existing > 0) {
                String payload = objectMapper.writeValueAsString(java.util.Map.of(
                        "to", principal.email(),
                        "displayName", principal.displayName() != null ? principal.displayName() : principal.email(),
                        "device", label,
                        "ip", ip,
                        "timestamp", java.time.Instant.now().toString()
                ));
                store.enqueueOutboxEvent(null, "notification.new_device", payload);
            }
        } catch (Exception e) {
            // Device notification must never block login
            System.err.println("Device check failed: " + e.getMessage());
        }
    }

    static String inferDevice(String ua) {
        if (ua == null) {
            return "\uD83D\uDDA5";
        }
        String lower = ua.toLowerCase();
        return (lower.contains("mobile") || lower.contains("android") || lower.contains("iphone")) ? "\uD83D\uDCF1" : "\uD83D\uDDA5";
    }

    static String inferBrowser(String ua) {
        if (ua == null) {
            return "Unknown";
        }
        String lower = ua.toLowerCase();
        if (lower.contains("edg/")) return "Edge";
        if (lower.contains("chrome/")) return "Chrome";
        if (lower.contains("safari/") && !lower.contains("chrome/")) return "Safari";
        if (lower.contains("firefox/")) return "Firefox";
        return "Browser";
    }

    static String inferOs(String ua) {
        if (ua == null) {
            return "Unknown";
        }
        String lower = ua.toLowerCase();
        // Check mobile before desktop (iPhone UA contains "Mac OS X")
        if (lower.contains("iphone") || lower.contains("ipad")) return "iOS";
        if (lower.contains("android")) return "Android";
        if (lower.contains("windows")) return "Windows";
        if (lower.contains("mac os")) return "macOS";
        if (lower.contains("linux")) return "Linux";
        return "OS";
    }
}
