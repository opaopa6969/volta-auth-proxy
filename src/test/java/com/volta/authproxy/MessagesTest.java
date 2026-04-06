package com.volta.authproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    @Test
    void jaLocaleLoadsJapanese() {
        var msg = Messages.forLocale("ja");
        assertEquals("ja", msg.locale());
        assertNotEquals("login.title", msg.get("login.title")); // resolved, not key
    }

    @Test
    void enLocaleLoadsEnglish() {
        var msg = Messages.forLocale("en");
        assertEquals("en", msg.locale());
        assertEquals("Login", msg.get("login.title"));
    }

    @Test
    void messageFormatWithArgs() {
        var msg = Messages.forLocale("en");
        String result = msg.get("invite.invited_by", "Alice", "Acme Corp");
        assertTrue(result.contains("Alice"));
        assertTrue(result.contains("Acme Corp"));
    }

    @Test
    void unknownKeyReturnsKey() {
        var msg = Messages.forLocale("ja");
        assertEquals("nonexistent.key", msg.get("nonexistent.key"));
    }

    @Test
    void unknownLocaleFallsBackToDefault() {
        var msg = Messages.forLocale("fr");
        assertEquals("ja", msg.locale()); // falls back to ja
        assertNotEquals("login.title", msg.get("login.title"));
    }

    @Test
    void resolveFromAcceptLanguage() {
        var msg = Messages.resolve(null, "en-US,en;q=0.9,ja;q=0.8");
        assertEquals("en", msg.locale());

        var msg2 = Messages.resolve(null, "ja,en-US;q=0.9");
        assertEquals("ja", msg2.locale());
    }

    @Test
    void resolveUserLocaleTakesPrecedence() {
        var msg = Messages.resolve("en", "ja,en;q=0.5");
        assertEquals("en", msg.locale());
    }

    @Test
    void threadLocalCurrentWorks() {
        Messages.setCurrent(Messages.forLocale("en"));
        assertEquals("en", Messages.current().locale());
        Messages.clearCurrent();
        assertEquals("ja", Messages.current().locale()); // fallback
    }

    @Test
    void supportedLocales() {
        assertTrue(Messages.supportedLocales().contains("ja"));
        assertTrue(Messages.supportedLocales().contains("en"));
    }
}
