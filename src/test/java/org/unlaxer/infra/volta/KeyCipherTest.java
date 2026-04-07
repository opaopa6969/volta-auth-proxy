package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeyCipherTest {
    @Test
    void encryptDecryptRoundTrip() {
        KeyCipher cipher = new KeyCipher("unit-test-secret");
        String plain = "hello-world";
        String encrypted = cipher.encrypt(plain);
        assertNotEquals(plain, encrypted);
        assertTrue(encrypted.startsWith("v1:"));
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void decryptSupportsLegacyPlainValue() {
        KeyCipher cipher = new KeyCipher("unit-test-secret");
        assertEquals("legacy", cipher.decrypt("legacy"));
    }
}
