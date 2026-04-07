package org.unlaxer.infra.volta.auth;

import com.tramli.FlowDefinition;
import com.tramli.MermaidGenerator;
import com.tramli.Tramli;
import org.unlaxer.infra.volta.auth.AuthData.*;

import java.time.Duration;

import static org.unlaxer.infra.volta.auth.AuthProcessors.*;
import static org.unlaxer.infra.volta.auth.AuthState.*;

/**
 * volta-auth-proxy の認証フロー定義。
 *
 * ── 今回の3つのバグが全て build() で検出される理由 ──
 *
 * 1. return_to 消失:
 *    LoginRedirectInit.produces(LoginRedirect) → SessionCreator.requires(LoginRedirect)
 *    → 間にある全パスで LoginRedirect が available であることが保証される。
 *    もし fetch('/auth/passkey/start') が LoginRedirect を渡さなくても、
 *    SessionCreator が LoginRedirect.returnTo() を取得できる。
 *
 * 2. scheme (http/https) 問題:
 *    RequestOrigin が initiallyAvailable で、全パスで参照可能。
 *    RequestOrigin.fromForwardAuth() が BASE_URL から scheme を推定。
 *    FORCE_HTTPS / FORCE_SECURE_COOKIE 環境変数は不要。
 *
 * 3. Cookie Secure フラグ:
 *    SessionCookie.create() が RequestOrigin.scheme から判定。
 *    SessionCreator.requires(RequestOrigin) があるので scheme が消えない。
 *
 * ── stateDiagram (生成される Mermaid) ──
 *
 * stateDiagram-v2
 *     [*] --> UNAUTHENTICATED
 *     UNAUTHENTICATED --> LOGIN_REDIRECT : LoginRedirectInit
 *     LOGIN_REDIRECT --> LOGIN_PENDING
 *     LOGIN_PENDING --> CALLBACK_RECEIVED : [IdpCallbackGuard]
 *     CALLBACK_RECEIVED --> USER_RESOLVED : TokenExchange
 *     USER_RESOLVED --> SESSION_CREATED : MfaCheck[no_mfa] / SessionCreator
 *     USER_RESOLVED --> MFA_PENDING : MfaCheck[mfa_required]
 *     MFA_PENDING --> SESSION_CREATED : [MfaVerifyGuard] / SessionCreator
 *     SESSION_CREATED --> COMPLETE
 *     COMPLETE --> [*]
 *     FAILED --> [*]
 *     EXPIRED --> [*]
 */
public final class AuthFlowDefinition {
    private AuthFlowDefinition() {}

    /**
     * OIDC 認証フロー。
     *
     * 50行で認証フロー全体を定義。
     * 1800行の Main.java ハンドラは、この定義 + 個別の Processor に分解される。
     */
    public static FlowDefinition<AuthState> oidcFlow() {
        return Tramli.define("volta-auth-oidc", AuthState.class)
                .ttl(Duration.ofMinutes(10))
                .maxGuardRetries(3)

                // ForwardAuth リクエストから抽出されるデータ
                .initiallyAvailable(RequestOrigin.class, AuthConfig.class)

                // ── UNAUTHENTICATED → LOGIN_REDIRECT ──
                // return_to URL 確定 + ログインページ URL 生成
                .from(UNAUTHENTICATED).auto(LOGIN_REDIRECT, LOGIN_REDIRECT_INIT)

                // ── LOGIN_REDIRECT → LOGIN_PENDING ──
                // ブラウザにリダイレクト (Processor なし — Javalin が LoginRedirect を読む)
                .from(LOGIN_REDIRECT).auto(LOGIN_PENDING, noop("RedirectToLogin"))

                // ── LOGIN_PENDING → CALLBACK_RECEIVED ──
                // IdP からのコールバック到着 (External — ユーザー操作待ち)
                .from(LOGIN_PENDING).external(CALLBACK_RECEIVED, CALLBACK_GUARD)

                // ── CALLBACK_RECEIVED → USER_RESOLVED ──
                // トークン交換 + ユーザー情報取得
                .from(CALLBACK_RECEIVED).auto(USER_RESOLVED, TOKEN_EXCHANGE)

                // ── USER_RESOLVED → SESSION_CREATED or MFA_PENDING ──
                // MFA 要否で分岐
                .from(USER_RESOLVED).branch(MFA_CHECK)
                    .to(SESSION_CREATED, "no_mfa", SESSION_CREATOR)
                    .to(MFA_PENDING, "mfa_required")
                    .endBranch()

                // ── MFA_PENDING → SESSION_CREATED ──
                // MFA チャレンジ応答検証 (External)
                .from(MFA_PENDING).external(SESSION_CREATED, MFA_GUARD, SESSION_CREATOR)

                // ── SESSION_CREATED → COMPLETE ──
                // Cookie セット + return_to リダイレクト (Javalin が SessionCookie + FinalRedirect を読む)
                .from(SESSION_CREATED).auto(COMPLETE, noop("FinalRedirect"))

                // ── エラーハンドリング ──
                .onAnyError(FAILED)

                .build();  // ← ここで 8 項目の検証が走る
    }

    /**
     * データ変換なしで遷移だけ行う noop Processor。
     * LOGIN_REDIRECT → LOGIN_PENDING のように
     * 「Javalin ハンドラが FlowContext を読んでレスポンスを返す」ステップ用。
     */
    private static com.tramli.StateProcessor noop(String name) {
        return new com.tramli.StateProcessor() {
            @Override public String name() { return name; }
            @Override public java.util.Set<Class<?>> requires() { return java.util.Set.of(); }
            @Override public java.util.Set<Class<?>> produces() { return java.util.Set.of(); }
            @Override public void process(com.tramli.FlowContext ctx) { /* noop */ }
        };
    }

    // ── Mermaid 生成 (CI で使用) ──

    public static void main(String[] args) {
        var flow = oidcFlow();
        System.out.println(MermaidGenerator.generate(flow));
        System.out.println();
        System.out.println(MermaidGenerator.generateDataFlow(flow));
    }
}
