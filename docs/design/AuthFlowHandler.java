package org.unlaxer.infra.volta.auth;

import com.tramli.*;
import org.unlaxer.infra.volta.auth.AuthData.*;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * Javalin ハンドラと tramli FlowEngine の統合。
 *
 * 既存の Main.java の /auth/verify, /auth/callback, /mfa/challenge 等の
 * 各ハンドラが、FlowEngine の startFlow / resumeAndExecute に集約される。
 *
 * ── 使い方 ──
 *
 * {@code
 * var authFlow = AuthFlowDefinition.oidcFlow();
 * var engine = Tramli.engine(new JdbcFlowStore(dataSource));
 * var handler = new AuthFlowHandler(engine, authFlow, config);
 *
 * app.get("/auth/verify", handler::verify);
 * app.get("/auth/callback", handler::callback);
 * app.post("/mfa/challenge", handler::mfaChallenge);
 * }
 *
 * 3つのエンドポイントだけ。1800行が100行に。
 */
public class AuthFlowHandler {
    private final FlowEngine engine;
    private final FlowDefinition<AuthState> flowDef;
    private final AuthConfig config;

    public AuthFlowHandler(FlowEngine engine, FlowDefinition<AuthState> flowDef, AuthConfig config) {
        this.engine = engine;
        this.flowDef = flowDef;
        this.config = config;
    }

    /**
     * Traefik ForwardAuth エンドポイント。
     *
     * セッション Cookie があれば 200 + X-Volta-* ヘッダー。
     * なければ新規フローを開始 → LoginRedirect の loginUrl に 302。
     *
     * 既存 Main.java の L370-L395 に相当。
     */
    public void verify(/* Javalin ctx */ Object javalinCtx) {
        // Pseudo-code — Javalin の型を直接参照しない (tramli は HTTP フレームワーク非依存)

        // 1. セッション Cookie チェック (フロー外)
        //    Cookie がある場合は FlowEngine を経由しない — セッション検証だけ。
        //    これは tramli のフローの「前」の話。
        // String sessionId = ctx.cookie("__volta_session");
        // if (sessionId != null && sessionStore.isValid(sessionId)) {
        //     ctx.header("X-Volta-Email", session.email());
        //     ctx.header("X-Volta-Roles", session.roles());
        //     ctx.status(200);
        //     return;
        // }

        // 2. セッションなし → フロー開始
        //    RequestOrigin を ForwardAuth ヘッダーから構築
        // var origin = RequestOrigin.fromForwardAuth(
        //         ctx.header("X-Forwarded-Proto"),
        //         ctx.header("X-Forwarded-Host"),
        //         ctx.header("X-Forwarded-Uri"),
        //         config.baseUrl());

        // 3. FlowEngine.startFlow() — UNAUTHENTICATED → auto-chain → LOGIN_PENDING で停止
        // var flow = engine.startFlow(flowDef, null,
        //         Map.of(RequestOrigin.class, origin, AuthConfig.class, config));

        // 4. auto-chain 後の FlowContext から LoginRedirect を取得してリダイレクト
        // var loginRedirect = flow.context().get(LoginRedirect.class);
        // ctx.redirect(loginRedirect.loginUrl());
        //
        // → return_to は LoginRedirect.returnTo() に保持されている。
        //   FlowContext がセッションに紐づくので、callback 時にも参照可能。
    }

    /**
     * IdP コールバックエンドポイント。
     *
     * IdP からリダイレクトされてくる。code + state パラメータを検証し、
     * トークン交換 → ユーザー解決 → MFA 判定 → セッション作成。
     *
     * 既存 Main.java の L400-L450 に相当。
     */
    public void callback(/* Javalin ctx */ Object javalinCtx) {
        // 1. フローを復元 (state パラメータからフロー ID を取得)
        // String flowId = ctx.queryParam("state");
        // var flow = engine.loadForUpdate(flowId, flowDef);

        // 2. コールバックデータを FlowContext に注入
        // flow.context().put(IdpCallback.class, new IdpCallback(
        //         ctx.queryParam("code"),
        //         ctx.queryParam("state"),
        //         null));

        // 3. resumeAndExecute() — LOGIN_PENDING → auto-chain
        //    IdpCallbackGuard → CALLBACK_RECEIVED → TokenExchange → USER_RESOLVED
        //    → MfaCheck (branch) → SESSION_CREATED or MFA_PENDING
        // flow = engine.resumeAndExecute(flowId, flowDef);

        // 4. 結果に応じてレスポンス
        // if (flow.currentState() == AuthState.SESSION_CREATED
        //         || flow.currentState() == AuthState.COMPLETE) {
        //     // セッション Cookie をセット
        //     var cookie = flow.context().get(SessionCookie.class);
        //     ctx.cookie("__volta_session", cookie.sessionId(),
        //             cookie.maxAge(), cookie.secure(), cookie.domain());
        //     // return_to にリダイレクト
        //     var redirect = flow.context().get(FinalRedirect.class);
        //     ctx.redirect(redirect.url());
        //
        // } else if (flow.currentState() == AuthState.MFA_PENDING) {
        //     // MFA チャレンジページにリダイレクト
        //     ctx.redirect(config.baseUrl() + "/mfa/challenge?flow_id=" + flowId);
        //
        // } else {
        //     ctx.status(401).result("Authentication failed");
        // }
    }

    /**
     * MFA チャレンジ応答エンドポイント。
     *
     * 既存 Main.java の MFA 関連ハンドラに相当。
     */
    public void mfaChallenge(/* Javalin ctx */ Object javalinCtx) {
        // 1. フロー復元
        // String flowId = ctx.formParam("flow_id");

        // 2. MFA 結果を FlowContext に注入
        // flow.context().put(MfaResult.class, new MfaResult(
        //         verifyTotp(ctx.formParam("code")),
        //         "TOTP"));

        // 3. resumeAndExecute() — MFA_PENDING → MfaVerifyGuard → SESSION_CREATED → COMPLETE
        // flow = engine.resumeAndExecute(flowId, flowDef);

        // 4. セッション Cookie + リダイレクト (callback と同じ)
    }
}
