package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class KeyCipherTest {
    private static final String SECRET = "unit-test-secret";

    @Test
    void encryptDecryptRoundTrip() {
        KeyCipher cipher = new KeyCipher(SECRET);
        String plain = "hello-world";
        String encrypted = cipher.encrypt(plain);
        assertNotEquals(plain, encrypted);
        // #43: 新規暗号化は v2（600k 反復 / デプロイ固有 salt）
        assertTrue(encrypted.startsWith("v2:"), "new values must be written as v2: " + encrypted);
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void decryptSupportsLegacyPlainValue() {
        KeyCipher cipher = new KeyCipher(SECRET);
        assertEquals("legacy", cipher.decrypt("legacy"));
    }

    /**
     * #43: 鍵導出を変えたので、**既存の v1 暗号文が読めなくなると本番のデータが
     * 復号不能になる**。旧方式（固定 salt / 100k 反復）で作った値を、現行コードが
     * 読めることを固定する。
     */
    @Test
    void decryptsLegacyV1CiphertextWithOldKeyDerivation() throws Exception {
        String plain = "legacy-secret-value";
        String v1 = encryptAsV1(SECRET, plain);
        assertTrue(v1.startsWith("v1:"));

        KeyCipher cipher = new KeyCipher(SECRET);
        assertEquals(plain, cipher.decrypt(v1), "v1 ciphertext must still be readable");
    }

    /** salt がデプロイ固有になっているか — secret が違えば鍵も違う。 */
    @Test
    void differentSecretsCannotDecryptEachOther() {
        String encrypted = new KeyCipher(SECRET).encrypt("payload");
        KeyCipher other = new KeyCipher("another-secret");
        assertThrows(RuntimeException.class, () -> other.decrypt(encrypted));
    }

    /** IV はランダム — 同じ平文でも毎回違う暗号文になる。 */
    @Test
    void sameplaintextProducesDifferentCiphertext() {
        KeyCipher cipher = new KeyCipher(SECRET);
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
    }

    // ── v1 形式の暗号文を作る（旧実装の再現。テスト専用） ──────────────
    private static String encryptAsV1(String secret, String plain) throws Exception {
        byte[] salt = "volta-key-cipher-v2".getBytes(StandardCharsets.UTF_8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] key = factory.generateSecret(
                new PBEKeySpec(secret.toCharArray(), salt, 100_000, 256)).getEncoded();
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder b64 = Base64.getEncoder();
        return "v1:" + b64.encodeToString(iv) + ":" + b64.encodeToString(encrypted);
    }
}
