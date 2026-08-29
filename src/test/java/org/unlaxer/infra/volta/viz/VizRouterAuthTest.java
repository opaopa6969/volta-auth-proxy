package org.unlaxer.infra.volta.viz;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.unlaxer.infra.volta.AppConfigTestFactory;
import org.unlaxer.infra.volta.AuthService;
import org.unlaxer.infra.volta.PolicyEngine;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #72: /viz/auth/stream (SSE) と /viz/ws (WebSocket) が未認証接続を
 * 拒否することを検証する。
 *
 * <p>VizRouter に app.before で authService.authenticate + policy.enforceMinRole
 * (ADMIN) を追加し、未認証は 401 で拒否する。SSE は HTTP upgrade リクエストが
 * before で拒否されるため 401 になる。
 */
class VizRouterAuthTest {

    private Javalin app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void sseStreamRejectsUnauthenticatedConnection() throws Exception {
        app = startVizRouterWithSse();
        int port = app.port();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/viz/auth/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, res.statusCode(),
                "未認証の SSE 接続は 401 で拒否されるべき");
    }

    @Test
    void wsUpgradeRejectsUnauthenticatedConnection() throws Exception {
        app = startVizRouterWithSseAndWs();
        int port = app.port();

        HttpClient client = HttpClient.newHttpClient();
        // WebSocket upgrade 自体は HTTP レベルで成功するが、onConnect で
        // 認証チェックに失敗すると closeSession(4001) で即座に閉じられる。
        // この close を検知できれば認証チェックが効いている証拠。
        var closed = new java.util.concurrent.CompletableFuture<Boolean>();
        java.net.http.WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://localhost:" + port + "/viz/ws"),
                        new java.net.http.WebSocket.Listener() {
                            @Override
                            public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket webSocket,
                                                                                    int statusCode, String reason) {
                                closed.complete(statusCode == 4001);
                                return null;
                            }
                        })
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        // close を待つ(5秒以内)
        Boolean wasClosedWithAuthError = closed.get(5, java.util.concurrent.TimeUnit.SECONDS);
        ws.abort();
        if (!wasClosedWithAuthError) {
            throw new AssertionError("未認証 WS 接続が 4001 で close されなかった — 認証チェックが効いていない");
        }
    }

    private Javalin startVizRouterWithSse() {
        return startVizRouter(false);
    }

    private Javalin startVizRouterWithSseAndWs() {
        return startVizRouter(true);
    }

    private Javalin startVizRouter(boolean withWs) {
        var config = AppConfigTestFactory.builder().build();
        var authService = new AuthService(config, null, null, null);
        var policy = PolicyEngine.defaultPolicy();
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // JedisPooled は遅延接続。subscribe は別スレッドで試みるが接続失敗で
        // WARNING ログを出すだけでテスト結果には影響しない。
        JedisPooled jedis = new JedisPooled("localhost", 6379);
        VizRouter router = new VizRouter(
                null, authService, policy, objectMapper,
                java.util.List.of(),
                jedis, "volta:auth:events",
                withWs ? "volta:viz:events" : null);
        Javalin app = Javalin.create().start(0);
        router.register(app);
        return app;
    }
}
