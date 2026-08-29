package org.unlaxer.infra.volta;

import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.InternalRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * クロステナント IDOR 修正の回帰テスト(ルーティング登録レベル)。
 *
 * <p>修正対象のエンドポイントがリファクタ後も登録されていることを検証。
 * ハンドラ本体は実行されないため、ApiRouter は依存を null で構築できる
 * (既存 {@code SessionRouteAliasTest} と同一の DB 不要テスト方針)。
 *
 * <p>修正内容:
 * <ul>
 *   <li>/api/v1/admin/sessions に tenantFilter を適用(#39 と同一パターン)
 *   <li>/api/v1/users/{id} にメンバーシップ検証(PII 保護)
 *   <li>/api/v1/users/{userId}/export と /data にメンバーシップ検証
 *   <li>/api/v1/tenants/{tenantId}/members/{userId}/mfa にメンバーシップ検証
 *   <li>/api/v1/admin/users/{userId}/known-devices にメンバーシップ検証
 * </ul>
 */
class ApiRouterTenantScopeRegressionTest {

    private InternalRouter registerRoutes() {
        ApiRouter router = new ApiRouter(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        Javalin app = Javalin.create();
        router.register(app);
        return app.unsafeConfig().pvt.internalRouter;
    }

    @Test
    void adminSessionsRouteStillRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.GET, "/api/v1/admin/sessions"),
                "GET /api/v1/admin/sessions は tenantFilter 追加後も登録されているべき");
    }

    @Test
    void userLookupRouteStillRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.GET, "/api/v1/users/{id}"),
                "GET /api/v1/users/{id} はメンバーシップ検証追加後も登録されているべき");
    }

    @Test
    void userExportAndDeleteRoutesStillRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.POST, "/api/v1/users/{userId}/export"),
                "POST /api/v1/users/{userId}/export はメンバーシップ検証追加後も登録されているべき");
        assertTrue(r.hasHttpHandlerEntry(HandlerType.DELETE, "/api/v1/users/{userId}/data"),
                "DELETE /api/v1/users/{userId}/data はメンバーシップ検証追加後も登録されているべき");
    }

    @Test
    void adminMfaResetRouteStillRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.DELETE, "/api/v1/tenants/{tenantId}/members/{userId}/mfa"),
                "DELETE /api/v1/tenants/{tenantId}/members/{userId}/mfa はメンバーシップ検証追加後も登録されているべき");
    }

    @Test
    void adminKnownDevicesResetRouteStillRegistered() {
        InternalRouter r = registerRoutes();
        assertTrue(r.hasHttpHandlerEntry(HandlerType.DELETE, "/api/v1/admin/users/{userId}/known-devices"),
                "DELETE /api/v1/admin/users/{userId}/known-devices はメンバーシップ検証追加後も登録されているべき");
    }
}
