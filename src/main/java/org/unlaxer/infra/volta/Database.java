package org.unlaxer.infra.volta;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

public final class Database {
    private Database() {
    }

    public static HikariDataSource createDataSource(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(2);
        hikari.setPoolName("volta-auth-proxy-pool");
        // AUTH-014 Phase 4 item 4: every new connection starts rooted in `public`
        // regardless of caller state, so an unscoped query never accidentally
        // lands inside a tenant_<slug> schema from a prior SET.
        hikari.setConnectionInitSql("SET search_path = public");
        return new HikariDataSource(hikari);
    }

    /**
     * Run Flyway against {@code db/migration/public}. Tenant-owned schemas
     * are migrated separately by {@link TenantSchemaManager}.
     */
    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/public")
                .outOfOrder(true)
                .load()
                .migrate();
    }
}
