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
    private final SecretKeySpec keySpec;

    // Fixed salt — deterministic key derivation for the same secret.
    // This is acceptable because the secret itself is high-entropy (from env config).
    private static final byte[] PBKDF2_SALT = "volta-key-cipher-v2".getBytes(StandardCharsets.UTF_8);
    private static final int PBKDF2_ITERATIONS = 100_000;

    public KeyCipher(String secret) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    secret.toCharArray(), PBKDF2_SALT, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = factory.generateSecret(spec).getEncoded();
            this.keySpec = new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "v1:" + b64(iv) + ":" + b64(encrypted);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            if (!encrypted.startsWith("v1:")) {
                LOG.log(System.Logger.Level.WARNING,
                        "KeyCipher.decrypt() received unencrypted value (legacy plaintext fallback). " +
                        "Re-encrypt this value to eliminate this warning.");
                return encrypted;
            }
            String[] parts = encrypted.split(":", 3);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] payload = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(payload);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
