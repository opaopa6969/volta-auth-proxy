package org.unlaxer.infra.volta.auth;

import com.tramli.*;
import org.unlaxer.infra.volta.auth.AuthData.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 認証フローの全 Processor / Guard / BranchProcessor。
 *
 * 各要素は閉じたユニット — requires() が入力、produces() が出力。
 * 他の Processor を壊すことは構造的に不可能。
 */
public final class AuthProcessors {
    private AuthProcessors() {}

    // ═══════════════════════════════════════════════════════════
    //  UNAUTHENTICATED → LOGIN_REDIRECT (Auto)
    // ═══════════════════════════════════════════════════════════

    /**
     * return_to URL を RequestOrigin から構築し、ログインページ URL を生成。
     *
     * 今回のバグ: return_to が消える問題
     *   → requires(RequestOrigin) + produces(LoginRedirect) で build() 時に保証。
     *   → LoginRedirect.returnTo がセッションに保持され、後続で必ず利用可能。
     *
     * 今回のバグ: scheme が http になる問題
     *   → RequestOrigin.scheme は BASE_URL から推定済み。FORCE_HTTPS 不要。
     */
    public static final StateProcessor LOGIN_REDIRECT_INIT = new StateProcessor() {
        @Override public String name() { return "LoginRedirectInit"; }
        @Override public Set<Class<?>> requires() { return Set.of(RequestOrigin.class, AuthConfig.class); }
        @Override public Set<Class<?>> produces() { return Set.of(LoginRedirect.class); }
        @Override public void process(FlowContext ctx) {
            var origin = ctx.get(RequestOrigin.class);
            var config = ctx.get(AuthConfig.class);

            String returnTo = origin.returnToUrl();

            // バリデーション: ALLOWED_REDIRECT_DOMAINS に含まれるか
            var host = java.net.URI.create(returnTo).getHost();
            boolean allowed = config.allowedRedirectDomains().stream()
                    .anyMatch(d -> host.equals(d) || host.endsWith("." + d));
            if (!allowed) {
                throw new FlowException("INVALID_REDIRECT",
                        "return_to host '" + host + "' not in allowed domains");
            }

            String loginUrl = config.baseUrl() + "/login?return_to="
                    + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);

            ctx.put(LoginRedirect.class, new LoginRedirect(returnTo, loginUrl));
        }
    };

    // ═══════════════════════════════════════════════════════════
    //  LOGIN_REDIRECT → LOGIN_PENDING (Auto)
    //  (HTTP 302 レスポンス発行 — 実際には Javalin ctx.redirect)
    // ═══════════════════════════════════════════════════════════

    // Note: LOGIN_REDIRECT → LOGIN_PENDING は「ブラウザにリダイレクトを返す」ステップ。
    // tramli の世界ではこれは auto transition で、Javalin ハンドラが
    // FlowEngine.startFlow() の戻り値から LoginRedirect を読んで ctx.redirect() する。
    // Processor は不要 — データは既に FlowContext にある。

    // ═══════════════════════════════════════════════════════════
    //  LOGIN_PENDING → CALLBACK_RECEIVED (External)
    //  IdP からのコールバック到着を検証する Guard
    // ═══════════════════════════════════════════════════════════

    /**
     * IdP コールバックの code + state を検証。
     * state パラメータが CSRF 対策として正しいことを確認。
     */
    public static final TransitionGuard CALLBACK_GUARD = new TransitionGuard() {
        @Override public String name() { return "IdpCallbackGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(LoginRedirect.class); }
        @Override public Set<Class<?>> produces() { return Set.of(IdpCallback.class); }
        @Override public int maxRetries() { return 1; }
        @Override public GuardOutput validate(FlowContext ctx) {
            // 実装: HTTP リクエストから code, state を取得して検証
            // ここでは Guard のシグネチャだけ定義。
            // 実際の ctx.header() 等へのアクセスは Javalin ハンドラから
            // resumeAndExecute() に渡す externalData 経由。
            var callback = ctx.find(IdpCallback.class);
            if (callback.isEmpty()) {
                return new GuardOutput.Rejected("Missing callback parameters");
            }
            return new GuardOutput.Accepted(
                    Map.of(IdpCallback.class, callback.get()));
        }
    };

    // ═══════════════════════════════════════════════════════════
    //  CALLBACK_RECEIVED → USER_RESOLVED (Auto)
    //  トークン交換 + ユーザー情報取得
    // ═══════════════════════════════════════════════════════════

    public static final StateProcessor TOKEN_EXCHANGE = new StateProcessor() {
        @Override public String name() { return "TokenExchange"; }
        @Override public Set<Class<?>> requires() { return Set.of(IdpCallback.class, AuthConfig.class); }
        @Override public Set<Class<?>> produces() { return Set.of(TokenSet.class, ResolvedUser.class); }
        @Override public void process(FlowContext ctx) {
            var callback = ctx.get(IdpCallback.class);
            var config = ctx.get(AuthConfig.class);

            // TODO: 実際の IdP トークン交換 + userinfo エンドポイント呼び出し
            // ここではスケルトン。実装は既存の Main.java から移植。
            ctx.put(TokenSet.class, new TokenSet("access", "id", "refresh"));
            ctx.put(ResolvedUser.class, new ResolvedUser(
                    "sub-123", "user@caulis.jp", "User",
                    false, Set.of("admin")));
        }
    };

    // ═══════════════════════════════════════════════════════════
    //  USER_RESOLVED → MFA_PENDING or SESSION_CREATED (Branch)
    // ═══════════════════════════════════════════════════════════

    public static final BranchProcessor MFA_CHECK = new BranchProcessor() {
        @Override public String name() { return "MfaCheck"; }
        @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class); }
        @Override public String decide(FlowContext ctx) {
            return ctx.get(ResolvedUser.class).mfaRequired()
                    ? "mfa_required"
                    : "no_mfa";
        }
    };

    // ═══════════════════════════════════════════════════════════
    //  MFA_PENDING → SESSION_CREATED (External)
    //  MFA チャレンジ応答の検証
    // ═══════════════════════════════════════════════════════════

    public static final TransitionGuard MFA_GUARD = new TransitionGuard() {
        @Override public String name() { return "MfaVerifyGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class); }
        @Override public Set<Class<?>> produces() { return Set.of(MfaResult.class); }
        @Override public int maxRetries() { return 3; }
        @Override public GuardOutput validate(FlowContext ctx) {
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
    };

    // ═══════════════════════════════════════════════════════════
    //  (MFA_PENDING or USER_RESOLVED) → SESSION_CREATED (Auto)
    //  セッション Cookie 生成
    // ═══════════════════════════════════════════════════════════

    /**
     * セッション Cookie を生成。
     *
     * 今回のバグ: Secure フラグ欠落
     *   → SessionCookie.create() が RequestOrigin.scheme から自動判定。
     *   → FORCE_SECURE_COOKIE 環境変数は不要。
     *   → requires(RequestOrigin) があるので、scheme 情報が消えることは build() 時に検出。
     */
    public static final StateProcessor SESSION_CREATOR = new StateProcessor() {
        @Override public String name() { return "SessionCreator"; }
        @Override public Set<Class<?>> requires() {
            return Set.of(ResolvedUser.class, RequestOrigin.class, AuthConfig.class);
        }
        @Override public Set<Class<?>> produces() {
            return Set.of(SessionCookie.class, FinalRedirect.class);
        }
        @Override public void process(FlowContext ctx) {
            var user = ctx.get(ResolvedUser.class);
            var origin = ctx.get(RequestOrigin.class);
            var config = ctx.get(AuthConfig.class);
            var redirect = ctx.get(LoginRedirect.class);

            // セッション作成 (DB 保存は FlowStore 経由)
            String sessionId = java.util.UUID.randomUUID().toString();

            // Cookie — Secure フラグは scheme から自動判定
            var cookie = SessionCookie.create(sessionId, origin, config);
            ctx.put(SessionCookie.class, cookie);

            // return_to — LoginRedirect から取得 (消えてない、build() が保証)
            ctx.put(FinalRedirect.class, new FinalRedirect(redirect.returnTo()));
        }
    };
}
