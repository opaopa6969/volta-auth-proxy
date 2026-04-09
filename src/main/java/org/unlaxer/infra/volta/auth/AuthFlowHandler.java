package org.unlaxer.infra.volta.auth;

import org.unlaxer.tramli.*;
import io.javalin.http.Context;
import org.unlaxer.infra.volta.*;
import org.unlaxer.infra.volta.auth.AuthData.*;
import org.unlaxer.infra.volta.flow.OidcStateCodec;

import java.net.URI;
import java.util.*;

/**
 * Javalin ハンドラと tramli FlowEngine の統合。
 *
 * 既存の Main.java の /auth/verify, /auth/callback, /mfa/challenge 等の
 * 各ハンドラが、FlowEngine の startFlow / resumeAndExecute に集約される。
 *
 * 3つのエンドポイント:
 * - GET /auth/verify    — ForwardAuth (Traefik)
 * - GET /auth/callback  — IdP コールバック
 * - POST /mfa/challenge — MFA チャレンジ応答
 */
public class AuthFlowHandler {
    private static final System.Logger LOG = System.getLogger("volta.auth");

    private final FlowEngine engine;
    private final FlowDefinition<AuthState> flowDef;
    private final AuthConfig authConfig;
    private final AuthService authService;
    private final AppConfig appConfig;
    private final OidcStateCodec stateCodec;
    private final JwtService jwtService;
    private final AppRegistry appRegistry;
    private final SqlStore store;

    public AuthFlowHandler(FlowEngine engine,
                           FlowDefinition<AuthState> flowDef,
                           AuthConfig authConfig,
                           AuthService authService,
                           AppConfig appConfig,
                           OidcStateCodec stateCodec,
                           JwtService jwtService,
                           AppRegistry appRegistry,
                           SqlStore store) {
        this.engine = engine;
        this.flowDef = flowDef;
        this.authConfig = authConfig;
        this.authService = authService;
        this.appConfig = appConfig;
        this.stateCodec = stateCodec;
        this.jwtService = jwtService;
        this.appRegistry = appRegistry;
        this.store = store;
    }

    /**
     * Traefik ForwardAuth エンドポイント。
     *
     * セッション Cookie があれば 200 + X-Volta-* ヘッダー。
     * なければ新規フローを開始 -> LoginRedirect の loginUrl に 302。
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
            var origin = buildRequestOrigin(ctx);
            String returnTo = origin.returnToUrl();
            LOG.log(System.Logger.Level.INFO, "[verify] MFA pending, redirecting to challenge. returnTo={0}", returnTo);
            ctx.redirect(appConfig.baseUrl() + "/mfa/challenge?return_to="
                    + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8));
            return;
        }

        // 1. セッション Cookie チェック (フロー外)
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
                ctx.status(401);
                return;
            }
            if (appPolicy.isPresent()
                    && Collections.disjoint(principal.roles(), appPolicy.get().allowedRoles())) {
                ctx.status(401);
                return;
            }

            String jwt = jwtService.issueToken(principal);
            ctx.header("X-Volta-User-Id", principal.userId().toString());
            ctx.header("X-Volta-Email", principal.email());
            ctx.header("X-Volta-Tenant-Id", principal.tenantId().toString());
            ctx.header("X-Volta-Tenant-Slug", principal.tenantSlug());
            ctx.header("X-Volta-Roles", String.join(",", principal.roles()));
            ctx.header("X-Volta-Display-Name",
                    principal.displayName() == null ? "" : principal.displayName());
            ctx.header("X-Volta-JWT", jwt);
            appPolicy.ifPresent(ap -> ctx.header("X-Volta-App-Id", ap.id()));
            ctx.status(200);
            return;
        }

        // Suspended tenant check (session exists but authenticate returned empty)
        if (isSuspendedTenantSession(ctx)) {
            ctx.status(403);
            return;
        }

        // 2. セッションなし -> フロー開始
        String fwdHost = ctx.header("X-Forwarded-Host");
        String fwdUri = ctx.header("X-Forwarded-Uri");
        if (fwdHost == null || fwdUri == null) {
            LOG.log(System.Logger.Level.INFO, "[verify] no session, no X-Forwarded-Host/Uri → 401");
            ctx.status(401);
            return;
        }

        var origin = buildRequestOrigin(ctx);
        LOG.log(System.Logger.Level.INFO, "[verify] no session → starting flow. origin={0}://{1}{2}",
                origin.scheme(), origin.host(), origin.uri());

        // 3. FlowEngine.startFlow() -- UNAUTHENTICATED -> auto-chain -> LOGIN_PENDING で停止
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> initialData = Map.of(
                (Class) RequestOrigin.class, origin,
                (Class) AuthConfig.class, authConfig);

        FlowInstance<AuthState> flow = engine.startFlow(flowDef, null, initialData);
        LOG.log(System.Logger.Level.INFO, "[verify] flow started: id={0} state={1}",
                flow.id(), flow.currentState());

        // 4. auto-chain 後の FlowContext から LoginRedirect を取得してリダイレクト
        LoginRedirect loginRedirect = flow.context().get(LoginRedirect.class);
        LOG.log(System.Logger.Level.INFO, "[verify] redirecting to login: {0}", loginRedirect.loginUrl());
        ctx.redirect(loginRedirect.loginUrl());
    }

    /**
     * IdP コールバックエンドポイント。
     *
     * IdP からリダイレクトされてくる。code + state パラメータを検証し、
     * トークン交換 -> ユーザー解決 -> MFA 判定 -> セッション作成。
     */
    public void callback(Context ctx) {
        setNoStore(ctx);
        LOG.log(System.Logger.Level.INFO, "[callback] code={0} state={1} error={2}",
                ctx.queryParam("code") != null ? "present" : "absent",
                ctx.queryParam("state") != null ? "present" : "absent",
                ctx.queryParam("error"));

        String error = ctx.queryParam("error");
        if (error != null) {
            ctx.status(400).result("OIDC failed: " + error);
            return;
        }

        String code = ctx.queryParam("code");
        String state = ctx.queryParam("state");
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            ctx.status(400).result("code/state is required");
            return;
        }

        // 1. フローを復元 (state パラメータからフロー ID を取得)
        String flowId = stateCodec.decode(state)
                .orElseThrow(() -> new ApiException(400, "INVALID_STATE",
                        "Invalid or tampered state parameter"));

        // 2. コールバックデータを externalData に設定
        IdpCallback callback = new IdpCallback(code, state, null);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> externalData = Map.of(
                (Class) IdpCallback.class, callback);

        // 3. resumeAndExecute() -- LOGIN_PENDING -> auto-chain
        LOG.log(System.Logger.Level.INFO, "[callback] resuming flow {0}", flowId);
        FlowInstance<AuthState> flow = engine.resumeAndExecute(flowId, flowDef, externalData);

        // 4. 結果に応じてレスポンス
        AuthState currentState = flow.currentState();
        LOG.log(System.Logger.Level.INFO, "[callback] flow state after resume: {0} (completed={1} exit={2})",
                currentState, flow.isCompleted(), flow.exitState());

        if (currentState == AuthState.SESSION_CREATED || currentState == AuthState.COMPLETE) {
            SessionCookie cookie = flow.context().get(SessionCookie.class);
            LOG.log(System.Logger.Level.INFO, "[callback] setting cookie: secure={0} domain={1} sameSite={2}",
                    cookie.secure(), cookie.domain(), cookie.sameSite());
            setSessionCookieFromFlowData(ctx, cookie);

            FinalRedirect redirect = flow.context().get(FinalRedirect.class);
            LOG.log(System.Logger.Level.INFO, "[callback] redirecting to: {0}", redirect.url());
            ctx.redirect(redirect.url());

        } else if (currentState == AuthState.MFA_PENDING) {
            LOG.log(System.Logger.Level.INFO, "[callback] MFA required, redirecting to challenge");
            ctx.redirect(appConfig.baseUrl() + "/mfa/challenge?flow_id=" + flowId);

        } else {
            LOG.log(System.Logger.Level.WARNING, "[callback] auth failed, state={0} lastError={1}",
                    currentState, flow.lastError());
            ctx.status(401).result("Authentication failed");
        }
    }

    /**
     * MFA チャレンジ応答エンドポイント。
     *
     * TOTP コードを検証し、結果を MfaResult として FlowEngine に渡す。
     * MfaVerifyGuard が MfaResult.verified を確認する。
     */
    public void mfaChallenge(Context ctx) {
        setNoStore(ctx);

        String flowId = ctx.formParam("flow_id");
        if (flowId == null || flowId.isBlank()) {
            flowId = ctx.queryParam("flow_id");
        }
        if (flowId == null || flowId.isBlank()) {
            ctx.status(400).result("flow_id is required");
            return;
        }

        String totpCode = ctx.formParam("code");
        if (totpCode == null || totpCode.isBlank()) {
            ctx.status(400).result("MFA code is required");
            return;
        }

        // TOTP verification happens here, before passing to FlowEngine.
        // We pass the result as MfaResult in externalData; MfaVerifyGuard checks .verified().
        boolean verified = verifyTotpCode(totpCode, ctx);
        MfaResult mfaResult = new MfaResult(verified, "TOTP");

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> externalData = Map.of(
                (Class) MfaResult.class, mfaResult);

        // resumeAndExecute() -- MFA_PENDING -> MfaVerifyGuard -> SESSION_CREATED -> COMPLETE
        FlowInstance<AuthState> flow = engine.resumeAndExecute(flowId, flowDef, externalData);

        AuthState currentState = flow.currentState();

        if (currentState == AuthState.SESSION_CREATED || currentState == AuthState.COMPLETE) {
            SessionCookie cookie = flow.context().get(SessionCookie.class);
            setSessionCookieFromFlowData(ctx, cookie);

            FinalRedirect redirect = flow.context().get(FinalRedirect.class);
            ctx.redirect(redirect.url());
        } else {
            ctx.status(401).result("MFA verification failed");
        }
    }

    // ── private helpers ──

    private RequestOrigin buildRequestOrigin(Context ctx) {
        return RequestOrigin.fromForwardAuth(
                ctx.header("X-Forwarded-Proto"),
                ctx.header("X-Forwarded-Host"),
                ctx.header("X-Forwarded-Uri"),
                URI.create(appConfig.baseUrl()));
    }

    /**
     * FlowContext の SessionCookie データから HTTP Set-Cookie ヘッダーを構築。
     * HttpSupport.setSessionCookie の代替 -- scheme ベースの Secure フラグ。
     */
    private static void setSessionCookieFromFlowData(Context ctx, SessionCookie cookie) {
        StringBuilder sb = new StringBuilder();
        sb.append(AuthService.SESSION_COOKIE).append("=").append(cookie.sessionId())
                .append("; Path=/; Max-Age=").append(cookie.maxAge())
                .append("; HttpOnly; SameSite=").append(cookie.sameSite());
        if (cookie.domain() != null && !cookie.domain().isEmpty()) {
            sb.append("; Domain=").append(cookie.domain());
        }
        if (cookie.secure()) {
            sb.append("; Secure");
        }
        ctx.res().addHeader("Set-Cookie", sb.toString());
    }

    /**
     * TOTP コードを検証。
     * セッション Cookie から userId を取得し、DB の MFA secret で検証。
     */
    private boolean verifyTotpCode(String code, Context ctx) {
        try {
            // The MFA challenge is shown after initial login, so a session exists
            // but mfaVerifiedAt is null. Use the session to find the user.
            Optional<SessionRecord> sessionOpt = authService.currentSession(ctx);
            if (sessionOpt.isEmpty()) {
                return false;
            }
            UUID userId = sessionOpt.get().userId();
            var mfa = store.findUserMfa(userId, "TOTP");
            if (mfa.isEmpty()) {
                return false;
            }
            var gAuth = new com.warrenstrange.googleauth.GoogleAuthenticator();
            return gAuth.authorize(mfa.get().secret(), Integer.parseInt(code));
        } catch (Exception e) {
            return false;
        }
    }

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

    private static void setNoStore(Context ctx) {
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
        ctx.header("Pragma", "no-cache");
    }
}
