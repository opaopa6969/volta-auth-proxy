package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUTH-014 Phase 4 item 4: slug validation on the public API surface.
 *
 * <p>Full Flyway round-trip tests require a live PostgreSQL; they live in
 * {@code SchemaIsolationIntegrationTest} and run only when
 * {@code TEST_DB_URL} is set.
 */
class TenantSchemaManagerTest {

    @Test
    void createAndMigrateRejectsIllegalSlug() {
        TenantSchemaManager mgr = new TenantSchemaManager(null);
        assertThrows(IllegalArgumentException.class, () -> mgr.createAndMigrate("evil\"slug"));
        assertThrows(IllegalArgumentException.class, () -> mgr.createAndMigrate(""));
        assertThrows(IllegalArgumentException.class, () -> mgr.createAndMigrate(null));
    }

    @Test
    void dropSchemaRejectsIllegalSlug() {
        TenantSchemaManager mgr = new TenantSchemaManager(null);
        assertThrows(IllegalArgumentException.class, () -> mgr.dropSchema("a;DROP"));
        assertThrows(IllegalArgumentException.class, () -> mgr.dropSchema(null));
    }

    @Test
    void schemaExistsReturnsFalseForIllegalSlug() throws Exception {
        TenantSchemaManager mgr = new TenantSchemaManager(null);
        // illegal slug short-circuits BEFORE the datasource is used, so a
        // null DataSource is fine here.
        assertFalse(mgr.schemaExists("bad\""));
        assertFalse(mgr.schemaExists(null));
    }

    @Test
    void migrationReportAllOk() {
        TenantSchemaManager.MigrationReport ok = new TenantSchemaManager.MigrationReport(
                java.util.List.of("a", "b"), java.util.List.of());
        assertTrue(ok.allOk());

        TenantSchemaManager.MigrationReport bad = new TenantSchemaManager.MigrationReport(
                java.util.List.of("a"), java.util.List.of("b"));
        assertFalse(bad.allOk());
        assertEquals(java.util.List.of("b"), bad.failed());
    }
}
