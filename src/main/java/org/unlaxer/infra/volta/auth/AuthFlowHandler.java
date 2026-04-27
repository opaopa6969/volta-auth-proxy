package org.unlaxer.infra.volta.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unlaxer.tramli.*;
import io.javalin.http.Context;
import org.unlaxer.infra.volta.*;
import org.unlaxer.infra.volta.flow.OidcFlowState;
import org.unlaxer.infra.volta.flow.MfaFlowState;
import org.unlaxer.infra.volta.flow.OidcStateCodec;
import org.unlaxer.infra.volta.flow.oidc.OidcFlowData.*;
import org.unlaxer.infra.volta.flow.mfa.MfaFlowData.*;

import java.util.*;

/**
 * AUTH-010: 統一認証ハンドラ。
 *
 * OidcFlowRouter + MfaFlowRouter + Main.java の /auth/verify を
 * 1つのクラスに集約。全認証エンドポイントを一箇所で管理する。
 *
 * フローエンジン:
 * - OIDC: OidcFlowDef (既存の検証済みフロー)
 * - MFA:  MfaFlowDef  (既存の検証済みフロー)
 * - /auth/verify: 手続き型 (フローエンジン不使用 — セッション確認のみ)
 *
 * エンドポイント:
 * - GET  /auth/verify           — ForwardAuth (Traefik)
 * - GET  /login                 — ログインページ or IdP リダイレクト
 * - GET  /callback              — IdP コールバック (GET)
 * - POST /auth/callback/complete — IdP コールバック (POST/JSON)
 * - GET  /mfa/challenge         — MFA チャレンジページ
 * - POST /auth/mfa/verify       — MFA コード検証
 */
public class AuthFlowHandler {
    private static final System.Logger LOG = System.getLogger("volta.auth");
    private static final String MFA_FLOW_COOKIE = "__volta_mfa_flow";

    // issue-hub#97: response header that explains why /auth/verify returned a
    // non-200. Lets infra/console correlate a redirect or 401 with the actual
    // cause when the user reports a loop — without leaking it into the body.
    private static final String AUTH_REASON_HEADER = "X-Volta-Auth-Reason";

    // issue-hub#97: flag a client that retriggers startOidc within 30s of the
    // previous attempt as a likely orphan — the previous flow expired without
    // a callback, which is a strong signal of Set-Cookie loss, browser
    // pre-fetch, or Back/Forward navigation. Cap the tracked set so a busy
    // node can't grow it unbounded; the cap is generous (typical login QPS
    // is well under this) and entries fall out of the window quickly anyway.
    private static final long ORPHAN_DETECT_WINDOW_MS = 30_000L;
    private static final int ORPHAN_TRACK_MAX = 2_048;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recentOidcStarts =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── 共通サービス ──
    private final AuthService authService;
    private final AppConfig appConfig;
    private final JwtService jwtService;
    private final AppRegistry appRegistry;
    private final SqlStore store;
    private final OidcService oidcService;
    private final OidcStateCodec stateCodec;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final FraudAlertClient fraudAlert;
    private final org.unlaxer.infra.volta.LocalNetworkBypass localNetworkBypass;
    private final org.unlaxer.infra.volta.TenancyPolicy tenancy;

    // ── フローエンジン ──
    private final FlowEngine engine;
    private final FlowDefinition<OidcFlowState> oidcFlowDef;
    private final FlowDefinition<MfaFlowState> mfaFlowDef;

    public AuthFlowHandler(FlowEngine engine,
                           FlowDefinition<OidcFlowState> oidcFlowDef,
                           FlowDefinition<MfaFlowState> mfaFlowDef,
                           AuthService authService,
                           AppConfig appConfig,
                           OidcStateCodec stateCodec,
                           JwtService jwtService,
                           AppRegistry appRegistry,
                           SqlStore store,
                           OidcService oidcService,
                           AuditService auditService,
                           ObjectMapper objectMapper,
                           FraudAlertClient fraudAlert,
                           org.unlaxer.infra.volta.LocalNetworkBypass localNetworkBypass) {
        this(engine, oidcFlowDef, mfaFlowDef, authService, appConfig, stateCodec, jwtService,
             appRegistry, store, oidcService, auditService, objectMapper, fraudAlert,
             localNetworkBypass, new org.unlaxer.infra.volta.TenancyPolicy((org.unlaxer.infra.volta.VoltaConfig) null));
    }

    public AuthFlowHandler(FlowEngine engine,
                           FlowDefinition<OidcFlowState> oidcFlowDef,
                           FlowDefinition<MfaFlowState> mfaFlowDef,
                           AuthService authService,
                           AppConfig appConfig,
                           OidcStateCodec stateCodec,
                           JwtService jwtService,
                           AppRegistry appRegistry,
                           SqlStore store,
                           OidcService oidcService,
                           AuditService auditService,
                           ObjectMapper objectMapper,
                           FraudAlertClient fraudAlert,
                           org.unlaxer.infra.volta.LocalNetworkBypass localNetworkBypass,
                           org.unlaxer.infra.volta.TenancyPolicy tenancy) {
        this.engine = engine;
        this.oidcFlowDef = oidcFlowDef;
        this.mfaFlowDef = mfaFlowDef;
        this.authService = authService;
        this.appConfig = appConfig;
        this.stateCodec = stateCodec;
        this.jwtService = jwtService;
        this.appRegistry = appRegistry;
        this.store = store;
        this.oidcService = oidcService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.fraudAlert = fraudAlert;
        this.localNetworkBypass = localNetworkBypass;
        this.tenancy = tenancy == null
                ? new org.unlaxer.infra.volta.TenancyPolicy((org.unlaxer.infra.volta.VoltaConfig) null)
                : tenancy;
    }

    // ================================================================
    //  GET /auth/verify — ForwardAuth (Traefik)
    // ================================================================

    /**
     * Traefik ForwardAuth エンドポイント。
     * 手続き型 — フローエンジン不使用。セッション Cookie の確認のみ。
     *
     * セッション Cookie があれば 200 + X-Volta-* ヘッダー。
     * なければ /login にリダイレクト。
     */
    public void verify(Context ctx) {
        setNoStore(ctx);

        LOG.log(System.Logger.Level.INFO, "[verify] path={0} fwdHost={1} fwdUri={2} fwdProto={3} cookie={4}",
                ctx.path(),
                ctx.header("X-Forwarded-Host"),
                ctx.header("X-Forwarded-Uri"),
                ctx.header("X-Forwarded-Proto"),
                ctx.cookie(AuthService.SESSION_COOKIE) != null ? "present" : "absent");

        // MFA check: if session exists but MFA not verified, redirect to challenge
        if (authService.isMfaPending(ctx)) {
            String fwdHost  = ctx.header("X-Forwarded-Host");
            String fwdUri   = ctx.header("X-Forwarded-Uri");
            String fwdProto = ctx.header("X-Forwarded-Proto");
            String baseScheme = appConfig.baseUrl().startsWith("https") ? "https" : "http";
            String proto = fwdProto != null && !"http".equals(fwdProto) ? fwdProto : baseScheme;
            String returnTo = (fwdHost != null && fwdUri != null)
                    ? proto + "://" + fwdHost + fwdUri
                    : "/";
            LOG.log(System.Logger.Level.INFO, "[verify] MFA pending, redirecting to challenge. returnTo={0}", returnTo);
            ctx.header(AUTH_REASON_HEADER, "mfa_pending");
            ctx.redirect(appConfig.baseUrl() + "/mfa/challenge?return_to="
                    + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8));
            return;
        }

        // 1. セッション Cookie チェック
        Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
        if (principalOpt.isPresent()) {
            AuthPrincipal principal = principalOpt.get();
            LOG.log(System.Logger.Level.INFO, "[verify] authenticated: user={0} tenant={1}",
                    principal.email(), principal.tenantSlug());

            // App policy check
            String appId = ctx.header("X-Volta-App-Id");
            String forwardedHost = ctx.header("X-Forwarded-Host");
            Optional<AppRegistry.AppPolicy> appPolicy = appRegistry.resolve(appId, forwardedHost);
            if (appId != null && !appId.isBlank() && appPolicy.isEmpty()) {
                ctx.header(AUTH_REASON_HEADER, "app_policy_unknown_app");
                ctx.status(401);
                return;
            }
            if (appPolicy.isPresent()
                    && Collections.disjoint(principal.roles(), appPolicy.get().allowedRoles())) {
                ctx.header(AUTH_REASON_HEADER, "app_policy_role_denied");
                ctx.status(401);
                return;
            }

            // AUTH-014 Phase 2/4: URL-driven tenant re-scoping.
            //   SLUG      → slug extracted from /o/{slug}/ path prefix
            //   SUBDOMAIN → slug extracted from X-Forwarded-Host (Phase 4)
            //   DOMAIN    → whole host matched against tenant_domains (Phase 4)
            // The session's default tenant is preserved; only the outgoing
            // X-Volta-Tenant-* headers get re-scoped for this request.
            UUID effectiveTenantId = principal.tenantId();
            String effectiveTenantSlug = principal.tenantSlug();
            Optional<org.unlaxer.infra.volta.TenantRecord> urlTenantOpt = Optional.empty();
            String urlLabel = null;
            if (tenancy.isSlugRouting()) {
                String s = tenancy.slugFromPath(ctx.header("X-Forwarded-Uri"));
                if (s != null) { urlLabel = s; urlTenantOpt = store.findTenantBySlug(s); }
            } else if (tenancy.isSubdomainRouting()) {
                String s = tenancy.slugFromHost(ctx.header("X-Forwarded-Host"));
                if (s != null) { urlLabel = s; urlTenantOpt = store.findTenantBySlug(s); }
            } else if (tenancy.isDomainRouting()) {
                String host = ctx.header("X-Forwarded-Host");
                if (host != null && !host.isBlank()) {
                    urlLabel = host;
                    urlTenantOpt = store.findTenantByDomain(host);
                }
            }

            if (urlLabel != null) {
                if (urlTenantOpt.isEmpty()) {
                    LOG.log(System.Logger.Level.INFO, "[verify] tenant route unknown label={0}", urlLabel);
                    ctx.header(AUTH_REASON_HEADER, "tenant_route_unknown");
                    ctx.status(404);
                    return;
                }
                var urlTenant = urlTenantOpt.get();
                // Membership check: the signed-in user must belong to the
                // URL-selected tenant, otherwise fail closed.
                var membership = store.findMembership(principal.userId(), urlTenant.id());
                if (membership.isEmpty() || !membership.get().active()) {
                    LOG.log(System.Logger.Level.INFO,
                            "[verify] tenant route denied: user={0} label={1}",
                            principal.email(), urlLabel);
                    ctx.header(AUTH_REASON_HEADER, "tenant_route_denied");
                    ctx.status(403);
                    return;
                }
                effectiveTenantId = urlTenant.id();
                effectiveTenantSlug = urlTenant.slug();
            }

            String jwt = jwtService.issueToken(principal);
            ctx.header("X-Volta-User-Id", principal.userId().toString());
            ctx.header("X-Volta-Email", principal.email());
            ctx.header("X-Volta-Tenant-Id", effectiveTenantId.toString());
            ctx.header("X-Volta-Tenant-Slug", effectiveTenantSlug);
            ctx.header("X-Volta-Roles", String.join(",", principal.roles()));
            ctx.header("X-Volta-Display-Name",
                    principal.displayName() == null ? "" : principal.displayName());
            ctx.header("X-Volta-JWT", jwt);
            appPolicy.ifPresent(ap -> ctx.header("X-Volta-App-Id", ap.id()));
            ctx.status(200);
            return;
        }

        // Suspended tenant check
        if (isSuspendedTenantSession(ctx)) {
            ctx.header(AUTH_REASON_HEADER, "tenant_suspended");
            ctx.status(403);
            return;
        }

        // Local network bypass: no session but LAN/Tailscale IP → allow anonymously
        if (localNetworkBypass.isLocalRequest(ctx)) {
            String ip = org.unlaxer.infra.volta.HttpSupport.clientIp(ctx);
            LOG.log(System.Logger.Level.INFO, "[verify] local-bypass (no session): ip={0}", ip);
            ctx.header("X-Volta-Auth-Source", "local-bypass");
            ctx.status(200);
            return;
        }

        // 2. セッションなし -> /login にリダイレクト
        String fwdHost  = ctx.header("X-Forwarded-Host");
        String fwdUri   = ctx.header("X-Forwarded-Uri");
        String fwdProto = ctx.header("X-Forwarded-Proto");
        if (fwdHost != null && fwdUri != null) {
            String baseScheme = appConfig.baseUrl().startsWith("https") ? "https" : "http";
            String proto = fwdProto != null && !"http".equals(fwdProto) ? fwdProto : baseScheme;
            String returnTo = proto + "://" + fwdHost + fwdUri;
            LOG.log(System.Logger.Level.INFO, "[verify] no session → redirecting to login. returnTo={0}", returnTo);
            ctx.header(AUTH_REASON_HEADER, "cookie_absent_redirect");
            ctx.redirect(appConfig.baseUrl() + "/login?return_to="
                    + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        LOG.log(System.Logger.Level.INFO, "[verify] no session, no X-Forwarded-Host/Uri → 401");
        ctx.header(AUTH_REASON_HEADER, "cookie_absent_401");
        ctx.status(401);
    }

    // ================================================================
    //  GET /login — ログインページ
    // ================================================================

    /**
     * ログインページ表示。
     * ?start=1 → startOidc() にデリゲート (IdP リダイレクト)。
     * それ以外 → login.jte をレンダリング。
     */
    public void loginPage(Context ctx) {
        if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
            HttpSupport.jsonError(ctx, 401, "AUTHENTICATION_REQUIRED", "Login required");
            return;
        }
        if ("1".equals(ctx.queryParam("start"))) {
            startOidc(ctx);
            return;
        }
        // Show login page with provider list
        String returnTo = ctx.queryParam("return_to");
        String inviteCode = ctx.queryParam("invite");
        java.util.Map<String, String> inviteContext = null;
        if (inviteCode != null && !inviteCode.isBlank()) {
            var invitation = store.findInvitationByCode(inviteCode).orElse(null);
            if (invitation != null) {
                String tenantName = store.findTenantById(invitation.tenantId())
                        .map(TenantRecord::name).orElse("ワークスペース");
                String inviterName = store.findUserById(invitation.createdBy())
                        .map(UserRecord::displayName).orElse("メンバー");
                inviteContext = java.util.Map.of(
                        "tenantName", tenantName, "inviterName", inviterName, "role", invitation.role());
            }
        }
        String baseParams = (returnTo != null ? "&return_to=" + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8) : "")
                + (inviteCode != null ? "&invite=" + java.net.URLEncoder.encode(inviteCode, java.nio.charset.StandardCharsets.UTF_8) : "");
        boolean isSwitchAccount = returnTo != null && returnTo.startsWith("/invite/");
        ctx.render("auth/login.jte", io.javalin.rendering.template.TemplateUtil.model(
                "title", "ログイン",
                "inviteContext", inviteContext,
                "providers", oidcService.enabledProviders(),
                "baseParams", baseParams,
                "isSwitchAccount", isSwitchAccount
        ));
    }

    // ================================================================
    //  GET /login?start=1 — OIDC フロー開始 → IdP リダイレクト
    // ================================================================

    /**
     * OidcFlowDef フローを開始し、IdP の認可 URL にリダイレクト。
     * OidcFlowRouter.handleStart() と同一ロジック。
     */
    private void startOidc(Context ctx) {
        String returnTo = ctx.queryParam("return_to");
        String inviteCode = ctx.queryParam("invite");
        String provider = ctx.queryParam("provider");

        String clientIp = HttpSupport.clientIp(ctx);
        String userAgent = ctx.userAgent();
        detectOrphanStart(clientIp, userAgent, provider);

        OidcRequest request = new OidcRequest(
                provider, returnTo, inviteCode,
                clientIp, userAgent
        );

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> initialData = Map.of((Class) OidcRequest.class, request);

        FlowInstance<OidcFlowState> flow = engine.startFlow(oidcFlowDef, null, initialData);

        // Flow should be in REDIRECTED state with OidcRedirect in context
        OidcRedirect redirect = flow.context().get(OidcRedirect.class);
        LOG.log(System.Logger.Level.INFO, "[startOidc] flow={0} state={1} redirecting to IdP",
                flow.id(), flow.currentState());
        ctx.redirect(redirect.authorizationUrl());
    }

    /**
     * issue-hub#97: warn when the same client repeats startOidc within
     * {@link #ORPHAN_DETECT_WINDOW_MS}. The previous flow probably never saw
     * its callback (orphan) — useful for spotting Set-Cookie loss, browser
     * pre-fetch, or Back/Forward re-firing the login link.
     */
    private void detectOrphanStart(String clientIp, String userAgent, String provider) {
        String fingerprint = (clientIp == null ? "-" : clientIp)
                + "|" + (userAgent == null ? "-" : Integer.toHexString(userAgent.hashCode()))
                + "|" + (provider == null ? "-" : provider);
        long now = System.currentTimeMillis();
        Long prev = recentOidcStarts.put(fingerprint, now);
        if (prev != null && now - prev < ORPHAN_DETECT_WINDOW_MS) {
            LOG.log(System.Logger.Level.WARNING,
                    "[startOidc] possible orphan flow: client repeated startOidc {0}ms after previous (window={1}ms, ip={2}, provider={3}). " +
                            "Likely cookie-loss / pre-fetch / Back-Forward.",
                    now - prev, ORPHAN_DETECT_WINDOW_MS, clientIp, provider);
        }
        if (recentOidcStarts.size() > ORPHAN_TRACK_MAX) {
            long cutoff = now - ORPHAN_DETECT_WINDOW_MS * 4;
            recentOidcStarts.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    // ================================================================
    //  GET /callback — IdP コールバック (GET)
    // ================================================================

    /**
     * IdP からのコールバック (GET)。
     * callback.jte をレンダリング (JS が POST /auth/callback/complete を呼ぶ)。
     * JSON リクエストの場合は直接処理。
     */
    public void callback(Context ctx) {
        setNoStore(ctx);

        String error = ctx.queryParam("error");
        if (error != null) {
            throw new ApiException(400, "OIDC_FAILED", "OIDC failed: " + error);
        }

        String code = ctx.queryParam("code");
        String state = ctx.queryParam("state");
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "code/state is required");
        }

        if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
            String redirectTo = completeCallback(ctx, code, state);
            ctx.json(Map.of("redirect_to", redirectTo));
        } else {
            // Render callback page that POSTs to /auth/callback/complete
            ctx.render("auth/callback.jte", io.javalin.rendering.template.TemplateUtil.model(
                    "title", "ログイン処理中",
                    "code", code,
                    "state", state
            ));
        }
    }

    // ================================================================
    //  POST /auth/callback/complete — IdP コールバック (POST/JSON)
    // ================================================================

    /**
     * IdP コールバック (POST)。callback.jte の JS が呼ぶ。
     * JSON body: {code, state}
     */
    public void callbackPost(Context ctx) {
        setNoStore(ctx);

        try {
            var body = objectMapper.readTree(ctx.body());
            String code = body.path("code").asText();
            String state = body.path("state").asText();
            if (code.isBlank() || state.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "code/state is required");
            }
            LOG.log(System.Logger.Level.INFO, "[callbackPost] code=present state={0}",
                    state.substring(0, Math.min(20, state.length())) + "...");
            String redirectTo = completeCallback(ctx, code, state);
            LOG.log(System.Logger.Level.INFO, "[callbackPost] success, redirectTo={0}", redirectTo);
            ctx.json(Map.of("redirect_to", redirectTo));
        } catch (ApiException e) {
            LOG.log(System.Logger.Level.ERROR, "[callbackPost] ApiException: {0} {1}", e.code(), e.getMessage());
            throw e;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[callbackPost] Exception: {0}", e.toString(), e);
            throw new ApiException(400, "CALLBACK_ERROR", "Callback processing failed: " + e.getMessage());
        }
    }

    // ================================================================
    //  GET /mfa/challenge — MFA チャレンジページ
    // ================================================================

    /**
     * MFA チャレンジページ表示。
     * MfaFlowDef フローを開始し、challenge ページをレンダリング。
     */
    public void mfaChallengePage(Context ctx) {
        // Must have a session with pending MFA
        if (!authService.isMfaPending(ctx)) {
            ctx.redirect("/select-tenant");
            return;
        }

        // Always create a fresh MFA flow (clears any stale/expired flow cookie)
        clearMfaFlowCookie(ctx);

        // Start new MFA flow
        SessionRecord session = authService.currentSession(ctx)
                .orElseThrow(() -> new ApiException(401, "INVALID_SESSION", "No valid session"));
        String returnTo = session.returnTo();

        MfaSessionContext mfaCtx = new MfaSessionContext(
                session.id(), session.userId(), returnTo);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> initialData = Map.of((Class) MfaSessionContext.class, mfaCtx);

        FlowInstance<MfaFlowState> flow = engine.startFlow(mfaFlowDef, session.id().toString(), initialData);

        // Set MFA flow cookie (HttpOnly, short TTL)
        setMfaFlowCookie(ctx, flow.id());
        ctx.render("auth/mfa-challenge.jte", io.javalin.rendering.template.TemplateUtil.model(
                "title", "MFA 認証",
                "return_to", ctx.queryParam("return_to")
        ));
    }

    // ================================================================
    //  POST /auth/mfa/verify — MFA コード検証
    // ================================================================

    /**
     * MFA コード検証。MfaFlowDef フローを resume して検証。
     * JSON body: {code: 123456} or {recovery_code: "XXXX-XXXX"}
     */
    public void mfaVerify(Context ctx) {
        String flowId = ctx.cookie(MFA_FLOW_COOKIE);
        if (flowId == null || flowId.isBlank()) {
            // No flow cookie — redirect to challenge to start a new flow
            ctx.json(Map.of("ok", false, "redirect_to", "/mfa/challenge"));
            return;
        }

        try {
            var body = objectMapper.readTree(ctx.body());
            int code = body.path("code").asInt(0);
            String recoveryCode = body.has("recovery_code") ? body.path("recovery_code").asText() : null;

            MfaCodeSubmission submission = new MfaCodeSubmission(code, recoveryCode);

            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<Class<?>, Object> externalData = Map.of((Class) MfaCodeSubmission.class, submission);

            FlowInstance<MfaFlowState> flow = engine.resumeAndExecute(flowId, mfaFlowDef, externalData);

            if (flow.isCompleted() && "VERIFIED".equals(flow.exitState())) {
                MfaVerified verified = flow.context().get(MfaVerified.class);
                clearMfaFlowCookie(ctx);
                ctx.json(Map.of("ok", true, "redirect_to", verified.redirectTo()));
            } else if (flow.isCompleted()) {
                clearMfaFlowCookie(ctx);
                ctx.json(Map.of("ok", false,
                        "error", Map.of("code", "MFA_FAILED", "message", "MFA verification failed. Please try again."),
                        "redirect_to", "/mfa/challenge"));
            } else {
                // Guard rejected but retries remain
                ctx.json(Map.of("ok", false,
                        "error", Map.of("code", "MFA_INVALID_CODE", "message", "Invalid code, please try again")));
            }
        } catch (FlowException fe) {
            clearMfaFlowCookie(ctx);
            String code = fe.code();
            String msg = switch (code) {
                case "FLOW_ALREADY_COMPLETED" -> "MFA session already used. Starting new challenge.";
                case "FLOW_EXPIRED" -> "MFA session expired. Please try again.";
                default -> "MFA session not found. Please try again.";
            };
            ctx.json(Map.of("ok", false, "redirect_to", "/mfa/challenge",
                    "error", Map.of("code", code, "message", msg)));
        } catch (Exception e) {
            ctx.json(Map.of("ok", false,
                    "error", Map.of("code", "BAD_REQUEST", "message", "Invalid request: " + e.getMessage())));
        }
    }

    // ================================================================
    //  共通コールバック処理 (OIDC)
    // ================================================================

    /**
     * OIDC コールバック完了処理。
     * state → flow_id デコード → OidcFlowDef フロー resume → セッション Cookie 設定。
     * OidcFlowRouter.completeCallback() と同一ロジック。
     */
    private String completeCallback(Context ctx, String code, String state) {
        // Decode HMAC-signed state → flow_id
        String flowId = stateCodec.decode(state)
                .orElseThrow(() -> {
                    LOG.log(System.Logger.Level.ERROR,
                            "[completeCallback] INVALID_STATE: decode failed for state={0}",
                            state.substring(0, Math.min(20, state.length())) + "...");
                    return new ApiException(400, "INVALID_STATE", "Invalid or tampered state parameter");
                });
        LOG.log(System.Logger.Level.INFO, "[completeCallback] flowId={0}", flowId);

        // Resume flow with callback data
        OidcCallback callback = new OidcCallback(code, state);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> externalData = Map.of((Class) OidcCallback.class, callback);

        LOG.log(System.Logger.Level.INFO, "[completeCallback] resuming flow {0}", flowId);
        FlowInstance<OidcFlowState> flow = engine.resumeAndExecute(flowId, oidcFlowDef, externalData);
        LOG.log(System.Logger.Level.INFO, "[completeCallback] flow state={0} completed={1} exit={2}",
                flow.currentState(), flow.isCompleted(), flow.exitState());

        if (!flow.isCompleted()) {
            // issue-hub#97: include flowId + currentState so the operator can
            // pinpoint the stuck flow in auth-proxy.log without grepping by
            // timestamp alone.
            LOG.log(System.Logger.Level.ERROR,
                    "[completeCallback] FLOW_INCOMPLETE: flowId={0} currentState={1} exitState={2}",
                    flow.id(), flow.currentState(), flow.exitState());
            throw new ApiException(500, "FLOW_INCOMPLETE",
                    "OIDC flow did not complete: flowId=" + flow.id()
                            + " currentState=" + flow.currentState());
        }

        if ("TERMINAL_ERROR".equals(flow.exitState()) || "EXPIRED".equals(flow.exitState())) {
            throw new ApiException(400, "OIDC_FAILED", "Authentication failed: " + flow.exitState());
        }

        // Extract result and set session cookie
        IssuedSession session = flow.context().get(IssuedSession.class);
        HttpSupport.setSessionCookie(ctx, AuthService.SESSION_COOKIE,
                session.sessionId().toString(), appConfig.sessionTtlSeconds());

        // Audit log
        ResolvedUser user = flow.context().get(ResolvedUser.class);
        OidcTokens tokens = flow.context().get(OidcTokens.class);
        AuthPrincipal principal = new AuthPrincipal(
                user.userId(), user.email(), user.displayName(),
                user.tenantId(), user.tenantName(), user.tenantSlug(),
                user.roles(), false
        );
        auditService.log(ctx, "LOGIN_SUCCESS", principal, "SESSION",
                session.sessionId().toString(), Map.of(
                        "via", tokens.provider().toLowerCase() + "_oidc",
                        "invite", tokens.inviteCode() != null,
                        "flow_id", flow.id()
                ));

        // fraud-alert feedback (fire-and-forget)
        fraudAlert.reportLoginSucceed(user.userId(), user.tenantId(),
                flow.id(), tokens.email(), ctx.userAgent());

        // MFA redirect override
        if (session.mfaPending()) {
            return "/mfa/challenge";
        }
        return session.redirectTo();
    }

    // ================================================================
    //  ヘルパー
    // ================================================================

    private boolean isSuspendedTenantSession(Context ctx) {
        try {
            Optional<SessionRecord> sessionOpt = authService.currentSession(ctx);
            if (sessionOpt.isEmpty()) {
                return false;
            }
            return store.findTenantDetailById(sessionOpt.get().tenantId())
                    .map(t -> !t.active())
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void setMfaFlowCookie(Context ctx, String flowId) {
        HttpSupport.setSessionCookie(ctx, MFA_FLOW_COOKIE, flowId, 300);
    }

    private static void clearMfaFlowCookie(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(MFA_FLOW_COOKIE).append("=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        if ("true".equalsIgnoreCase(System.getenv("FORCE_SECURE_COOKIE")) || ctx.req().isSecure()) {
            sb.append("; Secure");
        }
        ctx.res().addHeader("Set-Cookie", sb.toString());
    }

    private static void setNoStore(Context ctx) {
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
        ctx.header("Pragma", "no-cache");
    }
}
