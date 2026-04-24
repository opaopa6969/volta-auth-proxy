package org.unlaxer.infra.volta;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AUTH-014 Phase 4 item 4: create / migrate / drop tenant-owned schemas.
 *
 * <p>Each tenant with {@code isolation='schema'} owns a PostgreSQL schema
 * {@code tenant_<slug>} containing the 11 tenant-owned tables from
 * {@code db/migration/tenant/V*.sql}. Flyway is run per schema with its
 * own {@code flyway_schema_history} table isolated inside that schema, so
 * tenant migrations cannot interfere with the global {@code public.flyway_schema_history}.
 *
 * <p>Boot-time behaviour: {@link #migrateAllActive(DataSource)} enumerates
 * every tenant in {@code public.tenants} where {@code is_active=true} AND
 * {@code isolation='schema'}, ensures the schema exists, and runs Flyway
 * migrate against it. One tenant's failure is logged but does not abort
 * the others — the spec's "one tenant's migration failure does not stop
 * the world" rule.
 */
public final class TenantSchemaManager {

    private static final Logger LOGGER = Logger.getLogger(TenantSchemaManager.class.getName());
    private static final String TENANT_MIGRATION_LOCATION = "classpath:db/migration/tenant";

    private final DataSource dataSource;

    public TenantSchemaManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Create schema + run tenant migration. Idempotent. */
    public void createAndMigrate(String slug) throws SQLException {
        String schema = schemaName(slug);
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            // double-quote the schema identifier to match the SET LOCAL form in TxScope.
            st.executeUpdate("CREATE SCHEMA IF NOT EXISTS \"" + schema.replace("\"", "\"\"") + "\"");
        }
        runFlyway(schema);
    }

    /** Run Flyway against every active schema-isolated tenant. One failure does not abort the rest. */
    public MigrationReport migrateAllActive(DataSource ds) throws SQLException {
        List<String> slugs = listActiveSchemaTenants(ds);
        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String slug : slugs) {
            try {
                createAndMigrate(slug);
                succeeded.add(slug);
            } catch (RuntimeException | SQLException e) {
                LOGGER.log(Level.WARNING, "tenant migration failed for slug=" + slug, e);
                failed.add(slug);
            }
        }
        return new MigrationReport(succeeded, failed);
    }

    /**
     * DROP SCHEMA CASCADE. Destructive — used for GDPR tenant deletion or
     * cutover rollback. Caller must have already taken any backup required.
     */
    public void dropSchema(String slug) throws SQLException {
        if (!TxScope.isLegalSlug(slug)) {
            throw new IllegalArgumentException("illegal tenant slug: " + slug);
        }
        String schema = schemaName(slug);
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DROP SCHEMA IF EXISTS \"" + schema.replace("\"", "\"\"") + "\" CASCADE");
        }
    }

    /** Does the schema currently exist? */
    public boolean schemaExists(String slug) throws SQLException {
        if (!TxScope.isLegalSlug(slug)) return false;
        String schema = schemaName(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ── internals ─────────────────────────────────────────────────────────

    private void runFlyway(String schema) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations(TENANT_MIGRATION_LOCATION)
                // Keep history co-located with tenant tables so `DROP SCHEMA`
                // removes it too — no orphaned rows in public.
                .table("flyway_schema_history")
                .outOfOrder(true)
                .load()
                .migrate();
    }

    private List<String> listActiveSchemaTenants(DataSource ds) throws SQLException {
        List<String> slugs = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT slug FROM tenants WHERE is_active = true AND isolation = 'schema'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) slugs.add(rs.getString(1));
        }
        return slugs;
    }

    private static String schemaName(String slug) {
        if (!TxScope.isLegalSlug(slug)) {
            throw new IllegalArgumentException("illegal tenant slug: " + slug);
        }
        return "tenant_" + slug;
    }

    public record MigrationReport(List<String> succeeded, List<String> failed) {
        public boolean allOk() { return failed.isEmpty(); }
    }
}
