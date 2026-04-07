package org.unlaxer.infra.volta.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.unlaxer.infra.volta.flow.FlowData;
import org.unlaxer.infra.volta.flow.Sensitive;

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

    // --- initiallyAvailable (ForwardAuth リクエストから抽出) ---

    /**
     * リクエスト元の scheme / host / uri。
     * Traefik ForwardAuth の X-Forwarded-* ヘッダーから構築。
     * scheme は BASE_URL から推定し、X-Forwarded-Proto がなくても正しく動く。
     */
    @FlowData("auth.request_origin")
    public record RequestOrigin(
            @JsonProperty("scheme") String scheme,
            @JsonProperty("host") String host,
            @JsonProperty("uri") String uri) {
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
    @FlowData("auth.config")
    public record AuthConfig(
            @JsonProperty("base_url") URI baseUrl,
            @JsonProperty("cookie_domain") String cookieDomain,
            @JsonProperty("session_ttl_seconds") int sessionTtlSeconds,
            @JsonProperty("allowed_redirect_domains") Set<String> allowedRedirectDomains) {}

    // --- processors が produces するデータ ---

    /** LoginRedirectProcessor が生成。return_to URL をセッションに保持。 */
    @FlowData("auth.login_redirect")
    public record LoginRedirect(
            @JsonProperty("return_to") String returnTo,
            @JsonProperty("login_url") String loginUrl) {}

    /** IdP の認可レスポンス (code, state)。 */
    @FlowData("auth.idp_callback")
    public record IdpCallback(
            @Sensitive @JsonProperty("code") String code,
            @JsonProperty("state") String state,
            @JsonProperty("nonce") String nonce) {}

    /** トークン交換結果。 */
    @FlowData("auth.token_set")
    public record TokenSet(
            @Sensitive @JsonProperty("access_token") String accessToken,
            @Sensitive @JsonProperty("id_token") String idToken,
            @Sensitive @JsonProperty("refresh_token") String refreshToken) {}

    /** IdP から取得したユーザー情報。 */
    @FlowData("auth.resolved_user")
    public record ResolvedUser(
            @JsonProperty("sub") String sub,
            @Sensitive @JsonProperty("email") String email,
            @JsonProperty("name") String name,
            @JsonProperty("mfa_required") boolean mfaRequired,
            @JsonProperty("roles") Set<String> roles) {}

    /** MFA チャレンジの結果。 */
    @FlowData("auth.mfa_result")
    public record MfaResult(
            @JsonProperty("verified") boolean verified,
            @JsonProperty("method") String method) {}

    /**
     * セッション Cookie の情報。
     * Secure フラグは RequestOrigin.scheme から決定 -- FORCE_SECURE_COOKIE 不要。
     */
    @FlowData("auth.session_cookie")
    public record SessionCookie(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("domain") String domain,
            @JsonProperty("secure") boolean secure,
            @JsonProperty("same_site") String sameSite,
            @JsonProperty("max_age") int maxAge) {

        public static SessionCookie create(
                String sessionId, RequestOrigin origin, AuthConfig config) {
            return new SessionCookie(
                    sessionId,
                    config.cookieDomain(),
                    "https".equals(origin.scheme()),
                    "Lax",
                    config.sessionTtlSeconds());
        }
    }

    /** 最終リダイレクト先。return_to のバリデーション済み URL。 */
    @FlowData("auth.final_redirect")
    public record FinalRedirect(@JsonProperty("url") String url) {}
}
