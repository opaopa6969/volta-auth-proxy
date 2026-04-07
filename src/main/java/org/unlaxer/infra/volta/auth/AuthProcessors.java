package org.unlaxer.infra.volta.auth;

import com.tramli.*;
import org.unlaxer.infra.volta.*;
import org.unlaxer.infra.volta.auth.AuthData.*;
import org.unlaxer.infra.volta.flow.OidcStateCodec;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 認証フローの全 Processor / Guard / BranchProcessor。
 *
 * 各要素は閉じたユニット -- requires() が入力、produces() が出力。
 * 依存サービスはコンストラクタ経由で注入。
 */
public final class AuthProcessors {
    private static final System.Logger LOG = System.getLogger("volta.auth.processors");

    private AuthProcessors() {}

    // ======================================================
    //  Container: 依存サービスを持ち、全 Processor を提供
    // ======================================================

    /**
     * 依存を受け取り、各 Processor インスタンスを保持するコンテナ。
     * AuthFlowDefinition.oidcFlow() で使用。
     */
    public static final class Container {
        public final StateProcessor loginRedirectInit;
        public final TransitionGuard callbackGuard;
        public final StateProcessor tokenExchange;
        public final BranchProcessor mfaCheck;
        public final TransitionGuard mfaGuard;
        public final StateProcessor sessionCreator;

        public Container(OidcService oidcService,
                         OidcStateCodec stateCodec,
                         SqlStore store,
                         AuthService authService,
                         AppConfig appConfig,
                         AppRegistry appRegistry) {
            this.loginRedirectInit = new LoginRedirectInit();
            this.callbackGuard = new CallbackGuard();
            this.tokenExchange = new TokenExchange(oidcService, stateCodec, appConfig, store);
            this.mfaCheck = new MfaCheck();
            this.mfaGuard = new MfaGuard(store);
            this.sessionCreator = new SessionCreator(authService, appRegistry, store, appConfig);
        }
    }

    // ======================================================
    //  UNAUTHENTICATED -> LOGIN_REDIRECT (Auto)
    // ======================================================

    /**
     * return_to URL を RequestOrigin から構築し、ログインページ URL を生成。
     *
     * バグ修正: return_to が消える問題
     *   -> requires(RequestOrigin) + produces(LoginRedirect) で build() 時に保証。
     *
     * バグ修正: scheme が http になる問題
     *   -> RequestOrigin.scheme は BASE_URL から推定済み。FORCE_HTTPS 不要。
     */
    static final class LoginRedirectInit implements StateProcessor {
        @Override public String name() { return "LoginRedirectInit"; }
        @Override public Set<Class<?>> requires() { return Set.of(RequestOrigin.class, AuthConfig.class); }
        @Override public Set<Class<?>> produces() { return Set.of(LoginRedirect.class); }

        @Override
        public void process(FlowContext ctx) {
            var origin = ctx.get(RequestOrigin.class);
            var config = ctx.get(AuthConfig.class);

            String returnTo = origin.returnToUrl();
            LOG.log(System.Logger.Level.INFO, "[LoginRedirectInit] scheme={0} host={1} uri={2} returnTo={3}",
                    origin.scheme(), origin.host(), origin.uri(), returnTo);

            // バリデーション: ALLOWED_REDIRECT_DOMAINS に含まれるか
            var host = java.net.URI.create(returnTo).getHost();
            boolean allowed = config.allowedRedirectDomains().stream()
                    .anyMatch(d -> {
                        String p = d.trim().toLowerCase(Locale.ROOT);
                        String h = host.toLowerCase(Locale.ROOT);
                        if (p.startsWith("*.")) {
                            String suffix = p.substring(1); // ".unlaxer.org"
                            return h.endsWith(suffix) || h.equals(p.substring(2));
                        }
                        return h.equals(p);
                    });
            if (!allowed) {
                throw new FlowException("INVALID_REDIRECT",
                        "return_to host '" + host + "' not in allowed domains");
            }

            String loginUrl = config.baseUrl() + "/login?return_to="
                    + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);

            LOG.log(System.Logger.Level.INFO, "[LoginRedirectInit] loginUrl={0}", loginUrl);
            ctx.put(LoginRedirect.class, new LoginRedirect(returnTo, loginUrl));
        }
    }

    // ======================================================
    //  LOGIN_PENDING -> CALLBACK_RECEIVED (External)
    //  IdP からのコールバック到着を検証する Guard
    // ======================================================

    /**
     * IdP コールバックの code + state を検証。
     * state パラメータは HMAC で署名されており、router が decode 済み。
     * Guard は code/state が FlowContext に存在することを確認する。
     */
    static final class CallbackGuard implements TransitionGuard {
        @Override public String name() { return "IdpCallbackGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(LoginRedirect.class); }
        @Override public Set<Class<?>> produces() { return Set.of(IdpCallback.class); }
        @Override public int maxRetries() { return 1; }

        @Override
        public GuardOutput validate(FlowContext ctx) {
            var callback = ctx.find(IdpCallback.class);
            if (callback.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "[CallbackGuard] REJECTED: missing callback params");
                return new GuardOutput.Rejected("Missing callback parameters");
            }
            IdpCallback cb = callback.get();
            if (cb.code() == null || cb.code().isBlank()) {
                LOG.log(System.Logger.Level.WARNING, "[CallbackGuard] REJECTED: missing code");
                return new GuardOutput.Rejected("Missing authorization code");
            }
            LOG.log(System.Logger.Level.INFO, "[CallbackGuard] ACCEPTED: code=present state={0}", cb.state());
            return new GuardOutput.Accepted(
                    Map.of(IdpCallback.class, cb));
        }
    }

    // ======================================================
    //  CALLBACK_RECEIVED -> USER_RESOLVED (Auto)
    //  トークン交換 + ユーザー情報取得
    // ======================================================

    /**
     * IdP のトークン交換を実行し、ユーザー情報を解決する。
     * 既存の OidcTokenExchangeProcessor + UserResolveProcessor のロジックを統合。
     */
    static final class TokenExchange implements StateProcessor {
        private final OidcService oidcService;
        private final OidcStateCodec stateCodec;
        private final AppConfig appConfig;
        private final SqlStore store;

        TokenExchange(OidcService oidcService, OidcStateCodec stateCodec,
                      AppConfig appConfig, SqlStore store) {
            this.oidcService = oidcService;
            this.stateCodec = stateCodec;
            this.appConfig = appConfig;
            this.store = store;
        }

        @Override public String name() { return "TokenExchange"; }
        @Override public Set<Class<?>> requires() { return Set.of(IdpCallback.class, AuthConfig.class); }
        @Override public Set<Class<?>> produces() { return Set.of(TokenSet.class, ResolvedUser.class); }

        @Override
        public void process(FlowContext ctx) {
            var callback = ctx.get(IdpCallback.class);
            LOG.log(System.Logger.Level.INFO, "[TokenExchange] starting token exchange");

            // Decode state to get the provider info from the OIDC sub-flow
            // In the unified flow, the callback comes from the IdP redirect.
            // We need to resolve the provider. For now, use the first enabled provider
            // (same pattern as OidcInitProcessor fallback).
            IdpProvider idp = resolveDefaultProvider();

            // Build a minimal OidcFlowRecord for the exchange
            OidcFlowRecord flowRecord = new OidcFlowRecord(
                    callback.state(),
                    callback.nonce(),  // nonce from callback (may be null)
                    null,              // codeVerifier — not used in unified flow
                    null,              // returnTo — tracked in LoginRedirect
                    null,              // inviteCode
                    null,              // expiresAt
                    idp.id()
            );

            // Exchange code for identity
            var http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            OidcIdentity identity = idp.exchange(callback.code(), flowRecord, appConfig, http, mapper);

            ctx.put(TokenSet.class, new TokenSet(
                    "exchanged", // access token not stored in unified flow
                    "exchanged", // id token not stored
                    null));

            // Upsert user (same as UserResolveProcessor)
            UserRecord user = store.upsertUser(identity.email(), identity.displayName(), identity.sub());

            // Resolve tenant
            List<TenantRecord> tenants = store.findTenantsByUser(user.id());
            TenantRecord tenant = tenants.isEmpty()
                    ? store.createPersonalTenant(user)
                    : tenants.getFirst();

            // Check membership and roles
            MembershipRecord membership = store.findMembership(user.id(), tenant.id()).orElse(null);
            Set<String> roles;
            if (membership != null && membership.active()) {
                roles = Set.of(membership.role());
            } else {
                roles = Set.of();
            }

            boolean mfaRequired = store.hasActiveMfa(user.id());

            LOG.log(System.Logger.Level.INFO, "[TokenExchange] resolved: email={0} mfa={1} roles={2}",
                    identity.email(), mfaRequired, roles);
            ctx.put(ResolvedUser.class, new ResolvedUser(
                    identity.sub(),
                    identity.email(),
                    identity.displayName(),
                    mfaRequired,
                    roles));
        }

        private IdpProvider resolveDefaultProvider() {
            var enabled = oidcService.enabledProviders();
            if (!enabled.isEmpty()) return enabled.getFirst();
            throw new FlowException("NO_IDP", "No identity provider configured");
        }
    }

    // ======================================================
    //  USER_RESOLVED -> MFA_PENDING or SESSION_CREATED (Branch)
    // ======================================================

    /**
     * MFA 要否で分岐。
     * 既存の RiskAndMfaBranch のロジックを簡素化 (リスクスコアは OIDC sub-flow が担当)。
     */
    static final class MfaCheck implements BranchProcessor {
        @Override public String name() { return "MfaCheck"; }
        @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class); }

        @Override
        public String decide(FlowContext ctx) {
            boolean mfa = ctx.get(ResolvedUser.class).mfaRequired();
            LOG.log(System.Logger.Level.INFO, "[MfaCheck] mfaRequired={0} → {1}", mfa, mfa ? "mfa_required" : "no_mfa");
            return mfa ? "mfa_required" : "no_mfa";
        }
    }

    // ======================================================
    //  MFA_PENDING -> SESSION_CREATED (External)
    //  MFA チャレンジ応答の検証
    // ======================================================

    /**
     * MFA TOTP コードを検証する Guard。
     * googleauth ライブラリ経由で TOTP 検証 (既存 MfaCodeGuard パターン)。
     */
    static final class MfaGuard implements TransitionGuard {
        private final SqlStore store;

        MfaGuard(SqlStore store) {
            this.store = store;
        }

        @Override public String name() { return "MfaVerifyGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class); }
        @Override public Set<Class<?>> produces() { return Set.of(MfaResult.class); }
        @Override public int maxRetries() { return 3; }

        @Override
        public GuardOutput validate(FlowContext ctx) {
            var result = ctx.find(MfaResult.class);
            if (result.isEmpty()) {
                return new GuardOutput.Rejected("MFA not verified");
            }
            if (!result.get().verified()) {
                return new GuardOutput.Rejected("MFA verification failed");
            }
            return new GuardOutput.Accepted(
                    Map.of(MfaResult.class, result.get()));
        }
    }

    // ======================================================
    //  -> SESSION_CREATED (Auto)
    //  セッション Cookie 生成
    // ======================================================

    /**
     * セッション Cookie を生成。
     *
     * バグ修正: Secure フラグ欠落
     *   -> SessionCookie.create() が RequestOrigin.scheme から自動判定。
     *   -> FORCE_SECURE_COOKIE 環境変数は不要。
     *   -> requires(RequestOrigin) があるので、scheme 情報が消えることは build() 時に検出。
     */
    static final class SessionCreator implements StateProcessor {
        private final AuthService authService;
        private final AppRegistry appRegistry;
        private final SqlStore store;
        private final AppConfig appConfig;

        SessionCreator(AuthService authService, AppRegistry appRegistry,
                       SqlStore store, AppConfig appConfig) {
            this.authService = authService;
            this.appRegistry = appRegistry;
            this.store = store;
            this.appConfig = appConfig;
        }

        @Override public String name() { return "SessionCreator"; }
        @Override public Set<Class<?>> requires() {
            return Set.of(ResolvedUser.class, RequestOrigin.class, AuthConfig.class);
        }
        @Override public Set<Class<?>> produces() {
            return Set.of(SessionCookie.class, FinalRedirect.class);
        }

        @Override
        public void process(FlowContext ctx) {
            var user = ctx.get(ResolvedUser.class);
            var origin = ctx.get(RequestOrigin.class);
            var config = ctx.get(AuthConfig.class);
            var redirect = ctx.get(LoginRedirect.class);

            // Resolve user from DB for session issuance (upsertUser is idempotent)
            UserRecord dbUser = store.upsertUser(user.email(), user.name(), user.sub());

            // Resolve tenant
            List<TenantRecord> tenants = store.findTenantsByUser(dbUser.id());
            TenantRecord tenant = tenants.isEmpty()
                    ? store.createPersonalTenant(dbUser)
                    : tenants.getFirst();

            MembershipRecord membership = store.findMembership(dbUser.id(), tenant.id()).orElse(null);
            List<String> roles = (membership != null && membership.active())
                    ? List.of(membership.role())
                    : List.of();

            AuthPrincipal principal = new AuthPrincipal(
                    dbUser.id(), dbUser.email(), dbUser.displayName(),
                    tenant.id(), tenant.name(), tenant.slug(),
                    roles, false
            );

            // Issue session via AuthService (handles concurrent session limits, CSRF token, etc.)
            UUID sessionId = authService.issueSession(
                    principal, redirect.returnTo(),
                    null, null // IP/UA are HTTP concerns — set by handler
            );

            // Cookie -- Secure フラグは scheme から自動判定
            var cookie = SessionCookie.create(sessionId.toString(), origin, config);
            LOG.log(System.Logger.Level.INFO, "[SessionCreator] session={0} secure={1} scheme={2} domain={3}",
                    sessionId, cookie.secure(), origin.scheme(), cookie.domain());
            ctx.put(SessionCookie.class, cookie);

            // Resolve redirect destination
            String redirectUrl = resolveRedirectTo(dbUser, redirect, tenants);
            LOG.log(System.Logger.Level.INFO, "[SessionCreator] redirectTo={0} (returnTo={1})",
                    redirectUrl, redirect.returnTo());
            ctx.put(FinalRedirect.class, new FinalRedirect(redirectUrl));
        }

        private String resolveRedirectTo(UserRecord user, LoginRedirect redirect,
                                         List<TenantRecord> tenants) {
            // Multiple tenants -> tenant selection
            if (tenants.size() > 1) {
                return "/select-tenant";
            }

            // Validated return_to
            if (redirect.returnTo() != null
                    && HttpSupport.isAllowedReturnTo(redirect.returnTo(), appConfig.allowedRedirectDomains())) {
                return redirect.returnTo();
            }

            return appRegistry.defaultAppUrl().orElse("/select-tenant");
        }
    }
}
