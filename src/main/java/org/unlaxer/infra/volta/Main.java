package org.unlaxer.infra.volta;

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
        OutboxWorker outboxWorker = new OutboxWorker(config, store, notificationService);
        PolicyEngine policy = PolicyEngine.defaultPolicy();
        RateLimiter rateLimiter = new RateLimiter(200);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        DeviceTrustService deviceTrustService = new DeviceTrustService(store);
        GdprService gdprService = new GdprService(store, sessionStore, deviceTrustService, objectMapper);
        KeyCipher secretCipher = new KeyCipher(config.jwtKeyEncryptionSecret());
        TemplateEngine templateEngine = TemplateEngine.create(
                new DirectoryCodeResolver(java.nio.file.Path.of("src/main/jte")),
                java.nio.file.Path.of("target/jte-classes"),
                ContentType.Html,
                Main.class.getClassLoader()
        );

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.jsonMapper(new io.javalin.json.JavalinJackson(objectMapper, false));
            javalinConfig.fileRenderer(new JavalinJte(templateEngine));
            javalinConfig.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        // ─── State Machine Flow Routers (Strangler Fig — parallel to existing routes) ───
        {
            var flowRegistry = new org.unlaxer.infra.volta.flow.FlowDataRegistry(objectMapper);
            // Register all @FlowData types
            for (Class<?> c : new Class<?>[]{
                    org.unlaxer.infra.volta.flow.oidc.OidcFlowData.OidcRequest.class,
                    org.unlaxer.infra.volta.flow.oidc.OidcFlowData.OidcRedirect.class,
                    org.unlaxer.infra.volta.flow.oidc.OidcFlowData.OidcCallback.class,
                    org.unlaxer.infra.volta.flow.oidc.OidcFlowData.OidcTokens.class,
                    org.unlaxer.infra.volta.flow.oidc.OidcFlowData.ResolvedUser.class,
                    org.unlaxer.infra.volta.flow.oidc.OidcFlowData.IssuedSession.class,
                    org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.PasskeyRequest.class,
                    org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.PasskeyChallenge.class,
                    org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.PasskeyAssertion.class,
                    org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.PasskeyVerifiedUser.class,
                    org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.PasskeyIssuedSession.class,
                    org.unlaxer.infra.volta.flow.mfa.MfaFlowData.MfaSessionContext.class,
                    org.unlaxer.infra.volta.flow.mfa.MfaFlowData.MfaCodeSubmission.class,
                    org.unlaxer.infra.volta.flow.mfa.MfaFlowData.MfaVerified.class,
                    org.unlaxer.infra.volta.flow.invite.InviteFlowData.InviteContext.class,
                    org.unlaxer.infra.volta.flow.invite.InviteFlowData.InviteAcceptSubmission.class,
                    org.unlaxer.infra.volta.flow.invite.InviteFlowData.InviteAccepted.class,
                    org.unlaxer.infra.volta.flow.invite.InviteFlowData.InviteCompleted.class
            }) { flowRegistry.register(c); }

            var flowStore = new org.unlaxer.infra.volta.flow.SqlFlowStore(dataSource, objectMapper, flowRegistry);
            var flowEngine = new com.tramli.FlowEngine(flowStore);
            var stateCodec = new org.unlaxer.infra.volta.flow.OidcStateCodec(config.authFlowHmacKey());

            // OIDC Flow
            var fraudAlertClient = new org.unlaxer.infra.volta.FraudAlertClient(config, objectMapper);
            var oidcFlowDef = org.unlaxer.infra.volta.flow.oidc.OidcFlowDef.create(
                    oidcService, stateCodec, authService, appRegistry, store, config, fraudAlertClient);
            new org.unlaxer.infra.volta.flow.oidc.OidcFlowRouter(
                    flowEngine, oidcFlowDef, stateCodec, config, auditService, objectMapper, store, oidcService, fraudAlertClient
            ).register(app);

            // Passkey Flow
            var passkeyFlowDef = org.unlaxer.infra.volta.flow.passkey.PasskeyFlowDef.create(
                    config, authService, appRegistry, store);
            new org.unlaxer.infra.volta.flow.passkey.PasskeyFlowRouter(
                    flowEngine, passkeyFlowDef, config, auditService, objectMapper
            ).register(app);

            // MFA Flow
            var mfaFlowDef = org.unlaxer.infra.volta.flow.mfa.MfaFlowDef.create(store, authService, secretCipher);
            new org.unlaxer.infra.volta.flow.mfa.MfaFlowRouter(
                    flowEngine, mfaFlowDef, config, authService, objectMapper
            ).register(app);

            // Invite Flow
            var inviteFlowDef = org.unlaxer.infra.volta.flow.invite.InviteFlowDef.create(authService, store, config);
            new org.unlaxer.infra.volta.flow.invite.InviteFlowRouter(
                    flowEngine, inviteFlowDef, config, authService, auditService, store, objectMapper
            ).register(app);
        }

        // CORS for auth-console and other subdomains
        app.before(ctx -> {
            String origin = ctx.header("Origin");
            if (origin != null && isAllowedOrigin(origin)) {
                ctx.header("Access-Control-Allow-Origin", origin);
                ctx.header("Access-Control-Allow-Credentials", "true");
                ctx.header("Access-Control-Allow-Headers", "Content-Type, X-CSRF-Token");
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
            }
            if ("OPTIONS".equals(ctx.method().name())) {
                ctx.status(204);
                return;
            }
        });

        app.before(ctx -> ctx.attribute("wantsJson", HttpSupport.wantsJson(ctx)));
        // i18n: resolve Messages from Accept-Language header (ThreadLocal for jte access)
        app.before(ctx -> {
            String acceptLang = ctx.header("Accept-Language");
            Messages msg = Messages.resolve(null, acceptLang);
            ctx.attribute("msg", msg);
            Messages.setCurrent(msg);
        });
        app.after(ctx -> Messages.clearCurrent());
        app.before(ctx -> {
            UUID requestId = SecurityUtils.newUuid();
            ctx.attribute("requestId", requestId);
            ctx.header("X-Request-Id", requestId.toString());
        });
        // IP-based rate limiting for sensitive endpoints
        app.before(ctx -> {
            String path = ctx.path();
            if (path.equals("/healthz") || path.startsWith("/auth/") || path.startsWith("/css/") || path.startsWith("/js/") || path.equals("/login") || path.equals("/callback") || path.equals("/mfa/challenge")) return;
            String ip = HttpSupport.clientIp(ctx);
            if (!rateLimiter.allowRequest(ip, path)) {
                ctx.header("Retry-After", "60");
                HttpSupport.jsonError(ctx, 429, "RATE_LIMITED", "Too many requests");
                ctx.skipRemainingHandlers();
            }
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
            // API requests with valid Origin are exempt from CSRF token check
            // (SameSite=Lax cookie prevents cross-origin cookie attachment)
            String origin = ctx.header("Origin");
            if (origin != null && isAllowedOrigin(origin)) {
                return;
            }
            // No Origin header or unknown Origin → require CSRF token (form submission)
            String sessionCookie = ctx.cookie(AuthService.SESSION_COOKIE);
            if (sessionCookie == null) {
                throw new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。");
            }
            SessionRecord session = sessionStore.findSession(UUID.fromString(sessionCookie))
                    .orElseThrow(() -> new ApiException(403, "CSRF_INVALID", "CSRF トークンが無効です。"));
            String csrf = ctx.formParam("_csrf");
            if (csrf == null) csrf = ctx.header("X-CSRF-Token");
            if (csrf == null || session.csrfToken() == null || !SecurityUtils.constantTimeEquals(session.csrfToken(), csrf)) {
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
                    || path.equals("/auth/verify")
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
        // Tenant MFA required: redirect users without MFA to /settings/security
        app.before(ctx -> {
            String path = ctx.path();
            if (path.startsWith("/settings/") || path.startsWith("/api/v1/users/") || path.startsWith("/css/")
                    || path.startsWith("/js/") || path.startsWith("/console/") || path.equals("/auth/logout")
                    || path.equals("/login") || path.equals("/callback") || path.equals("/healthz")
                    || path.startsWith("/.well-known") || path.equals("/select-tenant")
                    || path.startsWith("/auth/") || path.startsWith("/mfa/")) {
                return;
            }
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) return;
            AuthPrincipal principal = principalOpt.get();
            TenantRecord tenant = store.findTenantById(principal.tenantId()).orElse(null);
            if (tenant == null || !tenant.mfaRequired()) return;
            if (store.hasActiveMfa(principal.userId())) return;
            // Grace period check
            if (tenant.mfaGraceUntil() != null && Instant.now().isBefore(tenant.mfaGraceUntil())) {
                return; // Within grace period, allow but show warning later
            }
            // MFA required but not set up — redirect
            if (Boolean.TRUE.equals(ctx.attribute("wantsJson")) || path.startsWith("/api/")) {
                HttpSupport.jsonError(ctx, 403, "MFA_SETUP_REQUIRED", "Tenant requires MFA. Set up at /settings/security");
                ctx.skipRemainingHandlers();
                return;
            }
            ctx.redirect("/settings/security?setup_required=true");
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
            System.getLogger("volta").log(System.Logger.Level.ERROR, "Unhandled exception on " + ctx.method() + " " + ctx.path(), e);
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

        // SPA: /console/ and /console/* serve index.html, static assets pass through
        app.get("/console/", ctx -> {
            try (var is = Main.class.getResourceAsStream("/public/console/index.html")) {
                if (is != null) { ctx.contentType("text/html"); ctx.result(is.readAllBytes()); }
            }
        });
        app.get("/console", ctx -> ctx.redirect("/console/"));
        app.after("/console/*", ctx -> {
            if (ctx.status().getCode() == 404) {
                try (var is = Main.class.getResourceAsStream("/public/console/index.html")) {
                    if (is != null) { ctx.status(200); ctx.contentType("text/html"); ctx.result(is.readAllBytes()); }
                }
            }
        });

        // ─── Register extracted Routers ───
        new AuthRouter(config, store, authService, sessionStore, auditService,
                oidcService, samlService, appRegistry, notificationService,
                objectMapper, policy, rateLimiter, deviceTrustService).register(app);

        new PasskeyRegistrationRouter(config, store, authService, sessionStore,
                auditService, policy, secretCipher, objectMapper).register(app);

        new AdminRouter(store, authService, sessionStore, policy, auditService).register(app);

        new ApiRouter(store, authService, sessionStore, policy, auditService, jwtService,
                objectMapper, config, gdprService, deviceTrustService, notificationService,
                secretCipher, rateLimiter, outboxWorker).register(app);

        new ScimRouter(store, config, objectMapper).register(app);

        // --- Dev token endpoint ---
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
            AuthRouter.setNoStore(ctx);
            // MFA check: if session exists but MFA not verified, redirect to challenge
            if (authService.isMfaPending(ctx)) {
                String fwdHost  = ctx.header("X-Forwarded-Host");
                String fwdUri   = ctx.header("X-Forwarded-Uri");
                String fwdProto = ctx.header("X-Forwarded-Proto");
                // Infer scheme from BASE_URL when X-Forwarded-Proto is missing or
                // unreliable (CF Tunnel → Traefik HTTP entrypoint → proto=http).
                String baseScheme = config.baseUrl().startsWith("https") ? "https" : "http";
                String proto = fwdProto != null && !fwdProto.equals("http") ? fwdProto : baseScheme;
                String returnTo = (fwdHost != null && fwdUri != null)
                        ? proto + "://" + fwdHost + fwdUri
                        : "/";
                ctx.redirect(config.baseUrl() + "/mfa/challenge?return_to=" + java.net.URLEncoder.encode(
                        returnTo, java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                if (isSuspendedTenantSession(ctx, sessionStore, store)) {
                    ctx.status(403);
                    return;
                }
                // ForwardAuth: Traefik sets X-Forwarded-Host + X-Forwarded-Uri.
                // Return 302 to /login so the browser is redirected instead of
                // receiving a raw 401.
                String fwdHost  = ctx.header("X-Forwarded-Host");
                String fwdUri   = ctx.header("X-Forwarded-Uri");
                String fwdProto = ctx.header("X-Forwarded-Proto");
                if (fwdHost != null && fwdUri != null) {
                    String baseScheme2 = config.baseUrl().startsWith("https") ? "https" : "http";
                    String proto    = fwdProto != null && !fwdProto.equals("http") ? fwdProto : baseScheme2;
                    String returnTo = proto + "://" + fwdHost + fwdUri;
                    ctx.redirect(config.baseUrl() + "/login?return_to=" + java.net.URLEncoder.encode(
                            returnTo, java.nio.charset.StandardCharsets.UTF_8));
                    return;
                }
                ctx.status(401);
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            String appId = ctx.header("X-Volta-App-Id");
            String forwardedHost = ctx.header("X-Forwarded-Host");
            Optional<AppRegistry.AppPolicy> appPolicy = appRegistry.resolve(appId, forwardedHost);
            if (appId != null && !appId.isBlank() && appPolicy.isEmpty()) {
                ctx.status(401);
                return;
            }
            if (appPolicy.isPresent() && Collections.disjoint(principal.roles(), appPolicy.get().allowedRoles())) {
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
            appPolicy.ifPresent(ap -> ctx.header("X-Volta-App-Id", ap.id()));
            ctx.status(200);
        });

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

    // --- Helpers that remain in Main ---

    static boolean isAllowedOrigin(String origin) {
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

    private static boolean isLocalRequest(Context ctx) {
        String ip = HttpSupport.clientIp(ctx);
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
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
}
