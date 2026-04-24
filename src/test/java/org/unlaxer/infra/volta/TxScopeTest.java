package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AUTH-014 Phase 4 item 4: slug validation guards SET LOCAL injection. */
class TxScopeTest {

    @Test
    void acceptsTypicalSlugs() {
        assertTrue(TxScope.isLegalSlug("acme"));
        assertTrue(TxScope.isLegalSlug("my-team"));
        assertTrue(TxScope.isLegalSlug("team_1"));
        assertTrue(TxScope.isLegalSlug("a"));
        assertTrue(TxScope.isLegalSlug("abcdefghij0123456789"));
    }

    @Test
    void rejectsInjectionAttempts() {
        assertFalse(TxScope.isLegalSlug("acme\"; DROP SCHEMA public--"));
        assertFalse(TxScope.isLegalSlug("acme\""));
        assertFalse(TxScope.isLegalSlug("acme'"));
        assertFalse(TxScope.isLegalSlug("acme;"));
        assertFalse(TxScope.isLegalSlug("acme space"));
        assertFalse(TxScope.isLegalSlug("-leading-dash"));
        assertFalse(TxScope.isLegalSlug(""));
        assertFalse(TxScope.isLegalSlug(null));
    }

    @Test
    void rejectsUpperCase() {
        // slug column constraint is lowercase; enforcing here too keeps
        // schema names deterministic.
        assertFalse(TxScope.isLegalSlug("ACME"));
        assertFalse(TxScope.isLegalSlug("MyTeam"));
    }

    @Test
    void rejectsOverlongSlugs() {
        String tooLong = "a".repeat(65);
        assertFalse(TxScope.isLegalSlug(tooLong));
    }

    @Test
    void illegalSlugThrows() {
        // null body — we only care that the slug check runs before the
        // data source is touched.
        assertThrows(IllegalArgumentException.class,
                () -> TxScope.withTenantSchema(null, "bad\"slug", conn -> null));
        assertThrows(IllegalArgumentException.class,
                () -> TxScope.withTenantSchemaVoid(null, "", conn -> { }));
    }
}
