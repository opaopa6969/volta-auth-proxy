package org.unlaxer.infra.volta;

import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.InternalRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * セッションAPIの統一prefixルートが登録されていることを検証する。
 *
 * 正準ルート:
 *   GET    /api/v1/users/me/sessions      (一覧)
 *   DELETE /api/v1/users/me/sessions/{id} (失効)
 *
 * これらは既存の /api/me/sessions, /api/me/sessions/{id} と同一の
 * {@code io.javalin.http.Handler} を共有する薄いエイリアスである。
 * 後方互換のため旧ルートも残っていることを併せて確認する。
 *
 * ルート登録時にハンドラのラムダ本体は実行されないため、ApiRouter は
 * 依存をすべて null で構築できる（DB 不要のルーティング登録テスト）。
 */
class SessionRouteAliasTest {

    private InternalRouter registerRoutes() {
        ApiRouter router = new ApiRouter(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        Javalin app = Javalin.create();
        router.register(app);
        return app.unsafeConfig().pvt.internalRouter;
    }

    @Test
    void canonicalSessionListRouteIsRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.GET, "/api/v1/users/me/sessions"),
                "統一prefixの自セッション一覧ルートが登録されているべき");
    }

    @Test
    void canonicalSessionRevokeRouteIsRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.DELETE, "/api/v1/users/me/sessions/{id}"),
                "統一prefixの自セッション失効ルートが登録されているべき");
    }

    @Test
    void legacySessionRoutesAreRetainedForBackwardCompat() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.GET, "/api/me/sessions"),
                "後方互換のため旧 /api/me/sessions 一覧ルートは残るべき");
        assertTrue(r.hasHttpHandlerEntry(HandlerType.DELETE, "/api/me/sessions/{id}"),
                "後方互換のため旧 /api/me/sessions/{id} 失効ルートは残るべき");
        assertTrue(r.hasHttpHandlerEntry(HandlerType.DELETE, "/auth/sessions/{id}"),
                "後方互換のため旧 /auth/sessions/{id} 失効ルートは残るべき");
    }
}
