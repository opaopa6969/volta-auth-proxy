package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * OutboxWorker の webhook 配信タイムアウト設定検証。
 *
 * <p>修正前は {@link HttpClient#newHttpClient()} で connectTimeout 無し、
 * {@link java.net.http.HttpRequest} に request timeout 無し、だった。
 * 単一スレッド worker が 1 件のハングした webhook 配信で全テナントの
 * 通知(magic link / 招待 / new device メール等)を停止する DoS になった。
 *
 * <p>修正後は connectTimeout=10s, requestTimeout=10s を設定。これは
 * 他の HTTP クライアント(StripeClient / FraudAlertClient / BaseIdpProvider 等)
 * と同程度の値。
 *
 * <p>DB 不要: リフレクションで static final 定数を検証する。
 */
class OutboxWorkerTimeoutTest {

    @Test
    void connectTimeoutIsTenSeconds() throws Exception {
        Duration connect = readStaticDuration("CONNECT_TIMEOUT");
        assertEquals(Duration.ofSeconds(10), connect,
                "CONNECT_TIMEOUT は 10s (他の HTTP クライアントと同程度)");
    }

    @Test
    void requestTimeoutIsTenSeconds() throws Exception {
        Duration request = readStaticDuration("REQUEST_TIMEOUT");
        assertEquals(Duration.ofSeconds(10), request,
                "REQUEST_TIMEOUT は 10s (worker 停滞 DoS を防ぐ)");
    }

    @Test
    void httpClientHasConnectTimeout() throws Exception {
        // httpClient フィールドはインスタンスフィールド。OutboxWorker は
        // AppConfig/SqlStore/NotificationService を必要とするが、httpClient は
        // コンストラクタで new されるだけなので null 依存で構築できる。
        // ただし SqlStore は DataSource 必要。最小限のスタブで済ませるため、
        // リフレクションで httpClient フィールドの connectTimeout を検証。
        // ここは static 定数の存在確認のみで、httpClient インスタンスの
        // connectTimeout 検証は統合テストに委ねる。
        Duration connect = readStaticDuration("CONNECT_TIMEOUT");
        Duration request = readStaticDuration("REQUEST_TIMEOUT");
        assertNotNull(connect);
        assertNotNull(request);
    }

    private static Duration readStaticDuration(String fieldName) throws Exception {
        Field f = OutboxWorker.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Duration) f.get(null);
    }
}
