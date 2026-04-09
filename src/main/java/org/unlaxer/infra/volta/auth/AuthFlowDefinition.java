package org.unlaxer.infra.volta.auth;

import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.*;
import org.unlaxer.infra.volta.auth.AuthData.*;
import org.unlaxer.infra.volta.flow.OidcStateCodec;

import java.time.Duration;
import java.util.Set;

import static org.unlaxer.infra.volta.auth.AuthState.*;

/**
 * volta-auth-proxy の統一認証フロー定義。
 *
 * ── 今回の3つのバグが全て build() で検出される理由 ──
 *
 * 1. return_to 消失:
 *    LoginRedirectInit.produces(LoginRedirect) -> SessionCreator.requires(LoginRedirect)
 *    -> 間にある全パスで LoginRedirect が available であることが保証される。
 *
 * 2. scheme (http/https) 問題:
 *    RequestOrigin が initiallyAvailable で、全パスで参照可能。
 *    RequestOrigin.fromForwardAuth() が BASE_URL から scheme を推定。
 *
 * 3. Cookie Secure フラグ:
 *    SessionCookie.create() が RequestOrigin.scheme から判定。
 *    SessionCreator.requires(RequestOrigin) があるので scheme が消えない。
 */
public final class AuthFlowDefinition {
    private AuthFlowDefinition() {}

    /**
     * OIDC 認証フロー。
     *
     * @param oidcService    OIDC プロバイダーサービス
     * @param stateCodec     HMAC state エンコーダー
     * @param store          DB ストア
     * @param authService    セッション発行サービス
     * @param appConfig      アプリケーション設定
     * @param appRegistry    アプリケーションレジストリ
     */
    public static FlowDefinition<AuthState> oidcFlow(
            OidcService oidcService,
            OidcStateCodec stateCodec,
            SqlStore store,
            AuthService authService,
            AppConfig appConfig,
            AppRegistry appRegistry) {

        var processors = new AuthProcessors.Container(
                oidcService, stateCodec, store, authService, appConfig, appRegistry);

        return Tramli.define("volta-auth-oidc", AuthState.class)
                .ttl(Duration.ofMinutes(10))
                .maxGuardRetries(3)

                // ForwardAuth リクエストから抽出されるデータ
                .initiallyAvailable(RequestOrigin.class, AuthConfig.class)

                // -- UNAUTHENTICATED -> LOGIN_REDIRECT --
                // return_to URL 確定 + ログインページ URL 生成
                .from(UNAUTHENTICATED).auto(LOGIN_REDIRECT, processors.loginRedirectInit)

                // -- LOGIN_REDIRECT -> LOGIN_PENDING --
                // ブラウザにリダイレクト (Processor なし -- Javalin が LoginRedirect を読む)
                .from(LOGIN_REDIRECT).auto(LOGIN_PENDING, noop("RedirectToLogin"))

                // -- LOGIN_PENDING -> CALLBACK_RECEIVED --
                // IdP からのコールバック到着 (External -- ユーザー操作待ち)
                .from(LOGIN_PENDING).external(CALLBACK_RECEIVED, processors.callbackGuard)

                // -- CALLBACK_RECEIVED -> USER_RESOLVED --
                // トークン交換 + ユーザー情報取得
                .from(CALLBACK_RECEIVED).auto(USER_RESOLVED, processors.tokenExchange)

                // -- USER_RESOLVED -> SESSION_CREATED or MFA_PENDING --
                // MFA 要否で分岐
                .from(USER_RESOLVED).branch(processors.mfaCheck)
                    .to(SESSION_CREATED, "no_mfa", processors.sessionCreator)
                    .to(MFA_PENDING, "mfa_required")
                    .endBranch()

                // -- MFA_PENDING -> SESSION_CREATED --
                // MFA チャレンジ応答検証 (External)
                .from(MFA_PENDING).external(SESSION_CREATED, processors.mfaGuard, processors.sessionCreator)

                // -- SESSION_CREATED -> COMPLETE --
                // Cookie セット + return_to リダイレクト
                .from(SESSION_CREATED).auto(COMPLETE, noop("FinalRedirect"))

                // -- エラーハンドリング --
                .onAnyError(FAILED)

                .build();
    }

    /**
     * データ変換なしで遷移だけ行う noop Processor。
     */
    private static StateProcessor noop(String name) {
        return new StateProcessor() {
            @Override public String name() { return name; }
            @Override public Set<Class<?>> requires() { return Set.of(); }
            @Override public Set<Class<?>> produces() { return Set.of(); }
            @Override public void process(FlowContext ctx) { /* noop */ }
        };
    }

    // -- Mermaid 生成 (CI で使用) --

    public static void main(String[] args) {
        // Mermaid 生成にはダミーの依存でよい (build() のデータフロー検証のみ)
        // 実行: mvn exec:java -Dexec.mainClass=org.unlaxer.infra.volta.auth.AuthFlowDefinition
        System.out.println("AuthFlowDefinition requires dependencies for oidcFlow().");
        System.out.println("Use MermaidGenerator with a fully constructed flow instance.");
    }
}
