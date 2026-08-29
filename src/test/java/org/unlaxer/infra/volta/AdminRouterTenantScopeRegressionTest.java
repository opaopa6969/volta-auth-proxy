package org.unlaxer.infra.volta;

import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.InternalRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdminRouter のクロステナント IDOR 修正の回帰テスト(ルーティング登録レベル)。
 *
 * <p>修正対象のエンドポイントがリファクタ後も登録されていることを検証。
 * ハンドラ本体は実行されないため、AdminRouter は依存を null で構築できる
 * (既存 {@code SessionRouteAliasTest} と同一の DB 不要テスト方針)。
 *
 * <p>修正内容:
 * <ul>
 *   <li>/admin/sessions をテナントスコープ化(listActiveSessionsForTenant)
 *   <li>DELETE /admin/sessions/{id} で対象セッションの所有権検証
 * </ul>
 */
class AdminRouterTenantScopeRegressionTest {

    private InternalRouter registerRoutes() {
        AdminRouter router = new AdminRouter(null, null, null, null, null);
        Javalin app = Javalin.create();
        router.register(app);
        return app.unsafeConfig().pvt.internalRouter;
    }

    @Test
    void adminSessionsListRouteStillRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.GET, "/admin/sessions"),
                "GET /admin/sessions はテナントスコープ化後も登録されているべき");
    }

    @Test
    void adminSessionRevokeRouteStillRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.DELETE, "/admin/sessions/{id}"),
                "DELETE /admin/sessions/{id} は所有権検証追加後も登録されているべき");
    }
}
