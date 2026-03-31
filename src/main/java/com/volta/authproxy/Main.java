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
        JwtService jwtService = new JwtService(config, store);
        AuthService authService = new AuthService(config, store, jwtService);
        OidcService oidcService = new OidcService(config, store);
        AppRegistry appRegistry = new AppRegistry(config);
        AuditService auditService = new AuditService(store);
        RateLimiter rateLimiter = new RateLimiter(200);
        ObjectMapper objectMapper = new ObjectMapper();
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
            if (isJsonOrXhr(ctx)) {
                return;
            }
            String sessionCookie = ctx.cookie(AuthService.SESSION_COOKIE);
            if (sessionCookie == null) {
                throw new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。");
            }
            SessionRecord session = store.findSession(UUID.fromString(sessionCookie))
                    .orElseThrow(() -> new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。"));
            String csrf = ctx.formParam("_csrf");
            if (csrf == null || session.csrfToken() == null || !session.csrfToken().equals(csrf)) {
                throw new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。");
            }
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
            ensureOidcConfig(config);
            String returnToRaw = ctx.queryParam("return_to");
            String inviteCode = ctx.queryParam("invite");
            String returnTo = HttpSupport.isAllowedReturnTo(returnToRaw, config.allowedRedirectDomains()) ? returnToRaw : null;
            if ("1".equals(ctx.queryParam("start"))) {
                ctx.redirect(oidcService.createAuthorizationUrl(returnTo, inviteCode));
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
            String startUrl = "/login?start=1"
                    + (returnTo != null ? "&return_to=" + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8) : "")
                    + (inviteCode != null ? "&invite=" + java.net.URLEncoder.encode(inviteCode, java.nio.charset.StandardCharsets.UTF_8) : "");
            ctx.render("auth/login.jte", model(
                    "title", "ログイン",
                    "inviteContext", inviteContext,
                    "startUrl", startUrl
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
                    store.revokeSession(UUID.fromString(cookie));
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
                    "csrfToken", currentCsrfToken(ctx, store)
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
                    store.revokeSession(UUID.fromString(oldSessionRaw));
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
                "csrfToken", currentCsrfToken(ctx, store),
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
            List<SessionRecord> sessions = store.listUserSessions(principal.userId());
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
                    "csrfToken", currentCsrfToken(ctx, store),
                    "flashMessage", flashMessage
            ));
        });

        app.delete("/auth/sessions/{id}", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            UUID sessionId = UUID.fromString(ctx.pathParam("id"));
            SessionRecord session = store.findSession(sessionId).orElse(null);
            if (session == null || !session.userId().equals(principal.userId())) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            store.revokeSession(sessionId);
            auditService.log(ctx, "SESSION_REVOKED", principal, "SESSION", sessionId.toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        app.post("/auth/sessions/revoke-all", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            store.revokeAllSessions(principal.userId());
            authService.clearSessionCookie(ctx);
            auditService.log(ctx, "SESSIONS_REVOKED_ALL", principal, "USER", principal.userId().toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/me/sessions", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            List<Map<String, String>> sessions = store.listUserSessions(principal.userId()).stream()
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
            SessionRecord target = store.findSession(sessionId)
                    .orElseThrow(() -> new ApiException(404, "SESSION_NOT_FOUND", "セッションが見つかりません。"));
            if (!target.userId().equals(principal.userId())) {
                throw new ApiException(403, "FORBIDDEN", "他ユーザーのセッションは操作できません。");
            }
            store.revokeSession(sessionId);
            auditService.log(ctx, "SESSION_REVOKED", principal, "SESSION", sessionId.toString(), Map.of("via", "api-me"));
            ctx.json(Map.of("ok", true));
        });

        app.delete("/api/me/sessions", ctx -> {
            AuthPrincipal principal = requireAuth(ctx, authService);
            store.revokeAllSessions(principal.userId());
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
                    "csrfToken", currentCsrfToken(ctx, store)
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
                    "csrfToken", currentCsrfToken(ctx, store)
            ));
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

        app.get("/auth/verify", ctx -> {
            setNoStore(ctx);
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
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
            ctx.status(201).json(Map.of(
                    "id", invitationId.toString(),
                    "code", code,
                    "link", config.baseUrl() + "/invite/" + code,
                    "expiresAt", expiresAt.toString()
            ));
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
            store.revokeSessionsForUserTenant(target.userId(), tenantId);
            auditService.log(ctx, "MEMBER_REMOVED", p, "MEMBER", memberId.toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(dataSource::close));
        app.start(config.port());
    }

    private static void ensureOidcConfig(AppConfig config) {
        if (config.googleClientId().isBlank() || config.googleClientSecret().isBlank()) {
            throw new IllegalArgumentException("GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are required");
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
        OidcService.OidcIdentity identity = oidcService.exchangeAndValidate(code, state);
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
                "via", "google_oidc",
                "invite", identity.inviteCode() != null
        ));

        if (identity.inviteCode() != null) {
            return "/invite/" + identity.inviteCode();
        }
        List<TenantRecord> tenants = store.findTenantsByUser(user.id());
        if (tenants.size() > 1) {
            return "/select-tenant";
        }
        if (identity.returnTo() != null) {
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
        String xrw = ctx.header("X-Requested-With");
        return (accept != null && accept.toLowerCase().contains("application/json"))
                || "XMLHttpRequest".equalsIgnoreCase(xrw);
    }

    private static String currentCsrfToken(Context ctx, SqlStore store) {
        String sessionRaw = ctx.cookie(AuthService.SESSION_COOKIE);
        if (sessionRaw == null) {
            return "";
        }
        try {
            return store.findSession(UUID.fromString(sessionRaw))
                    .map(SessionRecord::csrfToken)
                    .orElse("");
        } catch (IllegalArgumentException e) {
            return "";
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
