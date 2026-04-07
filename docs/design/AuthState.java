package org.unlaxer.infra.volta.auth;

import com.tramli.FlowState;

/**
 * volta-auth-proxy の認証フロー全ステート。
 *
 * 遷移図は {@code MermaidGenerator.generate(AuthFlowDefinition.oidcFlow())} で生成可能。
 */
public enum AuthState implements FlowState {
    /** リクエスト到着。セッション Cookie なし。 */
    UNAUTHENTICATED(false, true),

    /** return_to + scheme を確定し、セッションに保存済み。IdP リダイレクト待ち。 */
    LOGIN_REDIRECT(false, false),

    /** IdP ログインページ表示中。ユーザー操作待ち (External)。 */
    LOGIN_PENDING(false, false),

    /** IdP からコールバック到着。code + state パラメータ検証待ち (External)。 */
    CALLBACK_RECEIVED(false, false),

    /** トークン交換完了。ユーザー情報取得済み。 */
    USER_RESOLVED(false, false),

    /** MFA チャレンジ表示中。ユーザー応答待ち (External)。 */
    MFA_PENDING(false, false),

    /** セッション Cookie 発行完了。return_to へリダイレクト。 */
    SESSION_CREATED(false, false),

    /** 認証完了 (terminal)。 */
    COMPLETE(true, false),

    /** 認証失敗 (terminal)。 */
    FAILED(true, false),

    /** TTL 切れ (terminal)。 */
    EXPIRED(true, false);

    private final boolean terminal, initial;

    AuthState(boolean terminal, boolean initial) {
        this.terminal = terminal;
        this.initial = initial;
    }

    @Override public boolean isTerminal() { return terminal; }
    @Override public boolean isInitial() { return initial; }
}
