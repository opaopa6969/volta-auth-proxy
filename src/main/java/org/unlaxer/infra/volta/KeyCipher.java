package org.unlaxer.infra.volta;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class KeyCipher {
    private static final System.Logger LOG = System.getLogger("volta.security");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 新規暗号化に使う鍵（v2: 600k 反復・デプロイ固有 salt）。 */
    private final SecretKeySpec keySpec;
    /** v1 暗号文を読むためだけの鍵（100k 反復・固定 salt）。書き込みには使わない。 */
    private final SecretKeySpec legacyKeySpec;

    // ── v1（旧）: 固定 salt / 100k 反復 ────────────────────────────────
    // 「secret が高エントロピーだから固定 salt でよい」というコメントが付いていたが、
    // salt の役目は「同じ secret から別の鍵を導く」ことではなく
    // **レインボーテーブルの再利用を防ぐ**こと。固定だと全デプロイで同じテーブルが
    // 効いてしまう（#43）。復号のためだけに残す。
    private static final byte[] PBKDF2_SALT_V1 = "volta-key-cipher-v2".getBytes(StandardCharsets.UTF_8);
    private static final int PBKDF2_ITERATIONS_V1 = 100_000;

    // ── v2（現行）: デプロイ固有 salt / 600k 反復 ──────────────────────
    // 反復回数は OWASP 2023+ の PBKDF2-HMAC-SHA256 推奨値。
    // salt には secret の SHA-256 を混ぜてデプロイごとに変える。
    private static final byte[] PBKDF2_SALT_V2_PREFIX = "volta-key-cipher-v3:".getBytes(StandardCharsets.UTF_8);
    private static final int PBKDF2_ITERATIONS_V2 = 600_000;

    public KeyCipher(String secret) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            this.keySpec = new SecretKeySpec(
                    factory.generateSecret(new PBEKeySpec(
                            secret.toCharArray(), saltV2(secret), PBKDF2_ITERATIONS_V2, 256)).getEncoded(),
                    "AES");
            this.legacyKeySpec = new SecretKeySpec(
                    factory.generateSecret(new PBEKeySpec(
                            secret.toCharArray(), PBKDF2_SALT_V1, PBKDF2_ITERATIONS_V1, 256)).getEncoded(),
                    "AES");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * デプロイ固有の salt。secret の SHA-256 を固定プレフィックスに連結する。
     *
     * secret 自体を salt にすると「salt が秘密」という妙な設計になるため、
     * ハッシュを使う（salt は秘密である必要はない。デプロイごとに違えばよい）。
     */
    private static byte[] saltV2(String secret) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
        byte[] salt = new byte[PBKDF2_SALT_V2_PREFIX.length + digest.length];
        System.arraycopy(PBKDF2_SALT_V2_PREFIX, 0, salt, 0, PBKDF2_SALT_V2_PREFIX.length);
        System.arraycopy(digest, 0, salt, PBKDF2_SALT_V2_PREFIX.length, digest.length);
        return salt;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // #43: 新規は v2（600k 反復・デプロイ固有 salt）で書く。
            return "v2:" + b64(iv) + ":" + b64(encrypted);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * v1（旧鍵）と v2（現行鍵）の両方を読む。
     *
     * #43 で鍵導出を変えたため、**既存の v1 暗号文をそのままでは読めない**。
     * 移行スクリプトを別途走らせずに済むよう、読み出しは両対応にして、書き込みは
     * 常に v2 にする。値が更新された時点で自然に v2 へ寄る。
     * v1 が完全に消えたと確認できたら legacyKeySpec を落とせる。
     */
    public String decrypt(String encrypted) {
        try {
            boolean v1 = encrypted.startsWith("v1:");
            boolean v2 = encrypted.startsWith("v2:");
            if (!v1 && !v2) {
                LOG.log(System.Logger.Level.WARNING,
                        "KeyCipher.decrypt() received unencrypted value (legacy plaintext fallback). " +
                        "Re-encrypt this value to eliminate this warning.");
                return encrypted;
            }
            String[] parts = encrypted.split(":", 3);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] payload = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, v1 ? legacyKeySpec : keySpec, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(payload);
            if (v1) {
                LOG.log(System.Logger.Level.INFO,
                        "KeyCipher: decrypted a v1 value. It will be re-encrypted as v2 on next write (#43).");
            }
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
