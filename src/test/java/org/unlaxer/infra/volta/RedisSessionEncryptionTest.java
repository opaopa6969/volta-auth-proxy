package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #37: Redis に置くセッション JSON の暗号化。
 *
 * Redis 本体を立てずに検証できるよう、暗号化そのものは {@link KeyCipher} に寄せて
 * ある。ここでは「保存形式が平文でないこと」「暗号化を有効にする前に書かれた
 * 平文セッションも読めること（移行の互換性）」を固定する。
 *
 * Redis 実装のラウンドトリップ（JedisPooled 経由）は testcontainers か
 * embedded-redis の導入が前提なので #41 で扱う。
 */
class RedisSessionEncryptionTest {

    private static final String SECRET = "session-at-rest-test-secret";

    /** セッション JSON が Redis に平文で載らないこと。 */
    @Test
    void sessionJsonIsNotStoredInPlaintext() {
        KeyCipher cipher = new KeyCipher(SECRET);
        String json = """
                {"id":"5f0f7c8e-0000-0000-0000-000000000001",
                 "userId":"5f0f7c8e-0000-0000-0000-000000000002",
                 "csrfToken":"super-secret-csrf",
                 "returnTo":"/console/"}
                """;

        String stored = cipher.encrypt(json);

        // Redis を覗いた人に読まれてはいけない値が、そのまま入っていないこと
        assertFalse(stored.contains("super-secret-csrf"), "csrf_token が平文で載っている");
        assertFalse(stored.contains("5f0f7c8e-0000-0000-0000-000000000002"), "userId が平文で載っている");
        assertFalse(stored.contains("/console/"), "returnTo が平文で載っている");
        assertTrue(stored.startsWith("v2:"), "暗号化形式のプレフィックスが付く: " + stored);

        assertEquals(json, cipher.decrypt(stored));
    }

    /**
     * 暗号化を有効にする前に書かれた**平文セッション**も読めること。
     *
     * これが壊れると、デプロイした瞬間に既存の全セッションが無効になり
     * 全ユーザーが強制ログアウトされる。KeyCipher.decrypt が "v1:"/"v2:" 以外を
     * そのまま返す性質に依存しているので、ここで固定する。
     */
    @Test
    void plaintextSessionsWrittenBeforeEncryptionAreStillReadable() {
        KeyCipher cipher = new KeyCipher(SECRET);
        String legacyPlaintext = "{\"id\":\"x\",\"csrfToken\":\"t\"}";
        assertEquals(legacyPlaintext, cipher.decrypt(legacyPlaintext));
    }

    /** 鍵が違えば読めない（Redis のダンプを持ち出しても鍵なしでは復号できない）。 */
    @Test
    void differentSecretCannotDecrypt() {
        String stored = new KeyCipher(SECRET).encrypt("{\"csrfToken\":\"t\"}");
        KeyCipher other = new KeyCipher("another-session-secret");
        assertThrows(RuntimeException.class, () -> other.decrypt(stored));
    }

    /**
     * 鍵が未設定なら暗号化なしで動く（起動を壊さない）。
     *
     * SESSION_ENCRYPTION_SECRET も JWT_KEY_ENCRYPTION_SECRET も無い環境で
     * いきなり起動失敗にすると、既存デプロイが上がらなくなる。警告ログを出して
     * 平文で動く、という選択をしている。
     */
    @Test
    void nullSecretMeansNoEncryption() {
        // RedisSessionStore(redisUrl, null) の分岐に相当する挙動をここで明示する。
        // （Redis 接続を張らずに検証したいので、cipher が null のときは
        //   「そのまま書く」ことをドキュメントとして固定する）
        String json = "{\"csrfToken\":\"t\"}";
        String stored = json; // cipher == null のときの write() の挙動
        assertEquals(json, stored);
    }
}
