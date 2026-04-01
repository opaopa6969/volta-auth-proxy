package com.volta.authproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinJte;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static io.javalin.rendering.template.TemplateUtil.model;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        HikariDataSource dataSource = Database.createDataSource(config);
        Database.migrate(dataSource);
        SqlStore store = new SqlStore(dataSource);
        SessionStore sessionStore = SessionStore.create(config, store);
        JwtService jwtService = new JwtService(config, store);
        AuthService authService = new AuthService(config, store, jwtService, sessionStore);
        VoltaConfig voltaConfig = ConfigLoader.load(config.appConfigPath());
        OidcService oidcService = new OidcService(config, store, voltaConfig);
        SamlService samlService = new SamlService();
        AppRegistry appRegistry = new AppRegistry(config);
        AuditSink auditSink = AuditSink.create(config);
        AuditService auditService = new AuditService(store, auditSink);
        NotificationService notificationService = NotificationService.create(config);
        OutboxWorker outboxWorker = new OutboxWorker(config, store);
        RateLimiter rateLimiter = new RateLimiter(200);
        ObjectMapper objectMapper = new ObjectMapper();
        KeyCipher secretCipher = new KeyCipher(config.jwtKeyEncryptionSecret());
        TemplateEngine templateEngine = TemplateEngine.create(
                new DirectoryCodeResolver(java.nio.file.Path.of("src/main/jte")),
                java.nio.file.Path.of("target/jte-classes"),
                ContentType.Html,
                Main.class.getClassLoader()
        );

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.fileRenderer(new JavalinJte(templateEngine));
            javalinConfig.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        app.before(ctx -> ctx.attribute("wantsJson", HttpSupport.wantsJson(ctx)));
        app.before(ctx -> {
            UUID requestId = SecurityUtils.newUuid();
            ctx.attribute("requestId", requestId);
            ctx.header("X-Request-Id", requestId.toString());
        });
        app.before(ctx -> {
            String method = ctx.method().name();
            if (!method.equals("POST") && !method.equals("DELETE") && !method.equals("PATCH")) {
                return;
            }
            if ("/api/v1/billing/stripe/webhook".equals(ctx.path())
                    || "/oauth/token".equals(ctx.path())
                    || "/auth/saml/callback".equals(ctx.path())
                    || ctx.path().startsWith("/scim/v2/")) {
                return;
            }
            if (isJsonOrXhr(ctx)) {
                return;
            }
            String sessionCookie = ctx.cookie(AuthService.SESSION_COOKIE);
            if (sessionCookie == null) {
                throw new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。");
            }
            SessionRecord session = sessionStore.findSession(UUID.fromString(sessionCookie))
                    .orElseThrow(() -> new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。"));
            String csrf = ctx.formParam("_csrf");
            if (csrf == null || session.csrfToken() == null || !session.csrfToken().equals(csrf)) {
                throw new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。");
            }
        });
        app.before(ctx -> {
            if (!authService.isMfaPending(ctx)) {
                return;
            }
            String path = ctx.path();
            boolean exempt = path.equals("/mfa/challenge")
                    || path.equals("/auth/mfa/verify")
                    || path.equals("/auth/logout")
                    || path.startsWith("/css/")
                    || path.startsWith("/js/");
            if (exempt) {
                return;
            }
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson")) || path.startsWith("/api/") || path.equals("/auth/verify")) {
                HttpSupport.jsonError(ctx, 401, "MFA_REQUIRED", "MFA verification required");
                ctx.skipRemainingHandlers();
                return;
            }
            ctx.redirect("/mfa/challenge");
            ctx.skipRemainingHandlers();
        });
        app.exception(ApiException.class, (e, ctx) -> {
            AuthPrincipal actor = authService.authenticate(ctx).orElse(null);
            auditService.log(ctx, "ERROR_" + e.code(), actor, "REQUEST", ctx.path(), Map.of("status", e.status()));
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                HttpSupport.jsonError(ctx, e.status(), e.code(), e.getMessage());
            } else {
                renderErrorPage(ctx, e.status(), e.code(), e.getMessage());
            }
        });
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            AuthPrincipal actor = authService.authenticate(ctx).orElse(null);
            auditService.log(ctx, "ERROR_BAD_REQUEST", actor, "REQUEST", ctx.path(), Map.of("detail", e.getMessage()));
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                HttpSupport.jsonError(ctx, 400, "BAD_REQUEST", e.getMessage());
            } else {
                renderErrorPage(ctx, 400, "BAD_REQUEST", e.getMessage());
            }
        });
        app.exception(Exception.class, (e, ctx) -> {
            AuthPrincipal actor = authService.authenticate(ctx).orElse(null);
            auditService.log(ctx, "ERROR_INTERNAL", actor, "REQUEST", ctx.path(), Map.of());
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                HttpSupport.jsonError(ctx, 500, "INTERNAL_ERROR", "Internal server error");
            } else {
                renderErrorPage(ctx, 500, "INTERNAL_ERROR", "Internal server error");
            }
        });

        app.get("/healthz", ctx -> ctx.json(Map.of("status", "ok")));
        app.get("/.well-known/jwks.json", ctx -> {
            ctx.header("Cache-Control", "public, max-age=60, stale-while-revalidate=86400");
            ctx.result(jwtService.jwksJson()).contentType("application/json");
        });

        app.get("/", ctx -> ctx.redirect("/login"));
        app.get("/login", ctx -> {
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                HttpSupport.jsonError(ctx, 401, "AUTHENTICATION_REQUIRED", "Login required");
                return;
            }
            ensureOidcConfig(oidcService);
            String returnToRaw = ctx.queryParam("return_to");
            String inviteCode = ctx.queryParam("invite");
            String returnTo = HttpSupport.isAllowedReturnTo(returnToRaw, config.allowedRedirectDomains()) ? returnToRaw : null;
            if ("1".equals(ctx.queryParam("start"))) {
                ctx.redirect(oidcService.createAuthorizationUrl(returnTo, inviteCode, ctx.queryParam("provider")));
                return;
            }
            Map<String, String> inviteContext = null;
            if (inviteCode != null && !inviteCode.isBlank()) {
                InvitationRecord invitation = store.findInvitationByCode(inviteCode).orElse(null);
                if (invitation != null) {
                    String tenantName = store.findTenantById(invitation.tenantId()).map(TenantRecord::name).orElse("ワークスペース");
                    String inviterName = store.findUserById(invitation.createdBy()).map(UserRecord::displayName).orElse("メンバー");
                    inviteContext = Map.of(
                            "tenantName", tenantName,
                            "inviterName", inviterName,
                            "role", invitation.role()
                    );
                }
            }
            String baseParams = (returnTo != null ? "&return_to=" + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8) : "")
                    + (inviteCode != null ? "&invite=" + java.net.URLEncoder.encode(inviteCode, java.nio.charset.StandardCharsets.UTF_8) : "");
            ctx.render("auth/login.jte", model(
                    "title",     "ログイン",
                    "inviteContext", inviteContext,
                    "providers", oidcService.enabledProviders(),
                    "baseParams", baseParams
            ));
        });

        app.get("/callback", ctx -> {
            setNoStore(ctx);
            if (ctx.queryParam("error") != null) {
                throw new ApiException(400, "OIDC_FAILED", "OIDC failed: " + ctx.queryParam("error"));
            }
            String code = requireQuery(ctx, "code");
            String state = requireQuery(ctx, "state");
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                String redirectTo = completeOidcCallback(ctx, code, state, config, store, authService, oidcService, auditService, appRegistry);
                ctx.json(Map.of("redirect_to", redirectTo));
                return;
            }
            ctx.render("auth/callback.jte", model(
                    "title", "ログイン処理中",
                    "code", code,
                    "state", state
            ));
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

        app.post("/auth/callback/complete", ctx -> {
            setNoStore(ctx);
            JsonNode body = objectMapper.readTree(ctx.body());
            String code = body.path("code").asText();
            String state = body.path("state").asText();
            if (code.isBlank() || state.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "code/state is required");
            }
            String redirectTo = completeOidcCallback(ctx, code, state, config, store, authService, oidcService, auditService, appRegistry);
            ctx.json(Map.of("redirect_to", redirectTo));
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
            UUID sessionId = authService.issueSession(principal, relayState.returnTo(), clientIp(ctx), ctx.userAgent());
            setSessionCookie(ctx, sessionId, config.sessionTtlSeconds());
            auditService.log(ctx, "LOGIN_SUCCESS", principal, "SESSION", sessionId.toString(), Map.of(
                    "via", "saml",
                    "issuer", identity.issuer() == null ? "" : identity.issuer()
            ));
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

        app.get("/mfa/challenge", ctx -> {
            if (!authService.isMfaPending(ctx)) {
                ctx.redirect("/select-tenant");
                return;
            }
            ctx.result("""
                    <!doctype html><html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>MFA認証</title><link rel="stylesheet" href="/css/volta.css"></head><body><main class="container">
                    <h1>MFA 認証</h1><p>TOTP コードを入力してください。</p>
                    <form id="mfa-form"><input name="code" inputmode="numeric" pattern="[0-9]*" maxlength="6" style="min-height:44px;" required>
                    <button class="button" type="submit">確認</button></form><p id="err" class="muted"></p>
                    <script>
                    document.getElementById('mfa-form').addEventListener('submit', async function(e){
                      e.preventDefault();
                      const code = Number(this.code.value || 0);
                      const res = await fetch('/auth/mfa/verify', {method:'POST', headers:{'Content-Type':'application/json','Accept':'application/json'}, body:JSON.stringify({code})});
                      if(!res.ok){ document.getElementById('err').textContent='コードが正しくありません'; return; }
                      const p = await res.json(); location.href = p.redirect_to || '/select-tenant';
                    });
                    </script></main></body></html>
                    """).contentType("text/html; charset=utf-8");
        });

        app.post("/auth/mfa/verify", ctx -> {
            SessionRecord session = authService.currentSession(ctx)
                    .orElseThrow(() -> new ApiException(401, "AUTHENTICATION_REQUIRED", "Authentication required"));
            JsonNode body = objectMapper.readTree(ctx.body());
            int code = body.path("code").asInt(-1);
            String recoveryCode = body.path("recovery_code").asText("");
            boolean verified;
            if (recoveryCode != null && !recoveryCode.isBlank()) {
                String hash = SecurityUtils.sha256Hex(recoveryCode.replace("-", "").toUpperCase(Locale.ROOT));
                verified = store.consumeRecoveryCode(session.userId(), hash);
            } else {
                SqlStore.UserMfaRecord mfa = store.findUserMfa(session.userId(), "totp")
                        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "MFA setup not found"));
                com.warrenstrange.googleauth.GoogleAuthenticator ga = new com.warrenstrange.googleauth.GoogleAuthenticator();
                verified = ga.authorize(secretCipher.decrypt(mfa.secret()), code);
            }
            if (!verified) {
                throw new ApiException(400, "MFA_INVALID_CODE", "MFA code is invalid");
            }
            authService.markMfaVerified(session.id());
            String next = session.returnTo();
            if (next != null && next.startsWith("invite:")) {
                next = "/invite/" + next.substring("invite:".length());
            } else if (next == null || next.isBlank()) {
                next = "/select-tenant";
            }
            ctx.json(Map.of("ok", true, "redirect_to", next));
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
            UUID sessionId = authService.issueSession(switched, null, clientIp(ctx), ctx.userAgent());
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

        app.get("/invite/{code}", ctx -> {
            String code = ctx.pathParam("code");
            InvitationRecord invitation = store.findInvitationByCode(code)
                    .orElseThrow(() -> new ApiException(404, "INVITATION_NOT_FOUND", "招待リンクが見つかりません。"));
            TenantRecord tenant = store.findTenantById(invitation.tenantId())
                    .orElseThrow(() -> new ApiException(404, "TENANT_NOT_FOUND", "テナントが見つかりません。"));
            String inviter = store.findUserById(invitation.createdBy())
                    .map(UserRecord::displayName)
                    .orElse("メンバー");
            if (!invitation.isUsableAt(Instant.now())) {
                String codeType = invitation.expiresAt().isBefore(Instant.now()) ? "INVITATION_EXPIRED" : "INVITATION_EXHAUSTED";
                String message = invitation.expiresAt().isBefore(Instant.now())
                        ? "招待リンクの有効期限が切れています。"
                        : "この招待リンクは使用済みです。";
                ctx.status(410).render("error/error.jte", model(
                        "code", codeType,
                        "message", message,
                        "actionHref", "/login",
                        "actionLabel", "ログイン"
                ));
                return;
            }
            Optional<AuthPrincipal> principal = authService.authenticate(ctx);
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                ctx.json(Map.of(
                        "code", invitation.code(),
                        "tenantId", tenant.id().toString(),
                        "tenantName", tenant.name(),
                        "role", invitation.role(),
                        "inviterName", inviter,
                        "expiresAt", invitation.expiresAt().toString(),
                        "requiresLogin", principal.isEmpty()
                ));
                return;
            }
            if (principal.isEmpty()) {
                ctx.render("auth/invite-consent.jte", model(
                        "title", "招待リンク",
                        "code", code,
                        "tenantName", tenant.name(),
                        "role", invitation.role(),
                        "inviterName", inviter,
                        "isLoggedIn", false,
                        "csrfToken", "",
                        "inviteLoginHref", "/login?invite=" + code
                ));
                return;
            }
            ctx.render("auth/invite-consent.jte", model(
                    "title", "招待参加の確認",
                    "code", code,
                "tenantName", tenant.name(),
                "role", invitation.role(),
                "inviterName", inviter,
                "isLoggedIn", true,
                "csrfToken", currentCsrfToken(ctx, sessionStore),
                "inviteLoginHref", "/login?invite=" + code
            ));
        });

        app.post("/invite/{code}/accept", ctx -> {
            String code = ctx.pathParam("code");
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                    throw new ApiException(401, "AUTHENTICATION_REQUIRED", "Authentication required");
                }
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            InvitationRecord invitation = store.findInvitationByCode(code)
                    .orElseThrow(() -> new ApiException(404, "INVITATION_NOT_FOUND", "招待リンクが見つかりません。"));
            if (!invitation.isUsableAt(Instant.now())) {
                throw new ApiException(410, "INVITATION_EXPIRED", "招待リンクの有効期限が切れているか使用済みです。");
            }
            if (invitation.email() != null && !invitation.email().equalsIgnoreCase(principal.email())) {
                throw new ApiException(403, "INVITATION_EMAIL_MISMATCH", "Invitation email mismatch");
            }
            store.acceptInvitation(invitation.id(), invitation.tenantId(), principal.userId(), invitation.role());
            TenantRecord tenant = store.findTenantById(invitation.tenantId()).orElseThrow();
            AuthPrincipal switched = new AuthPrincipal(
                    principal.userId(),
                    principal.email(),
                    principal.displayName(),
                    tenant.id(),
                    tenant.name(),
                    tenant.slug(),
                    List.of(invitation.role()),
                    false
            );
            UUID sessionId = authService.issueSession(switched, null, clientIp(ctx), ctx.userAgent());
            setSessionCookie(ctx, sessionId, config.sessionTtlSeconds());
            auditService.log(ctx, "INVITATION_ACCEPTED", switched, "INVITATION", invitation.id().toString(), Map.of(
                    "code", code,
                    "role", invitation.role()
            ));
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                ctx.json(Map.of("ok", true, "redirect_to", "/settings/sessions"));
                return;
            }
            setFlashCookie(ctx, "✅ " + tenant.name() + " に参加しました");
            ctx.redirect("/settings/sessions");
        });

        app.get("/settings/sessions", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                    throw new ApiException(401, "AUTHENTICATION_REQUIRED", "Authentication required");
                }
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            List<SessionRecord> sessions = sessionStore.listUserSessions(principal.userId());
            String currentSessionId = ctx.cookie(AuthService.SESSION_COOKIE);
            List<Map<String, String>> sessionView = new ArrayList<>();
            for (SessionRecord s : sessions) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", s.id().toString());
                row.put("tenantId", s.tenantId().toString());
                row.put("ip", s.ipAddress() == null ? "-" : s.ipAddress());
                row.put("userAgent", s.userAgent() == null ? "-" : s.userAgent());
                row.put("device", inferDevice(s.userAgent()));
                row.put("browser", inferBrowser(s.userAgent()));
                row.put("os", inferOs(s.userAgent()));
                row.put("lastActive", s.lastActiveAt().toString());
                row.put("expiresAt", s.expiresAt().toString());
                row.put("status", s.invalidatedAt() == null ? "ACTIVE" : "REVOKED");
                row.put("isCurrent", String.valueOf(s.id().toString().equals(currentSessionId)));
                sessionView.add(row);
            }
            String flashMessage = popFlashCookie(ctx);
            ctx.render("auth/sessions.jte", model(
                    "title", "Sessions",
                    "sessionCount", sessions.size(),
                    "sessions", sessionView,
                    "csrfToken", currentCsrfToken(ctx, sessionStore),
                    "flashMessage", flashMessage
            ));
        });

        app.delete("/auth/sessions/{id}", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            UUID sessionId = UUID.fromString(ctx.pathParam("id"));
            SessionRecord session = sessionStore.findSession(sessionId).orElse(null);
            if (session == null || !session.userId().equals(principal.userId())) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            sessionStore.revokeSession(sessionId);
            auditService.log(ctx, "SESSION_REVOKED", principal, "SESSION", sessionId.toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        app.post("/auth/sessions/revoke-all", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            sessionStore.revokeAllSessions(principal.userId());
            authService.clearSessionCookie(ctx);
            auditService.log(ctx, "SESSIONS_REVOKED_ALL", principal, "USER", principal.userId().toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/me/sessions", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            List<Map<String, String>> sessions = sessionStore.listUserSessions(principal.userId()).stream()
                    .filter(s -> s.isValidAt(Instant.now()))
                    .map(s -> Map.of(
                            "id", s.id().toString(),
                            "tenantId", s.tenantId().toString(),
                            "ip", s.ipAddress() == null ? "-" : s.ipAddress(),
                            "lastActiveAt", s.lastActiveAt().toString(),
                            "device", inferDevice(s.userAgent()),
                            "browser", inferBrowser(s.userAgent()),
                            "os", inferOs(s.userAgent())
                    ))
                    .toList();
            ctx.json(Map.of("items", sessions));
        });

        app.delete("/api/me/sessions/{id}", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            UUID sessionId = UUID.fromString(ctx.pathParam("id"));
            SessionRecord target = sessionStore.findSession(sessionId)
                    .orElseThrow(() -> new ApiException(404, "SESSION_NOT_FOUND", "セッションが見つかりません。"));
            if (!target.userId().equals(principal.userId())) {
                throw new ApiException(403, "FORBIDDEN", "他ユーザーのセッションは操作できません。");
            }
            sessionStore.revokeSession(sessionId);
            auditService.log(ctx, "SESSION_REVOKED", principal, "SESSION", sessionId.toString(), Map.of("via", "api-me"));
            ctx.json(Map.of("ok", true));
        });

        app.delete("/api/me/sessions", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            sessionStore.revokeAllSessions(principal.userId());
            authService.clearSessionCookie(ctx);
            auditService.log(ctx, "SESSIONS_REVOKED_ALL", principal, "USER", principal.userId().toString(), Map.of("via", "api-me"));
            ctx.json(Map.of("ok", true));
        });

        app.get("/admin/members", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            if (!principal.roles().contains("ADMIN") && !principal.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "管理画面へのアクセス権限がありません。");
            }
            List<MembershipRecord> members = store.listTenantMembers(principal.tenantId(), 0, 100);
            List<Map<String, String>> memberView = new ArrayList<>();
            for (MembershipRecord m : members) {
                UserRecord u = store.findUserById(m.userId()).orElse(null);
                if (u == null) {
                    continue;
                }
                memberView.add(Map.of(
                        "memberId", m.id().toString(),
                        "userId", m.userId().toString(),
                        "email", u.email(),
                        "displayName", u.displayName() == null ? "-" : u.displayName(),
                        "role", m.role()
                ));
            }
            ctx.render("admin/members.jte", model(
                    "title", "メンバー管理",
                    "tenantId", principal.tenantId().toString(),
                    "members", memberView,
                    "currentUserRole", principal.roles().getFirst(),
                    "csrfToken", currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/invitations", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            if (!principal.roles().contains("ADMIN") && !principal.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "管理画面へのアクセス権限がありません。");
            }
            List<InvitationRecord> invitations = store.listInvitations(principal.tenantId(), 0, 100);
            List<Map<String, String>> invitationView = invitations.stream().map(i -> Map.of(
                    "id", i.id().toString(),
                    "code", i.code().substring(0, Math.min(8, i.code().length())) + "...",
                    "role", i.role(),
                    "status", i.expiresAt().isBefore(Instant.now()) ? "❌ 期限切れ" : (i.usedCount() > 0 ? "✅ 使用済み" : "⏳ 未使用"),
                    "expiresAt", i.expiresAt().toString(),
                    "createdBy", i.createdBy().toString()
            )).toList();
            ctx.render("admin/invitations.jte", model(
                    "title", "招待管理",
                    "tenantId", principal.tenantId().toString(),
                    "invitations", invitationView,
                    "csrfToken", currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/webhooks", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            if (!principal.roles().contains("ADMIN") && !principal.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "管理画面へのアクセス権限がありません。");
            }
            List<Map<String, String>> view = store.listWebhooks(principal.tenantId()).stream()
                    .map(w -> Map.of(
                            "id", w.id().toString(),
                            "url", w.endpointUrl(),
                            "events", w.events(),
                            "active", String.valueOf(w.active()),
                            "createdAt", w.createdAt().toString()
                    )).toList();
            ctx.render("admin/webhooks.jte", model(
                    "title", "Webhook 管理",
                    "tenantId", principal.tenantId().toString(),
                    "webhooks", view,
                    "csrfToken", currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/idp", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            if (!principal.roles().contains("ADMIN") && !principal.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "管理画面へのアクセス権限がありません。");
            }
            List<Map<String, String>> view = store.listIdpConfigs(principal.tenantId()).stream()
                    .map(i -> Map.of(
                            "id", i.id().toString(),
                            "provider", i.providerType(),
                            "issuer", i.issuer() == null ? "" : i.issuer(),
                            "metadataUrl", i.metadataUrl() == null ? "" : i.metadataUrl(),
                            "clientId", i.clientId() == null ? "" : i.clientId(),
                            "x509Cert", i.x509Cert() == null ? "" : i.x509Cert()
                    )).toList();
            ctx.render("admin/idp.jte", model(
                    "title", "IdP 設定",
                    "tenantId", principal.tenantId().toString(),
                    "idps", view,
                    "csrfToken", currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/tenants", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            requireOwner(principal);
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            List<Map<String, String>> items = store.listTenants(offset, limit).stream().map(t -> Map.of(
                    "id", t.id().toString(),
                    "name", t.name(),
                    "slug", t.slug(),
                    "plan", t.plan(),
                    "active", String.valueOf(t.active()),
                    "memberCount", String.valueOf(t.memberCount()),
                    "createdAt", t.createdAt().toString()
            )).toList();
            ctx.render("admin/tenants.jte", model(
                    "title", "テナント管理",
                    "tenants", items,
                    "csrfToken", currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/users", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            requireOwner(principal);
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            List<Map<String, String>> items = store.listUsers(offset, limit).stream().map(u -> Map.of(
                    "id", u.id().toString(),
                    "email", u.email(),
                    "displayName", u.displayName() == null ? "" : u.displayName(),
                    "locale", u.locale() == null ? "ja" : u.locale(),
                    "active", String.valueOf(u.active()),
                    "createdAt", u.createdAt().toString()
            )).toList();
            ctx.render("admin/users.jte", model("title", "ユーザー管理", "users", items));
        });

        app.get("/admin/audit", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            if (!principal.roles().contains("ADMIN") && !principal.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "監査ログ参照の権限がありません。");
            }
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            List<Map<String, Object>> logs = store.listAuditLogs(principal.tenantId(), offset, limit);
            ctx.render("admin/audit.jte", model("title", "監査ログ", "logs", logs));
        });

        app.post("/dev/token", ctx -> {
            if (!config.devMode() || !isLocalRequest(ctx)) {
                HttpSupport.jsonError(ctx, 403, "FORBIDDEN", "DEV_MODE only");
                return;
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            AuthPrincipal principal = new AuthPrincipal(
                    UUID.fromString(body.path("userId").asText()),
                    body.path("email").asText("dev@example.local"),
                    body.path("displayName").asText("dev"),
                    UUID.fromString(body.path("tenantId").asText()),
                    body.path("tenantName").asText("dev-tenant"),
                    body.path("tenantSlug").asText("dev-tenant"),
                    toStringList(body.path("roles")),
                    false
            );
            ctx.json(Map.of("token", jwtService.issueToken(principal)));
        });

        app.post("/oauth/token", ctx -> {
            String grantType = ctx.formParam("grant_type");
            String clientId = ctx.formParam("client_id");
            String clientSecret = ctx.formParam("client_secret");
            String scopeParam = ctx.formParam("scope");
            String audienceParam = ctx.formParam("audience");
            if (!"client_credentials".equals(grantType)) {
                throw new ApiException(400, "UNSUPPORTED_GRANT_TYPE", "grant_type=client_credentials のみ対応しています。");
            }
            if (clientId == null || clientSecret == null) {
                throw new ApiException(400, "INVALID_CLIENT", "client_id / client_secret が必要です。");
            }
            SqlStore.M2mClientRecord client = store.findM2mClient(clientId)
                    .orElseThrow(() -> new ApiException(401, "INVALID_CLIENT", "クライアント認証に失敗しました。"));
            if (!client.active()) {
                throw new ApiException(401, "INVALID_CLIENT", "クライアントは無効です。");
            }
            String providedHash = SecurityUtils.sha256Hex(clientSecret);
            if (!SecurityUtils.constantTimeEquals(client.clientSecretHash(), providedHash)) {
                throw new ApiException(401, "INVALID_CLIENT", "クライアント認証に失敗しました。");
            }
            List<String> scopes = csvToList(client.scopes());
            if (scopeParam != null && !scopeParam.isBlank()) {
                List<String> requested = Arrays.stream(scopeParam.split("\\s+"))
                        .map(String::trim).filter(s -> !s.isBlank()).toList();
                if (!scopes.containsAll(requested)) {
                    throw new ApiException(403, "SCOPE_NOT_ALLOWED", "要求された scope は許可されていません。");
                }
                scopes = requested;
            }
            List<String> audience = audienceParam == null || audienceParam.isBlank()
                    ? List.of(config.jwtAudience())
                    : Arrays.stream(audienceParam.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
            String token = jwtService.issueM2mToken(client.id(), client.tenantId(), scopes, audience, client.clientId());
            store.enqueueOutboxEvent(client.tenantId(), "oauth.token.issued", "{\"client_id\":\"" + client.clientId() + "\"}");
            ctx.json(Map.of(
                    "access_token", token,
                    "token_type", "Bearer",
                    "expires_in", config.jwtTtlSeconds(),
                    "scope", String.join(" ", scopes)
            ));
        });

        app.get("/auth/verify", ctx -> {
            setNoStore(ctx);
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                if (isSuspendedTenantSession(ctx, sessionStore, store)) {
                    ctx.status(403);
                    return;
                }
                ctx.status(401);
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            String appId = ctx.header("X-Volta-App-Id");
            String forwardedHost = ctx.header("X-Forwarded-Host");
            Optional<AppRegistry.AppPolicy> policy = appRegistry.resolve(appId, forwardedHost);
            if (appId != null && !appId.isBlank() && policy.isEmpty()) {
                ctx.status(401);
                return;
            }
            if (policy.isPresent() && Collections.disjoint(principal.roles(), policy.get().allowedRoles())) {
                ctx.status(401);
                return;
            }
            String jwt = jwtService.issueToken(principal);
            ctx.header("X-Volta-User-Id", principal.userId().toString());
            ctx.header("X-Volta-Email", principal.email());
            ctx.header("X-Volta-Tenant-Id", principal.tenantId().toString());
            ctx.header("X-Volta-Tenant-Slug", principal.tenantSlug());
            ctx.header("X-Volta-Roles", String.join(",", principal.roles()));
            ctx.header("X-Volta-Display-Name", principal.displayName() == null ? "" : principal.displayName());
            ctx.header("X-Volta-JWT", jwt);
            policy.ifPresent(p -> ctx.header("X-Volta-App-Id", p.id()));
            ctx.status(200);
        });

        app.before("/api/v1/*", ctx -> {
            if ("/api/v1/billing/stripe/webhook".equals(ctx.path())) {
                return;
            }
            String authHeader = ctx.header("Authorization");
            if ((authHeader == null || !authHeader.startsWith("Bearer "))
                    && !isJsonOrXhr(ctx)) {
                HttpSupport.jsonError(ctx, 403, "CSRF_INVALID", "CSRF トークンが無効です。");
                ctx.skipRemainingHandlers();
                return;
            }
            Optional<AuthPrincipal> principal = authService.authenticate(ctx);
            if (principal.isEmpty()) {
                HttpSupport.jsonError(ctx, 401, "AUTHENTICATION_REQUIRED", "Authentication required");
                ctx.skipRemainingHandlers();
                return;
            }
            String limitKey = principal.get().serviceToken() ? "service" : principal.get().userId().toString();
            int limit = 200;
            if (!rateLimiter.allow(limitKey)) {
                ctx.header("Retry-After", "60");
                ctx.header("X-RateLimit-Limit", String.valueOf(limit));
                ctx.header("X-RateLimit-Remaining", "0");
                HttpSupport.jsonError(ctx, 429, "RATE_LIMITED", "Too many requests");
                ctx.skipRemainingHandlers();
                return;
            }
            ctx.header("X-RateLimit-Limit", String.valueOf(limit));
            ctx.attribute("principal", principal.get());
        });

        app.get("/api/v1/admin/keys", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            ctx.json(Map.of("items", store.listSigningKeys()));
        });

        app.post("/api/v1/admin/keys/rotate", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            String newKid = jwtService.rotateKey();
            auditService.log(ctx, "SIGNING_KEY_ROTATED", p, "SIGNING_KEY", newKid, Map.of());
            ctx.json(Map.of("ok", true, "kid", newKid));
        });

        app.post("/api/v1/admin/keys/{kid}/revoke", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            String kid = ctx.pathParam("kid");
            store.revokeSigningKey(kid);
            auditService.log(ctx, "SIGNING_KEY_REVOKED", p, "SIGNING_KEY", kid, Map.of());
            ctx.json(Map.of("ok", true, "kid", kid));
        });

        app.get("/api/v1/users/me", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            ctx.json(Map.of(
                    "id", p.userId().toString(),
                    "email", p.email(),
                    "displayName", p.displayName(),
                    "tenantId", p.tenantId().toString(),
                    "roles", p.roles()
            ));
        });

        app.get("/api/v1/users/{id}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            if (!p.serviceToken() && !p.userId().equals(userId) && !p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "FORBIDDEN", "権限がありません。");
            }
            UserRecord user = store.findUserById(userId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "ユーザーが見つかりません。"));
            ctx.json(Map.of(
                    "id", user.id().toString(),
                    "email", user.email(),
                    "displayName", user.displayName()
            ));
        });

        app.get("/api/v1/users/me/tenants", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            List<Map<String, Object>> data = new ArrayList<>();
            List<SqlStore.UserTenantInfo> infos = store.findTenantInfosByUser(p.userId());
            UUID lastTenant = p.tenantId();
            for (SqlStore.UserTenantInfo info : infos) {
                data.add(Map.of(
                        "id", info.id().toString(),
                        "name", info.name(),
                        "slug", info.slug(),
                        "role", info.role(),
                        "isLast", info.id().equals(lastTenant)
                ));
            }
            ctx.json(Map.of("data", data));
        });

        app.get("/api/v1/tenants/{tenantId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            SqlStore.TenantDetailRecord tenant = store.findTenantDetailById(tenantId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "テナントが見つかりません。"));
            ctx.json(tenant);
        });

        app.patch("/api/v1/tenants/{tenantId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String name = body.path("name").isMissingNode() ? null : body.path("name").asText(null);
            Boolean autoJoin = body.path("auto_join").isMissingNode() ? null : body.path("auto_join").asBoolean();
            String logoUrl = body.path("logo_url").isMissingNode() ? null : body.path("logo_url").asText(null);
            String primaryColor = body.path("primary_color").isMissingNode() ? null : body.path("primary_color").asText(null);
            String theme = body.path("theme").isMissingNode() ? null : body.path("theme").asText(null);
            SqlStore.TenantDetailRecord updated = store.updateTenantSettings(tenantId, name, autoJoin, logoUrl, primaryColor, theme)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "テナントが見つかりません。"));
            ctx.json(updated);
        });

        app.patch("/api/v1/users/{userId}/locale", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self update is allowed");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String locale = body.path("locale").asText("ja");
            if (!locale.equals("ja") && !locale.equals("en")) {
                throw new ApiException(400, "BAD_REQUEST", "locale は ja または en を指定してください。");
            }
            int updated = store.setUserLocale(userId, locale);
            if (updated == 0) {
                throw new ApiException(404, "NOT_FOUND", "ユーザーが見つかりません。");
            }
            ctx.json(Map.of("ok", true, "locale", locale));
        });

        app.post("/api/v1/users/{userId}/mfa/totp/setup", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self update is allowed");
            }
            com.warrenstrange.googleauth.GoogleAuthenticator ga = new com.warrenstrange.googleauth.GoogleAuthenticator();
            final com.warrenstrange.googleauth.GoogleAuthenticatorKey key = ga.createCredentials();
            store.upsertUserMfa(userId, "totp", secretCipher.encrypt(key.getKey()), false);
            String issuer = java.net.URLEncoder.encode("volta-auth-proxy", java.nio.charset.StandardCharsets.UTF_8);
            String label = java.net.URLEncoder.encode(p.email(), java.nio.charset.StandardCharsets.UTF_8);
            String otpauth = "otpauth://totp/" + issuer + ":" + label + "?secret=" + key.getKey() + "&issuer=" + issuer;
            ctx.json(Map.of("secret", key.getKey(), "otpauth_url", otpauth));
        });

        app.post("/api/v1/users/{userId}/mfa/totp/verify", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self update is allowed");
            }
            SqlStore.UserMfaRecord mfa = store.findUserMfa(userId, "totp")
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "MFA setup not found"));
            JsonNode body = objectMapper.readTree(ctx.body());
            int code = body.path("code").asInt(-1);
            com.warrenstrange.googleauth.GoogleAuthenticator ga = new com.warrenstrange.googleauth.GoogleAuthenticator();
            boolean ok = ga.authorize(secretCipher.decrypt(mfa.secret()), code);
            if (!ok) {
                throw new ApiException(400, "MFA_INVALID_CODE", "TOTP code is invalid");
            }
            store.upsertUserMfa(userId, "totp", mfa.secret(), true);
            ctx.json(Map.of("ok", true, "enabled", true));
        });

        app.post("/api/v1/users/{userId}/mfa/recovery-codes/regenerate", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self update is allowed");
            }
            if (store.findUserMfa(userId, "totp").isEmpty()) {
                throw new ApiException(400, "BAD_REQUEST", "TOTP must be configured first");
            }
            List<String> codes = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String raw = SecurityUtils.randomUrlSafe(6).replace("-", "").replace("_", "").toUpperCase(Locale.ROOT);
                String code = raw.substring(0, 4) + "-" + raw.substring(4, 8);
                codes.add(code);
                hashes.add(SecurityUtils.sha256Hex(code.replace("-", "")));
            }
            store.replaceRecoveryCodes(userId, hashes);
            ctx.json(Map.of("codes", codes));
        });

        app.patch("/api/v1/users/{userId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self update is allowed");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String displayName = body.path("display_name").asText();
            int updated = store.updateUserDisplayName(userId, displayName);
            if (updated == 0) {
                ctx.status(404);
                return;
            }
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/v1/tenants/{tenantId}/members", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            List<MembershipRecord> members = store.listTenantMembers(tenantId, offset, limit);
            ctx.json(Map.of("items", members, "offset", offset, "limit", limit));
        });

        app.get("/api/v1/tenants/{tenantId}/members/{userId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            enforceTenantMatch(p, tenantId);
            MembershipRecord member = store.findMembershipByUser(tenantId, userId)
                    .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "メンバーが見つかりません。"));
            ctx.json(member);
        });

        app.patch("/api/v1/tenants/{tenantId}/members/{memberId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            UUID memberId = UUID.fromString(ctx.pathParam("memberId"));
            JsonNode body = objectMapper.readTree(ctx.body());
            String role = body.path("role").asText();
            MembershipRecord target = store.findMembershipById(memberId, tenantId)
                    .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "メンバーが見つかりません。"));
            if ("OWNER".equalsIgnoreCase(target.role()) && !"OWNER".equalsIgnoreCase(role)) {
                int ownerCount = store.countActiveOwners(tenantId);
                if (ownerCount <= 1) {
                    throw new ApiException(400, "LAST_OWNER_CANNOT_CHANGE", "テナントには最低 1 人の OWNER が必要です。");
                }
            }
            if (!"OWNER".equalsIgnoreCase(target.role()) && "OWNER".equalsIgnoreCase(role)) {
                throw new ApiException(400, "OWNER_ROLE_IMMUTABLE", "OWNER への変更は専用 API が必要です。");
            }
            int updated = store.updateMemberRole(tenantId, memberId, role);
            if (updated == 0) {
                ctx.status(404);
                return;
            }
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/v1/tenants/{tenantId}/invitations", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String code = SecurityUtils.inviteCode();
            String email = body.path("email").isMissingNode() ? null : body.path("email").asText(null);
            String role = body.path("role").asText("MEMBER");
            int maxUses = body.path("max_uses").asInt(1);
            Instant expiresAt = Instant.now().plus(body.path("expires_in_hours").asInt(72), ChronoUnit.HOURS);
            UUID invitationId = store.createInvitation(tenantId, code, email, role, maxUses, p.userId(), expiresAt);
            auditService.log(ctx, "INVITATION_CREATED", p, "INVITATION", invitationId.toString(), Map.of(
                    "role", role,
                    "maxUses", maxUses
            ));
            if (email != null && !email.isBlank()) {
                String tenantName = store.findTenantById(tenantId).map(TenantRecord::name).orElse("Workspace");
                String inviterName = store.findUserById(p.userId()).map(UserRecord::displayName).orElse(p.displayName());
                notificationService.sendInvitationEmail(email, config.baseUrl() + "/invite/" + code, tenantName, role, inviterName);
            }
            ctx.status(201).json(Map.of(
                    "id", invitationId.toString(),
                    "code", code,
                    "link", config.baseUrl() + "/invite/" + code,
                    "expiresAt", expiresAt.toString()
            ));
        });

        app.post("/api/v1/tenants/{tenantId}/transfer-ownership", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID newOwnerUserId = UUID.fromString(body.path("user_id").asText());
            int updated = store.transferOwnership(tenantId, p.userId(), newOwnerUserId);
            if (updated == 0) {
                throw new ApiException(400, "BAD_REQUEST", "owner transfer failed");
            }
            auditService.log(ctx, "TENANT_OWNERSHIP_TRANSFERRED", p, "TENANT", tenantId.toString(), Map.of("to", newOwnerUserId.toString()));
            ctx.json(Map.of("ok", true, "new_owner_user_id", newOwnerUserId.toString()));
        });

        app.get("/api/v1/tenants/{tenantId}/invitations", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            List<InvitationRecord> invitations = store.listInvitations(tenantId, offset, limit);
            ctx.json(Map.of("items", invitations, "offset", offset, "limit", limit));
        });

        app.delete("/api/v1/tenants/{tenantId}/invitations/{invitationId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            UUID invitationId = UUID.fromString(ctx.pathParam("invitationId"));
            int deleted = store.cancelInvitation(tenantId, invitationId);
            if (deleted == 0) {
                throw new ApiException(404, "INVITATION_NOT_FOUND", "取消可能な招待が見つかりません。");
            }
            auditService.log(ctx, "INVITATION_CANCELLED", p, "INVITATION", invitationId.toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        app.delete("/api/v1/tenants/{tenantId}/members/{memberId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            UUID memberId = UUID.fromString(ctx.pathParam("memberId"));
            MembershipRecord target = store.findMembershipById(memberId, tenantId)
                    .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "メンバーが見つかりません。"));
            if ("OWNER".equalsIgnoreCase(target.role()) && store.countActiveOwners(tenantId) <= 1) {
                throw new ApiException(400, "LAST_OWNER_CANNOT_CHANGE", "最後の OWNER は削除できません。");
            }
            int updated = store.deactivateMember(tenantId, memberId);
            if (updated == 0) {
                throw new ApiException(404, "MEMBER_NOT_FOUND", "メンバーが見つかりません。");
            }
            sessionStore.revokeSessionsForUserTenant(target.userId(), tenantId);
            auditService.log(ctx, "MEMBER_REMOVED", p, "MEMBER", memberId.toString(), Map.of());
            store.enqueueOutboxEvent(tenantId, "member.removed", "{\"member_id\":\"" + memberId + "\"}");
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/v1/tenants", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            if (!config.allowSelfServiceTenant()) {
                throw new ApiException(403, "FORBIDDEN", "self-service tenant 作成は無効です。");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String name = body.path("name").asText();
            String slug = body.path("slug").asText();
            if (name.isBlank() || slug.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "name / slug が必要です。");
            }
            UUID tenantId = store.createTenant(p.userId(), name, slug, body.path("auto_join").asBoolean(false));
            ctx.status(201).json(Map.of("id", tenantId.toString(), "name", name, "slug", slug));
        });

        app.get("/api/v1/tenants/{tenantId}/webhooks", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            ctx.json(Map.of("items", store.listWebhooks(tenantId)));
        });

        app.post("/api/v1/tenants/{tenantId}/webhooks", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String endpoint = body.path("endpoint_url").asText();
            if (endpoint.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "endpoint_url が必要です。");
            }
            String secret = body.path("secret").asText();
            if (secret.isBlank()) {
                secret = SecurityUtils.randomUrlSafe(24);
            }
            String events = body.path("events").isArray()
                    ? String.join(",", toStringList(body.path("events")))
                    : body.path("events").asText("member.joined,member.removed,user.deleted");
            UUID id = store.createWebhook(tenantId, endpoint, secret, events);
            auditService.log(ctx, "WEBHOOK_CREATED", p, "WEBHOOK", id.toString(), Map.of("endpoint", endpoint));
            ctx.status(201).json(Map.of("id", id.toString(), "secret", secret, "events", events));
        });

        app.delete("/api/v1/tenants/{tenantId}/webhooks/{webhookId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID webhookId = UUID.fromString(ctx.pathParam("webhookId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            int updated = store.deactivateWebhook(tenantId, webhookId);
            if (updated == 0) {
                throw new ApiException(404, "NOT_FOUND", "Webhook が見つかりません。");
            }
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/v1/tenants/{tenantId}/idp-configs", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            ctx.json(Map.of("items", store.listIdpConfigs(tenantId)));
        });

        app.post("/api/v1/tenants/{tenantId}/idp-configs", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String providerType = body.path("provider_type").asText("OIDC").toUpperCase(Locale.ROOT);
            String metadataUrl = body.path("metadata_url").asText(null);
            String issuer = body.path("issuer").asText(null);
            String clientId = body.path("client_id").asText(null);
            String x509Cert = body.path("x509_cert").asText(null);
            UUID id = store.upsertIdpConfig(tenantId, providerType, metadataUrl, issuer, clientId, x509Cert);
            ctx.status(201).json(Map.of("id", id.toString(), "providerType", providerType));
        });

        app.get("/api/v1/tenants/{tenantId}/m2m-clients", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            List<Map<String, Object>> items = store.listM2mClients(tenantId).stream().map(c -> Map.of(
                    "id", c.id().toString(),
                    "tenantId", c.tenantId().toString(),
                    "clientId", c.clientId(),
                    "scopes", csvToList(c.scopes()),
                    "active", c.active()
            )).toList();
            ctx.json(Map.of("items", items));
        });

        app.post("/api/v1/tenants/{tenantId}/m2m-clients", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String name = body.path("name").asText("m2m");
            String clientId = body.path("client_id").asText("m2m_" + tenantId.toString().substring(0, 8) + "_" + SecurityUtils.randomUrlSafe(6));
            String secret = SecurityUtils.randomUrlSafe(24);
            List<String> scopes = body.path("scopes").isArray() ? toStringList(body.path("scopes")) : List.of("service:read");
            UUID id = store.createM2mClient(tenantId, clientId, SecurityUtils.sha256Hex(secret), String.join(",", scopes));
            auditService.log(ctx, "M2M_CLIENT_CREATED", p, "M2M_CLIENT", id.toString(), Map.of("name", name, "clientId", clientId));
            ctx.status(201).json(Map.of("id", id.toString(), "client_id", clientId, "client_secret", secret, "scopes", scopes));
        });

        app.get("/api/v1/tenants/{tenantId}/policies", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            ctx.json(Map.of("items", store.listPolicies(tenantId)));
        });

        app.post("/api/v1/tenants/{tenantId}/policies", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String resource = body.path("resource").asText();
            String action = body.path("action").asText();
            String effect = body.path("effect").asText("allow");
            int priority = body.path("priority").asInt(0);
            JsonNode condition = body.path("condition");
            UUID id = store.createPolicy(tenantId, resource, action, condition.isMissingNode() ? "{}" : condition.toString(), effect, priority);
            ctx.status(201).json(Map.of("id", id.toString()));
        });

        app.post("/api/v1/tenants/{tenantId}/policies/evaluate", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            JsonNode body = objectMapper.readTree(ctx.body());
            String resource = body.path("resource").asText();
            String action = body.path("action").asText();
            SqlStore.PolicyRecord policy = store.findMatchingPolicy(tenantId, resource, action).orElse(null);
            boolean allowed = policy == null || !"deny".equalsIgnoreCase(policy.effect());
            ctx.json(Map.of(
                    "allowed", allowed,
                    "matchedPolicyId", policy == null ? null : policy.id().toString(),
                    "effect", policy == null ? "allow(default)" : policy.effect()
            ));
        });

        app.get("/api/v1/tenants/{tenantId}/billing", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Admin or owner role required");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("plans", store.listPlans());
            out.put("subscription", store.findSubscription(tenantId).orElse(null));
            ctx.json(out);
        });

        app.post("/api/v1/tenants/{tenantId}/billing/subscription", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            enforceTenantMatch(p, tenantId);
            if (!p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "Owner role required");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String planId = body.path("plan_id").asText("free");
            String status = body.path("status").asText("active");
            Instant expiresAt = body.path("expires_at").isTextual() ? Instant.parse(body.path("expires_at").asText()) : null;
            UUID id = store.upsertSubscription(tenantId, planId, status, expiresAt);
            auditService.log(ctx, "SUBSCRIPTION_UPDATED", p, "SUBSCRIPTION", id.toString(), Map.of("planId", planId));
            ctx.status(201).json(Map.of("id", id.toString(), "planId", planId, "status", status));
        });

        app.post("/api/v1/users/{userId}/export", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId) && !p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "FORBIDDEN", "データエクスポートの権限がありません。");
            }
            UserRecord user = store.findUserById(userId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "ユーザーが見つかりません。"));
            List<SessionRecord> sessions = sessionStore.listUserSessions(userId);
            String payload = objectMapper.writeValueAsString(Map.of(
                    "user", Map.of("id", user.id().toString(), "email", user.email(), "displayName", user.displayName()),
                    "sessions", sessions.stream().map(s -> Map.of(
                            "id", s.id().toString(),
                            "tenantId", s.tenantId().toString(),
                            "createdAt", s.createdAt().toString(),
                            "lastActiveAt", s.lastActiveAt().toString(),
                            "expiresAt", s.expiresAt().toString(),
                            "invalidatedAt", s.invalidatedAt() == null ? null : s.invalidatedAt().toString()
                    )).toList()
            ));
            UUID eventId = store.enqueueOutboxEvent(p.tenantId(), "user.data_export_requested", payload);
            ctx.json(Map.of("ok", true, "eventId", eventId.toString(), "data", objectMapper.readTree(payload)));
        });

        app.delete("/api/v1/users/{userId}/data", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId) && !p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "FORBIDDEN", "データ削除の権限がありません。");
            }
            int updated = store.deleteUserData(userId);
            if (updated == 0) {
                throw new ApiException(404, "NOT_FOUND", "ユーザーが見つかりません。");
            }
            UUID eventId = store.enqueueOutboxEvent(p.tenantId(), "user.data_deletion_requested", "{\"user_id\":\"" + userId + "\"}");
            ctx.json(Map.of("ok", true, "eventId", eventId.toString()));
        });

        app.get("/api/v1/admin/tenants", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            ctx.json(Map.of("items", store.listTenants(offset, limit), "offset", offset, "limit", limit));
        });

        app.post("/api/v1/admin/tenants/{tenantId}/suspend", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            int updated = store.setTenantActive(tenantId, false);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "tenant not found");
            store.enqueueOutboxEvent(tenantId, "tenant.suspended", "{\"tenant_id\":\"" + tenantId + "\"}");
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/v1/admin/tenants/{tenantId}/activate", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            int updated = store.setTenantActive(tenantId, true);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "tenant not found");
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/v1/admin/users", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            ctx.json(Map.of("items", store.listUsers(offset, limit), "offset", offset, "limit", limit));
        });

        app.get("/api/v1/admin/audit", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            if (!p.roles().contains("ADMIN") && !p.roles().contains("OWNER")) {
                throw new ApiException(403, "ROLE_INSUFFICIENT", "監査ログ参照の権限がありません。");
            }
            int offset = parseOffset(ctx.queryParam("offset"));
            int limit = parseLimit(ctx.queryParam("limit"));
            UUID tenantFilter = p.serviceToken() ? null : p.tenantId();
            ctx.json(Map.of("items", store.listAuditLogs(tenantFilter, offset, limit), "offset", offset, "limit", limit));
        });

        app.post("/api/v1/admin/outbox/flush", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            outboxWorker.runOnce();
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/v1/billing/stripe/webhook", ctx -> {
            String configured = config.stripeWebhookSecret();
            if (!configured.isBlank()) {
                String signature = ctx.header("Stripe-Signature");
                if (!verifyStripeSignature(signature, ctx.body(), configured)) {
                    throw new ApiException(401, "FORBIDDEN", "invalid webhook secret");
                }
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String eventType = body.path("type").asText("");
            JsonNode object = body.path("data").path("object");
            String tenantRaw = object.path("metadata").path("tenant_id").asText("");
            if (tenantRaw.isBlank()) {
                ctx.json(Map.of("ok", true, "ignored", true));
                return;
            }
            UUID tenantId = UUID.fromString(tenantRaw);
            if (store.findTenantById(tenantId).isEmpty()) {
                ctx.json(Map.of("ok", true, "ignored", true, "reason", "tenant_not_found"));
                return;
            }
            String planId = object.path("metadata").path("plan_id").asText("free");
            String status = object.path("status").asText("active");
            Instant expiresAt = object.path("current_period_end").canConvertToLong()
                    ? Instant.ofEpochSecond(object.path("current_period_end").asLong())
                    : null;
            if (eventType.contains("subscription")) {
                store.upsertSubscription(tenantId, planId, status, expiresAt);
                store.enqueueOutboxEvent(tenantId, "billing.subscription.updated", ctx.body());
            }
            ctx.json(Map.of("ok", true));
        });

        app.before("/scim/v2/*", ctx -> {
            String auth = ctx.header("Authorization");
            if (auth == null || !auth.startsWith("Bearer volta-service:")) {
                ctx.header("WWW-Authenticate", "Bearer realm=\"volta-scim\"");
                ctx.status(401);
                ctx.skipRemainingHandlers();
                return;
            }
            String provided = auth.substring("Bearer volta-service:".length()).trim();
            if (config.serviceToken().isBlank() || !config.serviceToken().equals(provided)) {
                ctx.status(403);
                ctx.skipRemainingHandlers();
            }
        });

        app.get("/scim/v2/Users", ctx -> {
            UUID tenantId = UUID.fromString(ctx.queryParam("tenantId"));
            List<Map<String, Object>> resources = store.listScimUsers(tenantId).stream()
                    .map(u -> Map.of(
                            "id", u.id().toString(),
                            "userName", u.email(),
                            "active", u.active(),
                            "name", Map.of("formatted", u.displayName() == null ? "" : u.displayName())
                    )).toList();
            ctx.json(Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"), "Resources", resources));
        });

        app.post("/scim/v2/Users", ctx -> {
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID tenantId = UUID.fromString(body.path("tenantId").asText());
            String email = body.path("userName").asText();
            String displayName = body.path("name").path("formatted").asText(email);
            UUID userId = store.createScimUser(tenantId, email, displayName);
            ctx.status(201).json(Map.of("id", userId.toString(), "userName", email, "active", true));
        });

        app.get("/scim/v2/Users/{id}", ctx -> {
            UUID tenantId = UUID.fromString(ctx.queryParam("tenantId"));
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            SqlStore.BasicUserRecord u = store.findScimUser(tenantId, userId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "SCIM user not found"));
            ctx.json(Map.of("id", u.id().toString(), "userName", u.email(), "active", u.active()));
        });

        app.put("/scim/v2/Users/{id}", ctx -> {
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            String email = body.path("userName").asText();
            String displayName = body.path("name").path("formatted").asText(email);
            boolean active = body.path("active").asBoolean(true);
            int updated = store.updateScimUser(userId, email, displayName, active);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "SCIM user not found");
            ctx.json(Map.of("id", userId.toString(), "userName", email, "active", active));
        });

        app.patch("/scim/v2/Users/{id}", ctx -> {
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            String email = body.path("userName").asText("patched-" + userId + "@example.local");
            String displayName = body.path("displayName").asText("patched");
            boolean active = body.path("active").asBoolean(true);
            int updated = store.updateScimUser(userId, email, displayName, active);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "SCIM user not found");
            ctx.json(Map.of("id", userId.toString(), "active", active));
        });

        app.delete("/scim/v2/Users/{id}", ctx -> {
            UUID tenantId = UUID.fromString(ctx.queryParam("tenantId"));
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            int updated = store.deactivateScimMembership(tenantId, userId);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "SCIM user not found");
            ctx.status(204);
        });

        app.get("/scim/v2/Groups", ctx -> ctx.json(Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"), "Resources", List.of())));
        app.post("/scim/v2/Groups", ctx -> ctx.status(201).json(Map.of("id", SecurityUtils.newUuid().toString(), "displayName", "group")));

        // SIGHUP: reload IdP list without restart
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), sig -> {
                VoltaConfig reloaded = ConfigLoader.load(config.appConfigPath());
                oidcService.reload(reloaded);
                System.out.println("[volta] IdP registry reloaded via SIGHUP ("
                        + oidcService.enabledProviders().stream()
                              .map(IdpProvider::id).toList() + ")");
            });
        } catch (IllegalArgumentException ignored) {
            // SIGHUP not available on this OS (Windows)
        }

        outboxWorker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            outboxWorker.close();
            auditSink.close();
            sessionStore.close();
            dataSource.close();
        }));
        app.start(config.port());
    }

    private static boolean verifyStripeSignature(String header, String payload, String secret) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String[] parts = header.split(",");
        String timestamp = null;
        String v1 = null;
        for (String part : parts) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) timestamp = kv[1];
            if ("v1".equals(kv[0])) v1 = kv[1];
        }
        if (timestamp == null || v1 == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > 300) {
            return false;
        }
        String signedPayload = timestamp + "." + payload;
        String expected = SecurityUtils.hmacSha256Hex(secret, signedPayload);
        return SecurityUtils.constantTimeEquals(expected, v1);
    }

    private static void ensureOidcConfig(OidcService oidcService) {
        if (oidcService.enabledProviders().isEmpty()) {
            throw new IllegalStateException("No IdP configured. Set GOOGLE_CLIENT_ID, GITHUB_CLIENT_ID, or MICROSOFT_CLIENT_ID.");
        }
    }

    private static String requireQuery(Context ctx, String key) {
        String value = ctx.queryParam(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing query param: " + key);
        }
        return value;
    }

    private static AuthPrincipal requireAuth(Context ctx, AuthService authService) {
        return authService.authenticate(ctx)
                .orElseThrow(() -> new ApiException(401, "AUTHENTICATION_REQUIRED", "Authentication required"));
    }

    private static String completeOidcCallback(
            Context ctx,
            String code,
            String state,
            AppConfig config,
            SqlStore store,
            AuthService authService,
            OidcService oidcService,
            AuditService auditService,
            AppRegistry appRegistry
    ) {
        OidcIdentity identity = oidcService.exchangeAndValidate(code, state);
        UserRecord user = store.upsertUser(identity.email(), identity.displayName(), identity.googleSub());
        TenantRecord tenant = resolveTenant(store, user, identity.inviteCode());
        AuthPrincipal principal;
        String sessionReturnTo = identity.returnTo();
        if (identity.inviteCode() != null) {
            InvitationRecord invitation = store.findInvitationByCode(identity.inviteCode())
                    .orElseThrow(() -> new ApiException(404, "INVITATION_NOT_FOUND", "招待リンクが見つかりません。"));
            principal = new AuthPrincipal(
                    user.id(), user.email(), user.displayName(),
                    tenant.id(), tenant.name(), tenant.slug(),
                    List.of("INVITED"), false
            );
            sessionReturnTo = "invite:" + invitation.code();
        } else {
            MembershipRecord membership = store.findMembership(user.id(), tenant.id())
                    .orElseThrow(() -> new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant membership not found"));
            principal = new AuthPrincipal(
                    user.id(), user.email(), user.displayName(),
                    tenant.id(), tenant.name(), tenant.slug(),
                    List.of(membership.role()), false
            );
        }
        UUID sessionId = authService.issueSession(principal, sessionReturnTo, clientIp(ctx), ctx.userAgent());
        setSessionCookie(ctx, sessionId, config.sessionTtlSeconds());
        auditService.log(ctx, "LOGIN_SUCCESS", principal, "SESSION", sessionId.toString(), Map.of(
                "via", identity.provider().toLowerCase() + "_oidc",
                "invite", identity.inviteCode() != null
        ));

        if (identity.inviteCode() != null) {
            return "/invite/" + identity.inviteCode();
        }
        List<TenantRecord> tenants = store.findTenantsByUser(user.id());
        if (tenants.size() > 1) {
            return "/select-tenant";
        }
        if (identity.returnTo() != null && HttpSupport.isAllowedReturnTo(identity.returnTo(), config.allowedRedirectDomains())) {
            return identity.returnTo();
        }
        return appRegistry.defaultAppUrl().orElse("/select-tenant");
    }

    private static TenantRecord resolveTenant(SqlStore store, UserRecord user, String inviteCode) {
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

    private static void setSessionCookie(Context ctx, UUID sessionId, int sessionTtlSeconds) {
        String cookie = AuthService.SESSION_COOKIE + "=" + sessionId
                + "; Path=/; Max-Age=" + sessionTtlSeconds
                + "; HttpOnly; SameSite=Lax";
        if (ctx.req().isSecure()) {
            cookie += "; Secure";
        }
        ctx.header("Set-Cookie", cookie);
    }

    private static String clientIp(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ctx.ip();
    }

    private static boolean isLocalRequest(Context ctx) {
        String ip = clientIp(ctx);
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    private static int parseOffset(String offsetRaw) {
        if (offsetRaw == null) {
            return 0;
        }
        return Math.max(0, Integer.parseInt(offsetRaw));
    }

    private static int parseLimit(String limitRaw) {
        if (limitRaw == null) {
            return 20;
        }
        int value = Integer.parseInt(limitRaw);
        return Math.min(100, Math.max(1, value));
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of("MEMBER");
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asText()));
        return out.isEmpty() ? List.of("MEMBER") : out;
    }

    private static List<String> csvToList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static void enforceTenantMatch(AuthPrincipal principal, UUID tenantId) {
        if (principal.serviceToken()) {
            return;
        }
        if (!principal.tenantId().equals(tenantId)) {
            throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied");
        }
    }

    private static void requireOwner(AuthPrincipal principal) {
        if (principal.serviceToken()) {
            throw new ApiException(403, "FORBIDDEN", "Service token cannot access admin keys");
        }
        if (!principal.roles().contains("OWNER")) {
            throw new ApiException(403, "ROLE_INSUFFICIENT", "Owner role required");
        }
    }

    private static void renderErrorPage(Context ctx, int status, String code, String message) {
        String userMessage = humanMessage(code, message);
        String actionHref = "/login";
        String actionLabel = "ログイン";
        if ("FORBIDDEN".equals(code) || "ROLE_INSUFFICIENT".equals(code)) {
            actionHref = "/";
            actionLabel = "前の画面へ戻る";
        } else if ("TENANT_ACCESS_DENIED".equals(code) || "TENANT_SUSPENDED".equals(code)) {
            actionHref = "/select-tenant";
            actionLabel = "ワークスペースを切り替える";
        } else if ("INVITATION_EXPIRED".equals(code) || "INVITATION_EXHAUSTED".equals(code)) {
            actionHref = "/login";
            actionLabel = "ログイン";
        } else if ("RATE_LIMITED".equals(code)) {
            actionHref = "/";
            actionLabel = "しばらく待って再試行";
        }
        ctx.status(status).render("error/error.jte", model(
                "title", "エラー",
                "errorCode", code,
                "message", userMessage,
                "actions", List.of(Map.of("label", actionLabel, "url", actionHref, "style", "primary")),
                "showSupport", List.of("SESSION_REVOKED", "TENANT_SUSPENDED", "INTERNAL_ERROR").contains(code),
                "supportContact", System.getenv().getOrDefault("SUPPORT_CONTACT", "管理者にお問い合わせください")
        ));
    }

    private static String humanMessage(String code, String fallback) {
        return switch (code) {
            case "AUTHENTICATION_REQUIRED" -> "ログインが必要です。";
            case "SESSION_EXPIRED" -> "セッションの有効期限が切れました。";
            case "SESSION_REVOKED" -> "セッションが無効化されました。";
            case "FORBIDDEN" -> "この操作を実行する権限がありません。";
            case "TENANT_ACCESS_DENIED" -> "ワークスペースへのアクセス権がありません。";
            case "TENANT_SUSPENDED" -> "このワークスペースは一時停止中です。";
            case "ROLE_INSUFFICIENT" -> "この操作に必要なロールが不足しています。";
            case "INVITATION_EXPIRED" -> "招待リンクの有効期限が切れました。";
            case "INVITATION_EXHAUSTED" -> "この招待リンクは使用済みです。";
            case "RATE_LIMITED" -> "アクセスが集中しています。しばらく待って再試行してください。";
            case "INTERNAL_ERROR" -> "システムエラーが発生しました。時間をおいて再試行してください。";
            default -> fallback;
        };
    }

    private static void setNoStore(Context ctx) {
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
        ctx.header("Pragma", "no-cache");
    }

    private static boolean isJsonOrXhr(Context ctx) {
        String accept = ctx.header("Accept");
        String contentType = ctx.header("Content-Type");
        String xrw = ctx.header("X-Requested-With");
        return (accept != null && accept.toLowerCase().contains("application/json"))
                || (contentType != null && contentType.toLowerCase().contains("application/json"))
                || "XMLHttpRequest".equalsIgnoreCase(xrw);
    }

    private static String currentCsrfToken(Context ctx, SessionStore sessionStore) {
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

    private static boolean isSuspendedTenantSession(Context ctx, SessionStore sessionStore, SqlStore store) {
        String sessionRaw = ctx.cookie(AuthService.SESSION_COOKIE);
        if (sessionRaw == null || sessionRaw.isBlank()) {
            return false;
        }
        try {
            SessionRecord session = sessionStore.findSession(UUID.fromString(sessionRaw)).orElse(null);
            if (session == null) {
                return false;
            }
            return store.findTenantDetailById(session.tenantId())
                    .map(t -> !t.active())
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void setFlashCookie(Context ctx, String message) {
        String encoded = java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
        ctx.header("Set-Cookie", "__volta_flash=" + encoded + "; Path=/; Max-Age=20; SameSite=Lax");
    }

    private static String popFlashCookie(Context ctx) {
        String value = ctx.cookie("__volta_flash");
        if (value == null || value.isBlank()) {
            return "";
        }
        ctx.header("Set-Cookie", "__volta_flash=; Path=/; Max-Age=0; SameSite=Lax");
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String inferDevice(String ua) {
        if (ua == null) {
            return "🖥";
        }
        String lower = ua.toLowerCase();
        return (lower.contains("mobile") || lower.contains("android") || lower.contains("iphone")) ? "📱" : "🖥";
    }

    private static String inferBrowser(String ua) {
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

    private static String inferOs(String ua) {
        if (ua == null) {
            return "Unknown";
        }
        String lower = ua.toLowerCase();
        if (lower.contains("windows")) return "Windows";
        if (lower.contains("mac os")) return "macOS";
        if (lower.contains("android")) return "Android";
        if (lower.contains("iphone") || lower.contains("ipad")) return "iOS";
        if (lower.contains("linux")) return "Linux";
        return "OS";
    }
}
