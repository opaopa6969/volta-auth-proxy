package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class ApiRouter {
    private final SqlStore store;
    private final AuthService authService;
    private final SessionStore sessionStore;
    private final PolicyEngine policy;
    private final AuditService auditService;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final AppConfig config;
    private final VoltaConfig voltaConfig;
    private final GdprService gdprService;
    private final DeviceTrustService deviceTrustService;
    private final NotificationService notificationService;
    private final KeyCipher secretCipher;
    private final RateLimiter rateLimiter;
    private final OutboxWorker outboxWorker;

    public ApiRouter(SqlStore store, AuthService authService, SessionStore sessionStore,
                     PolicyEngine policy, AuditService auditService, JwtService jwtService,
                     ObjectMapper objectMapper, AppConfig config, VoltaConfig voltaConfig,
                     GdprService gdprService,
                     DeviceTrustService deviceTrustService, NotificationService notificationService,
                     KeyCipher secretCipher, RateLimiter rateLimiter, OutboxWorker outboxWorker) {
        this.store = store;
        this.authService = authService;
        this.sessionStore = sessionStore;
        this.policy = policy;
        this.auditService = auditService;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.config = config;
        this.voltaConfig = voltaConfig != null ? voltaConfig : VoltaConfig.empty();
        this.gdprService = gdprService;
        this.deviceTrustService = deviceTrustService;
        this.notificationService = notificationService;
        this.secretCipher = secretCipher;
        this.rateLimiter = rateLimiter;
        this.outboxWorker = outboxWorker;
    }

    public void register(Javalin app) {
        // /api/v1/* before-handler (auth check)
        app.before("/api/v1/*", ctx -> {
            if ("/api/v1/billing/stripe/webhook".equals(ctx.path())) {
                return;
            }
            String authHeader = ctx.header("Authorization");
            boolean hasBearerToken = authHeader != null && authHeader.startsWith("Bearer ");
            String origin = ctx.header("Origin");
            boolean hasValidOrigin = origin == null || isAllowedOrigin(origin);
            if (!hasBearerToken && !hasValidOrigin) {
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

            // Block M2M/service tokens from admin-only endpoints unless they have admin scope
            AuthPrincipal p = principal.get();
            if (p.serviceToken() && ctx.path().startsWith("/api/v1/admin/")) {
                boolean hasAdminScope = p.roles().stream()
                        .anyMatch(r -> r.equalsIgnoreCase("ADMIN") || r.equalsIgnoreCase("OWNER")
                                || r.startsWith("admin:"));
                if (!hasAdminScope) {
                    HttpSupport.jsonError(ctx, 403, "SCOPE_INSUFFICIENT",
                            "M2M client does not have admin scope for this endpoint");
                    ctx.skipRemainingHandlers();
                    return;
                }
            }
        });

        // --- Settings pages ---
        app.get("/settings/security", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            Optional<SqlStore.UserMfaRecord> mfa = store.findUserMfa(principal.userId(), "totp");
            boolean mfaEnabled = mfa.isPresent() && mfa.get().active();
            int recoveryRemaining = store.countUnusedRecoveryCodes(principal.userId());
            String flashMessage = popFlashCookie(ctx);
            ctx.render("settings/security.jte", io.javalin.rendering.template.TemplateUtil.model(
                    "title", "Security Settings",
                    "userId", principal.userId().toString(),
                    "mfaEnabled", mfaEnabled,
                    "recoveryRemaining", recoveryRemaining,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore),
                    "flashMessage", flashMessage
            ));
        });

        // AUTH-004-v2: user-facing known-devices management page. The REST
        // API is already live at /api/v1/users/me/known-devices (8c8fe3d);
        // this page just renders + wires delete-buttons.
        app.get("/settings/devices", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                    throw new ApiException(401, "AUTHENTICATION_REQUIRED", "Authentication required");
                }
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            List<Map<String, Object>> devices = store.listKnownDevices(principal.userId());
            String flashMessage = popFlashCookie(ctx);
            ctx.render("settings/devices.jte", io.javalin.rendering.template.TemplateUtil.model(
                    "title", "Known devices",
                    "userEmail", principal.email() == null ? "" : principal.email(),
                    "devices", devices,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore),
                    "flashMessage", flashMessage
            ));
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
                row.put("device", AuthRouter.inferDevice(s.userAgent()));
                row.put("browser", AuthRouter.inferBrowser(s.userAgent()));
                row.put("os", AuthRouter.inferOs(s.userAgent()));
                row.put("lastActive", s.lastActiveAt().toString());
                row.put("expiresAt", s.expiresAt().toString());
                row.put("status", s.invalidatedAt() == null ? "ACTIVE" : "REVOKED");
                row.put("isCurrent", String.valueOf(s.id().toString().equals(currentSessionId)));
                sessionView.add(row);
            }
            String flashMessage = popFlashCookie(ctx);
            ctx.render("auth/sessions.jte", io.javalin.rendering.template.TemplateUtil.model(
                    "title", "Sessions",
                    "sessionCount", sessionStore.countActiveSessions(principal.userId()),
                    "sessions", sessionView,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore),
                    "flashMessage", flashMessage
            ));
        });

        app.delete("/auth/sessions/{id}", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
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
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            sessionStore.revokeAllSessions(principal.userId());
            authService.clearSessionCookie(ctx);
            auditService.log(ctx, "SESSIONS_REVOKED_ALL", principal, "USER", principal.userId().toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/me/sessions", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            List<Map<String, String>> sessions = sessionStore.listUserSessions(principal.userId()).stream()
                    .filter(s -> s.isValidAt(Instant.now()))
                    .map(s -> Map.of(
                            "id", s.id().toString(),
                            "tenantId", s.tenantId().toString(),
                            "ip", s.ipAddress() == null ? "-" : s.ipAddress(),
                            "lastActiveAt", s.lastActiveAt().toString(),
                            "device", AuthRouter.inferDevice(s.userAgent()),
                            "browser", AuthRouter.inferBrowser(s.userAgent()),
                            "os", AuthRouter.inferOs(s.userAgent())
                    ))
                    .toList();
            ctx.json(Map.of("items", sessions));
        });

        app.delete("/api/me/sessions/{id}", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
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
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            sessionStore.revokeAllSessions(principal.userId());
            authService.clearSessionCookie(ctx);
            auditService.log(ctx, "SESSIONS_REVOKED_ALL", principal, "USER", principal.userId().toString(), Map.of("via", "api-me"));
            ctx.json(Map.of("ok", true));
        });

        // --- Admin API keys ---
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

        // --- User APIs ---
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

        // --- GDPR APIs ---
        app.post("/api/v1/users/me/data-export", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            String json = gdprService.exportUserData(p.userId());
            ctx.contentType("application/json").header(
                    "Content-Disposition", "attachment; filename=\"volta-export.json\"").result(json);
        });

        app.delete("/api/v1/users/me", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            java.time.Instant deleteAt = gdprService.requestDeletion(p.userId());
            auditService.log(ctx, "ACCOUNT_DELETION_REQUESTED", p, "USER", p.userId().toString(), Map.of());
            ctx.json(Map.of("status", "scheduled", "delete_at", deleteAt.toString()));
        });

        // --- Device Trust APIs ---
        app.get("/api/v1/users/me/devices", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            var devices = deviceTrustService.listDevices(p.userId());
            ctx.json(devices.stream().map(d -> Map.of(
                    "id", d.id().toString(),
                    "device_name", d.deviceName(),
                    "last_seen_at", d.lastSeenAt() != null ? d.lastSeenAt().toString() : "",
                    "ip_address", maskIp(d.ipAddress())
            )).toList());
        });

        app.delete("/api/v1/users/me/devices/{deviceId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID deviceId = UUID.fromString(ctx.pathParam("deviceId"));
            deviceTrustService.removeDevice(p.userId(), deviceId);
            ctx.json(Map.of("ok", true));
        });

        app.delete("/api/v1/users/me/devices", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            deviceTrustService.removeAllDevices(p.userId());
            ctx.json(Map.of("ok", true));
        });

        // AUTH-004-v2: implicit login-fingerprint history (known_devices).
        // Separate from trusted_devices above — this is the table that
        // triggers "new device" notifications.
        app.get("/api/v1/users/me/known-devices", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            ctx.json(Map.of("items", store.listKnownDevices(p.userId())));
        });

        app.delete("/api/v1/users/me/known-devices/{fingerprint}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            String fingerprint = ctx.pathParam("fingerprint");
            int removed = store.deleteKnownDevice(p.userId(), fingerprint);
            if (removed == 0) {
                throw new ApiException(404, "NOT_FOUND", "device not found");
            }
            auditService.log(ctx, "KNOWN_DEVICE_REMOVED", p, "DEVICE", fingerprint, Map.of());
            ctx.json(Map.of("ok", true, "removed", removed));
        });

        app.delete("/api/v1/users/me/known-devices", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            int removed = store.deleteAllKnownDevices(p.userId());
            auditService.log(ctx, "KNOWN_DEVICES_RESET", p, "USER", p.userId().toString(), Map.of("count", removed));
            ctx.json(Map.of("ok", true, "removed", removed));
        });

        app.get("/api/v1/users/{id}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            if (!p.serviceToken() && !p.userId().equals(userId)) {
                policy.enforceMinRole(p, "ADMIN");
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

        // --- Tenant APIs ---
        app.get("/api/v1/tenants/{tenantId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            SqlStore.TenantDetailRecord tenant = store.findTenantDetailById(tenantId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "テナントが見つかりません。"));
            ctx.json(tenant);
        });

        app.patch("/api/v1/tenants/{tenantId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "OWNER");
            JsonNode body = objectMapper.readTree(ctx.body());
            String name = body.path("name").isMissingNode() ? null : body.path("name").asText(null);
            Boolean autoJoin = body.path("auto_join").isMissingNode() ? null : body.path("auto_join").asBoolean();
            String logoUrl = body.path("logo_url").isMissingNode() ? null : body.path("logo_url").asText(null);
            String primaryColor = body.path("primary_color").isMissingNode() ? null : body.path("primary_color").asText(null);
            String theme = body.path("theme").isMissingNode() ? null : body.path("theme").asText(null);
            // MFA policy
            if (!body.path("mfa_required").isMissingNode()) {
                boolean mfaRequired = body.path("mfa_required").asBoolean();
                int graceDays = body.path("mfa_grace_days").asInt(7);
                store.updateTenantMfaPolicy(tenantId, mfaRequired, mfaRequired ? graceDays : 0);
            }
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

        // --- MFA APIs ---
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

        // MFA status
        app.get("/api/v1/users/me/mfa", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            Optional<SqlStore.UserMfaRecord> mfa = store.findUserMfa(p.userId(), "totp");
            int recoveryRemaining = store.countUnusedRecoveryCodes(p.userId());
            if (mfa.isPresent() && mfa.get().active()) {
                ctx.json(Map.of(
                        "totp", Map.of("enabled", true, "setupAt", mfa.get().createdAt().toString()),
                        "recovery_codes_remaining", recoveryRemaining
                ));
            } else {
                ctx.json(Map.of(
                        "totp", Map.of("enabled", false),
                        "recovery_codes_remaining", 0
                ));
            }
        });

        // MFA disable (self only, requires current TOTP code)
        app.delete("/api/v1/users/{userId}/mfa/totp", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self update is allowed");
            }
            SqlStore.UserMfaRecord mfa = store.findUserMfa(userId, "totp")
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "MFA is not configured"));
            if (!mfa.active()) {
                throw new ApiException(400, "BAD_REQUEST", "MFA is not active");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            int code = body.path("code").asInt(-1);
            com.warrenstrange.googleauth.GoogleAuthenticator ga = new com.warrenstrange.googleauth.GoogleAuthenticator();
            boolean ok = ga.authorize(secretCipher.decrypt(mfa.secret()), code);
            if (!ok) {
                throw new ApiException(400, "MFA_INVALID_CODE", "TOTP code is invalid. Cannot disable MFA.");
            }
            store.deactivateUserMfa(userId, "totp");
            store.deleteRecoveryCodes(userId);
            auditService.log(ctx, "MFA_DISABLED", p, "USER", userId.toString(), Map.of());
            ctx.json(Map.of("ok", true, "mfa_enabled", false));
        });

        // Admin MFA reset
        app.delete("/api/v1/tenants/{tenantId}/members/{userId}/mfa", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            store.deactivateUserMfa(userId, "totp");
            store.deleteRecoveryCodes(userId);
            auditService.log(ctx, "ADMIN_MFA_RESET", p, "USER", userId.toString(),
                    Map.of("resetBy", p.userId().toString()));
            ctx.json(Map.of("ok", true));
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

        // --- Tenant Members ---
        app.get("/api/v1/tenants/{tenantId}/members", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            Pagination.PageRequest pageReq = Pagination.PageRequest.from(ctx);
            ctx.json(store.findMembersPaginated(tenantId, pageReq).toJson());
        });

        app.get("/api/v1/tenants/{tenantId}/members/{userId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            policy.enforceTenantMatch(p, tenantId);
            MembershipRecord member = store.findMembershipByUser(tenantId, userId)
                    .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "メンバーが見つかりません。"));
            ctx.json(member);
        });

        app.patch("/api/v1/tenants/{tenantId}/members/{memberId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
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
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
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
                // i18n: use the invitee's locale when they already have an
                // account; otherwise fall back to the inviter's locale so the
                // email matches who triggered it. `null` → default locale.
                String locale = store.getUserLocaleByEmail(email)
                        .or(() -> store.getUserLocale(p.userId()))
                        .orElse(null);
                Map<String, Object> invData = new java.util.LinkedHashMap<>();
                invData.put("to", email);
                invData.put("inviteLink", config.baseUrl() + "/invite/" + code);
                invData.put("tenantName", tenantName);
                invData.put("role", role);
                invData.put("inviterName", inviterName == null ? "" : inviterName);
                if (locale != null && !locale.isBlank()) invData.put("locale", locale);
                String emailPayload = objectMapper.writeValueAsString(invData);
                store.enqueueOutboxEvent(tenantId, "notification.invitation", emailPayload);
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
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "OWNER");
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
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            Pagination.PageRequest pageReq = Pagination.PageRequest.from(ctx);
            String statusFilter = ctx.queryParam("status");
            ctx.json(store.findInvitationsPaginated(tenantId, pageReq, statusFilter).toJson());
        });

        app.delete("/api/v1/tenants/{tenantId}/invitations/{invitationId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
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
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
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
            // AUTH-014 Phase 1: tenancy.creation_policy from volta-config.yaml
            // supersedes the legacy ALLOW_SELF_SERVICE_TENANT env var.
            // If the YAML has no `tenancy:` section we read the AppConfig
            // fallback (true → AUTO, false → DISABLED) to stay compatible.
            var policyKind = voltaConfig.tenancy().creationPolicy();
            if (policyKind == VoltaConfig.TenancyConfig.CreationPolicy.DISABLED
                    && voltaConfig.tenancy() == VoltaConfig.TenancyConfig.defaults()
                    && config.allowSelfServiceTenant()) {
                // No YAML tenancy section AND env var says allow → treat as AUTO.
                policyKind = VoltaConfig.TenancyConfig.CreationPolicy.AUTO;
            }
            switch (policyKind) {
                case DISABLED -> throw new ApiException(403, "FORBIDDEN", "self-service tenant 作成は無効です。");
                case AUTO -> { /* allow any authenticated user */ }
                case ADMIN_ONLY -> {
                    // Platform super-admin role is not yet modelled; require
                    // OWNER of at least one existing tenant as a conservative
                    // stand-in until AUTH-014 Phase 4 introduces a dedicated role.
                    if (!store.hasOwnerRoleAnyTenant(p.userId())) {
                        throw new ApiException(403, "FORBIDDEN", "admin_only policy: platform admin required.");
                    }
                }
                case INVITE_ONLY -> {
                    // Existing org ADMIN or OWNER may create another org.
                    if (!store.hasAdminRoleAnyTenant(p.userId())) {
                        throw new ApiException(403, "FORBIDDEN", "invite_only policy: existing-tenant admin required.");
                    }
                }
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

        // --- Webhook APIs ---
        app.get("/api/v1/tenants/{tenantId}/webhooks", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            ctx.json(Map.of("items", store.listWebhooks(tenantId)));
        });

        app.post("/api/v1/tenants/{tenantId}/webhooks", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            JsonNode body = objectMapper.readTree(ctx.body());
            String endpoint = body.path("endpoint_url").asText();
            if (endpoint.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "endpoint_url が必要です。");
            }
            validateWebhookUrl(endpoint);
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

        app.get("/api/v1/tenants/{tenantId}/webhooks/{webhookId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID webhookId = UUID.fromString(ctx.pathParam("webhookId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            var webhook = store.findWebhook(tenantId, webhookId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Webhook が見つかりません。"));
            ctx.json(webhook);
        });

        app.get("/api/v1/tenants/{tenantId}/webhooks/{webhookId}/deliveries", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID webhookId = UUID.fromString(ctx.pathParam("webhookId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            int limit = Math.min(ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50), 200);
            ctx.json(Map.of("items", store.listWebhookDeliveries(webhookId, limit)));
        });

        app.patch("/api/v1/tenants/{tenantId}/webhooks/{webhookId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID webhookId = UUID.fromString(ctx.pathParam("webhookId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            var existing = store.findWebhook(tenantId, webhookId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Webhook が見つかりません。"));
            JsonNode body = objectMapper.readTree(ctx.body());
            String endpoint = body.has("endpoint_url") ? body.path("endpoint_url").asText() : existing.endpointUrl();
            if (body.has("endpoint_url")) {
                validateWebhookUrl(endpoint);
            }
            String events = body.has("events")
                    ? (body.path("events").isArray()
                        ? String.join(",", toStringList(body.path("events")))
                        : body.path("events").asText())
                    : existing.events();
            boolean active = body.has("is_active") ? body.path("is_active").asBoolean() : existing.active();
            int updated = store.updateWebhook(tenantId, webhookId, endpoint, events, active);
            if (updated == 0) {
                throw new ApiException(404, "NOT_FOUND", "Webhook が見つかりません。");
            }
            auditService.log(ctx, "WEBHOOK_UPDATED", p, "WEBHOOK", webhookId.toString(), Map.of("endpoint", endpoint));
            ctx.json(Map.of("ok", true));
        });

        app.delete("/api/v1/tenants/{tenantId}/webhooks/{webhookId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            UUID webhookId = UUID.fromString(ctx.pathParam("webhookId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            int updated = store.deactivateWebhook(tenantId, webhookId);
            if (updated == 0) {
                throw new ApiException(404, "NOT_FOUND", "Webhook が見つかりません。");
            }
            ctx.json(Map.of("ok", true));
        });

        // --- IdP Config APIs ---
        app.get("/api/v1/tenants/{tenantId}/idp-configs", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            ctx.json(Map.of("items", store.listIdpConfigs(tenantId)));
        });

        app.post("/api/v1/tenants/{tenantId}/idp-configs", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            JsonNode body = objectMapper.readTree(ctx.body());
            String providerType = body.path("provider_type").asText("OIDC").toUpperCase(Locale.ROOT);
            String metadataUrl = body.path("metadata_url").asText(null);
            String issuer = body.path("issuer").asText(null);
            String clientId = body.path("client_id").asText(null);
            String x509Cert = body.path("x509_cert").asText(null);
            UUID id = store.upsertIdpConfig(tenantId, providerType, metadataUrl, issuer, clientId, x509Cert);
            ctx.status(201).json(Map.of("id", id.toString(), "providerType", providerType));
        });

        // --- M2M Client APIs ---
        app.get("/api/v1/tenants/{tenantId}/m2m-clients", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
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
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            JsonNode body = objectMapper.readTree(ctx.body());
            String name = body.path("name").asText("m2m");
            String clientId = body.path("client_id").asText("m2m_" + tenantId.toString().substring(0, 8) + "_" + SecurityUtils.randomUrlSafe(6));
            String secret = SecurityUtils.randomUrlSafe(24);
            List<String> scopes = body.path("scopes").isArray() ? toStringList(body.path("scopes")) : List.of("service:read");
            UUID id = store.createM2mClient(tenantId, clientId, SecurityUtils.sha256Hex(secret), String.join(",", scopes));
            auditService.log(ctx, "M2M_CLIENT_CREATED", p, "M2M_CLIENT", id.toString(), Map.of("name", name, "clientId", clientId));
            ctx.status(201).json(Map.of("id", id.toString(), "client_id", clientId, "client_secret", secret, "scopes", scopes));
        });

        // --- Policy APIs ---
        app.get("/api/v1/tenants/{tenantId}/policies", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            ctx.json(Map.of("items", store.listPolicies(tenantId)));
        });

        app.post("/api/v1/tenants/{tenantId}/policies", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
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
            policy.enforceTenantMatch(p, tenantId);
            JsonNode body = objectMapper.readTree(ctx.body());
            String resource = body.path("resource").asText();
            String action = body.path("action").asText();
            SqlStore.PolicyRecord matched = store.findMatchingPolicy(tenantId, resource, action).orElse(null);
            boolean allowed = matched == null || !"deny".equalsIgnoreCase(matched.effect());
            ctx.json(Map.of(
                    "allowed", allowed,
                    "matchedPolicyId", matched == null ? null : matched.id().toString(),
                    "effect", matched == null ? "allow(default)" : matched.effect()
            ));
        });

        // --- Billing APIs ---
        app.get("/api/v1/tenants/{tenantId}/billing", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("plans", store.listPlans());
            out.put("subscription", store.findSubscription(tenantId).orElse(null));
            ctx.json(out);
        });

        // SAAS-008: usage metering
        // Record a billable event. Services self-report — e.g. Traefik sidecar
        // increments "api_call", ttyd launcher increments "session_hours".
        app.post("/api/v1/tenants/{tenantId}/billing/usage", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            // Any tenant member can record usage for their own tenant; the
            // header-injection path means the caller is already trusted. We
            // still gate on membership (enforceTenantMatch above).
            JsonNode body = objectMapper.readTree(ctx.body());
            String metric = body.path("metric").asText("");
            if (metric.isBlank() || metric.length() > 64 || !metric.matches("^[a-z0-9_.-]+$")) {
                throw new ApiException(400, "BAD_REQUEST", "metric must be 1-64 chars of [a-z0-9_.-]");
            }
            long quantity = body.path("quantity").asLong(1L);
            if (quantity < 0 || quantity > 1_000_000_000L) {
                throw new ApiException(400, "BAD_REQUEST", "quantity out of range (0..1e9)");
            }
            JsonNode meta = body.path("meta");
            String metaJson = meta.isMissingNode() || meta.isNull() ? null : meta.toString();
            store.recordUsage(tenantId, metric, quantity, metaJson);
            ctx.status(202).json(Map.of("ok", true));
        });

        // SAAS-008: aggregated usage query. Returns { metric: totalQuantity }.
        // `from` / `to` are optional ISO-8601 instants (epoch / now by default).
        app.get("/api/v1/tenants/{tenantId}/billing/usage", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "ADMIN");
            Instant from = parseInstantOrNull(ctx.queryParam("from"));
            Instant to   = parseInstantOrNull(ctx.queryParam("to"));
            Map<String, Long> agg = store.aggregateUsage(tenantId, from, to);
            Map<String, Instant> last = store.lastUsageByMetric(tenantId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tenant_id", tenantId.toString());
            result.put("window", Map.of(
                    "from", from == null ? null : from.toString(),
                    "to",   to   == null ? null : to.toString()
            ));
            result.put("totals", agg);
            Map<String, String> lastStr = new LinkedHashMap<>();
            last.forEach((k, v) -> lastStr.put(k, v.toString()));
            result.put("last_recorded_at", lastStr);
            ctx.json(result);
        });

        // SAAS-008: Stripe Checkout — user starts a subscription / upgrade.
        // The returned URL is short-lived and owned by Stripe; the frontend
        // simply 302s to it. Source of truth for state remains the webhook.
        app.post("/api/v1/tenants/{tenantId}/billing/checkout-session", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "OWNER");
            StripeClient stripe = new StripeClient(config.stripeSecretKey());
            if (!stripe.isConfigured()) {
                throw new ApiException(503, "STRIPE_NOT_CONFIGURED",
                        "Stripe is not enabled on this server.");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String priceId = body.path("price_id").asText("");
            if (priceId.isBlank() || !priceId.startsWith("price_")) {
                throw new ApiException(400, "BAD_REQUEST", "price_id must start with price_");
            }
            String success = firstNonBlank(
                    body.path("success_url").asText(""),
                    config.stripeCheckoutSuccessUrl(),
                    config.baseUrl() + "/billing?status=success");
            String cancel = firstNonBlank(
                    body.path("cancel_url").asText(""),
                    config.stripeCheckoutCancelUrl(),
                    config.baseUrl() + "/billing?status=cancel");
            String url = stripe.createCheckoutSession(priceId, success, cancel,
                    tenantId.toString(), p.email());
            auditService.log(ctx, "CHECKOUT_SESSION_STARTED", p, "TENANT", tenantId.toString(),
                    Map.of("price_id", priceId));
            ctx.status(201).json(Map.of("url", url));
        });

        // SAAS-008: Stripe Customer Portal — user manages their existing
        // subscription (cancel, update card, view invoices).
        app.post("/api/v1/tenants/{tenantId}/billing/portal", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "OWNER");
            StripeClient stripe = new StripeClient(config.stripeSecretKey());
            if (!stripe.isConfigured()) {
                throw new ApiException(503, "STRIPE_NOT_CONFIGURED",
                        "Stripe is not enabled on this server.");
            }
            var sub = store.findSubscription(tenantId);
            if (sub.isEmpty() || sub.get().stripeSubId() == null || sub.get().stripeSubId().isBlank()) {
                throw new ApiException(409, "NO_STRIPE_SUBSCRIPTION",
                        "No Stripe subscription linked to this tenant. Complete checkout first.");
            }
            JsonNode body = objectMapper.readTree(ctx.body());
            String customerId = body.path("customer_id").asText("");
            if (customerId.isBlank() || !customerId.startsWith("cus_")) {
                throw new ApiException(400, "BAD_REQUEST", "customer_id must start with cus_");
            }
            String returnUrl = firstNonBlank(
                    body.path("return_url").asText(""),
                    config.stripePortalReturnUrl(),
                    config.baseUrl() + "/billing");
            String url = stripe.createPortalSession(customerId, returnUrl);
            auditService.log(ctx, "BILLING_PORTAL_OPENED", p, "TENANT", tenantId.toString(), Map.of());
            ctx.status(201).json(Map.of("url", url));
        });

        app.post("/api/v1/tenants/{tenantId}/billing/subscription", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID tenantId = UUID.fromString(ctx.pathParam("tenantId"));
            policy.enforceTenantMatch(p, tenantId);
            policy.enforceMinRole(p, "OWNER");
            JsonNode body = objectMapper.readTree(ctx.body());
            String planId = body.path("plan_id").asText("free");
            String status = body.path("status").asText("active");
            Instant expiresAt = body.path("expires_at").isTextual() ? Instant.parse(body.path("expires_at").asText()) : null;
            UUID id = store.upsertSubscription(tenantId, planId, status, expiresAt);
            auditService.log(ctx, "SUBSCRIPTION_UPDATED", p, "SUBSCRIPTION", id.toString(), Map.of("planId", planId));
            ctx.status(201).json(Map.of("id", id.toString(), "planId", planId, "status", status));
        });

        // --- User Data Export/Delete ---
        app.post("/api/v1/users/{userId}/export", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                policy.enforceMinRole(p, "ADMIN");
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

        // --- Super Admin APIs ---
        app.get("/api/v1/admin/tenants", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            int offset = HttpSupport.parseOffset(ctx.queryParam("offset"));
            int limit = HttpSupport.parseLimit(ctx.queryParam("limit"));
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
            Pagination.PageRequest pageReq = Pagination.PageRequest.from(ctx);
            ctx.json(store.findUsersPaginated(pageReq).toJson());
        });

        // AUTH-004-v2: admin "reset all known devices" for a user.
        // Use case: user lost a device, or got a suspicious login alert;
        // admin wipes the fingerprint history so the user will get fresh
        // new-device notifications on their next sign-ins from any device.
        app.delete("/api/v1/admin/users/{userId}/known-devices", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            policy.enforceMinRole(p, "ADMIN");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            int removed = store.deleteAllKnownDevices(userId);
            auditService.log(ctx, "KNOWN_DEVICES_ADMIN_RESET", p, "USER", userId.toString(),
                    Map.of("count", removed));
            ctx.json(Map.of("ok", true, "removed", removed));
        });

        app.get("/api/v1/admin/audit", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            policy.enforceMinRole(p, "ADMIN");
            Pagination.PageRequest pageReq = Pagination.PageRequest.from(ctx);
            UUID tenantFilter = p.serviceToken() ? null : p.tenantId();
            String fromDate = ctx.queryParam("from");
            String toDate = ctx.queryParam("to");
            String eventType = ctx.queryParam("event");
            ctx.json(store.findAuditLogsPaginated(pageReq, tenantFilter, fromDate, toDate, eventType).toJson());
        });

        app.get("/api/v1/admin/sessions", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            policy.enforceMinRole(p, "ADMIN");
            Pagination.PageRequest pageReq = Pagination.PageRequest.from(ctx);
            String userIdRaw = ctx.queryParam("user_id");
            UUID userIdFilter = userIdRaw == null ? null : UUID.fromString(userIdRaw);
            ctx.json(store.findSessionsPaginated(pageReq, userIdFilter).toJson());
        });

        app.post("/api/v1/admin/outbox/flush", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            requireOwner(p);
            outboxWorker.runOnce();
            ctx.json(Map.of("ok", true));
        });

        // --- Stripe Webhook ---
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
    }

    // --- Private helpers ---

    /**
     * Validate webhook endpoint URL to prevent SSRF attacks.
     * Only HTTPS URLs to public (non-private) hosts are allowed.
     */
    private static void validateWebhookUrl(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_WEBHOOK_URL", "endpoint_url の形式が不正です。");
        }
        // Enforce HTTPS
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiException(400, "INVALID_WEBHOOK_URL", "endpoint_url は https:// のみ許可されています。");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ApiException(400, "INVALID_WEBHOOK_URL", "endpoint_url にホスト名が必要です。");
        }
        // Block private/reserved IPs and hostnames
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                        || isCarrierGradeNat(addr)) {
                    throw new ApiException(400, "INVALID_WEBHOOK_URL",
                            "endpoint_url にプライベート IP アドレスは使用できません。");
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_WEBHOOK_URL", "endpoint_url のホスト名を解決できません。");
        }
        // Block common internal hostnames
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.equals("metadata.google.internal")
                || lower.startsWith("169.254.")) {
            throw new ApiException(400, "INVALID_WEBHOOK_URL",
                    "endpoint_url に内部ホスト名は使用できません。");
        }
    }

    private static boolean isCarrierGradeNat(java.net.InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length != 4) return false;
        // 100.64.0.0/10 (RFC 6598)
        return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private void requireOwner(AuthPrincipal principal) {
        if (principal.serviceToken()) {
            throw new ApiException(403, "FORBIDDEN", "Service token cannot access admin keys");
        }
        policy.enforceMinRole(principal, "OWNER");
    }

    /**
     * Return the first non-blank string from the list. Used for Stripe URL
     * resolution where caller → server default → base-url fallback are
     * tried in order.
     */
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return "";
    }

    /**
     * Parse an ISO-8601 instant query parameter, returning {@code null} for
     * empty / invalid values. Used by SAAS-008 billing usage endpoints.
     */
    private static Instant parseInstantOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Instant.parse(raw); }
        catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid ISO-8601 instant: " + raw);
        }
    }

    private static boolean isAllowedOrigin(String origin) {
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String host = uri.getHost();
            if (host == null) return false;
            return host.equals("unlaxer.org")
                    || host.endsWith(".unlaxer.org")
                    || host.equals("localhost")
                    || host.startsWith("localhost:");
        } catch (Exception e) {
            return false;
        }
    }

    private static String maskIp(String ip) {
        if (ip == null) return "";
        int lastDot = ip.lastIndexOf('.');
        return lastDot < 0 ? ip : ip.substring(0, lastDot) + ".***";
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

    private static String popFlashCookie(io.javalin.http.Context ctx) {
        String value = ctx.cookie("__volta_flash");
        if (value == null || value.isBlank()) {
            return "";
        }
        ctx.res().addHeader("Set-Cookie", "__volta_flash=; Path=/; Max-Age=0; SameSite=Lax");
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
