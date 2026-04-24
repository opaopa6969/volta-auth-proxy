package org.unlaxer.infra.volta;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * AUTH-014 Phase 4 item 4: transaction-scoped schema context helper.
 *
 * <p>Runs the supplied body inside a transaction with
 * {@code SET LOCAL search_path = "tenant_<slug>", public}. Because
 * {@code SET LOCAL} is txn-scoped, commit/rollback automatically restores
 * the connection's search_path — no connection-return leak.
 *
 * <p>Usage:
 * <pre>{@code
 *   UserRecord user = TxScope.withTenantSchema(ds, "acme", conn -> {
 *       // queries here see tenant_acme tables first, then public
 *       return sqlStore.loadSession(conn, sessionId);
 *   });
 * }</pre>
 *
 * <p>Why a helper rather than a thread-local:
 * <ul>
 *   <li>Javalin runs on virtual threads where thread-locals do not travel
 *       with the request reliably — an explicit Connection is safer.</li>
 *   <li>A future move to async/reactive stays straightforward.</li>
 * </ul>
 */
public final class TxScope {

    // Slug characters accepted by {@code tenants.slug}. Restricted here so
    // identifier injection through the SET LOCAL concatenation is impossible.
    private static final Pattern SLUG_ALLOWED = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private TxScope() {
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlAction {
        void apply(Connection connection) throws SQLException;
    }

    /** Run {@code body} with search_path scoped to {@code tenant_<slug>}, public. */
    public static <T> T withTenantSchema(DataSource ds, String slug, SqlFunction<T> body) throws SQLException {
        if (slug == null || !SLUG_ALLOWED.matcher(slug).matches()) {
            throw new IllegalArgumentException("illegal tenant slug: " + slug);
        }
        String schema = "tenant_" + slug;
        try (Connection conn = ds.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    // quoteIdent: doubling any embedded quote is redundant because the
                    // slug regex already forbids `"` but we keep it for belt-and-braces.
                    st.execute("SET LOCAL search_path = \"" + schema.replace("\"", "\"\"") + "\", public");
                }
                T result = body.apply(conn);
                conn.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) { /* best-effort */ }
                throw e;
            } finally {
                // Restore autoCommit before Hikari returns the connection to the pool.
                try { conn.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) { /* best-effort */ }
            }
        }
    }

    /** Void variant for commands that do not produce a return value. */
    public static void withTenantSchemaVoid(DataSource ds, String slug, SqlAction body) throws SQLException {
        withTenantSchema(ds, slug, conn -> { body.apply(conn); return null; });
    }

    /** Public: is this slug acceptable for {@code SET LOCAL search_path}? */
    public static boolean isLegalSlug(String slug) {
        return slug != null && SLUG_ALLOWED.matcher(slug).matches();
    }
}
