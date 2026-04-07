package org.unlaxer.infra.volta.auth;

import java.net.URI;
import java.util.Set;

/**
 * FlowContext に格納される型安全なデータ。
 * 各 record が1つの Class<?> キーとして機能する。
 *
 * requires/produces でこれらの Class を宣言することで、
 * build() 時に「return_to がどこで消えたか」のような問題を構造的に検出する。
 */
public final class AuthData {
    private AuthData() {}

    // ─── initiallyAvailable (ForwardAuth リクエストから抽出) ───

    /**
     * リクエスト元の scheme / host / uri。
     * Traefik ForwardAuth の X-Forwarded-* ヘッダーから構築。
     * scheme は BASE_URL から推定し、X-Forwarded-Proto がなくても正しく動く。
     */
    public record RequestOrigin(String scheme, String host, String uri) {
        public String returnToUrl() {
            return scheme + "://" + host + uri;
        }

        /**
         * ForwardAuth ヘッダーから構築。
         * scheme: X-Forwarded-Proto > BASE_URL の scheme > "http"
         */
        public static RequestOrigin fromForwardAuth(
                String fwdProto, String fwdHost, String fwdUri, URI baseUrl) {
            String scheme;
            if (fwdProto != null && !fwdProto.isBlank()) {
                scheme = fwdProto;
            } else {
                scheme = baseUrl.getScheme(); // https from BASE_URL=https://auth.unlaxer.org
            }
            return new RequestOrigin(
                    scheme,
                    fwdHost != null ? fwdHost : baseUrl.getHost(),
                    fwdUri != null ? fwdUri : "/");
        }
    }

    /** 認証設定 (環境変数から)。 */
    public record AuthConfig(
            URI baseUrl,
            String cookieDomain,
            int sessionTtlSeconds,
            Set<String> allowedRedirectDomains) {}

    // ─── processors が produces するデータ ───

    /** LoginRedirectProcessor が生成。return_to URL をセッションに保持。 */
    public record LoginRedirect(String returnTo, String loginUrl) {}

    /** IdP の認可レスポンス (code, state)。 */
    public record IdpCallback(String code, String state, String nonce) {}

    /** トークン交換結果。 */
    public record TokenSet(String accessToken, String idToken, String refreshToken) {}

    /** IdP から取得したユーザー情報。 */
    public record ResolvedUser(
            String sub, String email, String name,
            boolean mfaRequired, Set<String> roles) {}

    /** MFA チャレンジの結果。 */
    public record MfaResult(boolean verified, String method) {}

    /**
     * セッション Cookie の情報。
     * Secure フラグは RequestOrigin.scheme から決定 — FORCE_SECURE_COOKIE 不要。
     */
    public record SessionCookie(
            String sessionId, String domain,
            boolean secure, String sameSite, int maxAge) {

        public static SessionCookie create(
                String sessionId, RequestOrigin origin, AuthConfig config) {
            return new SessionCookie(
                    sessionId,
                    config.cookieDomain(),
                    "https".equals(origin.scheme()),  // ← ここが今回のバグの根本解決
                    "Lax",
                    config.sessionTtlSeconds());
        }
    }

    /** 最終リダイレクト先。return_to のバリデーション済み URL。 */
    public record FinalRedirect(String url) {}
}
