package com.volta.authproxy;

import java.util.Objects;

public record AppConfig(
        int port,
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        String baseUrl,
        String googleClientId,
        String googleClientSecret,
        String googleRedirectUri,
        String allowedRedirectDomains,
        boolean devMode,
        String serviceToken,
        String jwtIssuer,
        String jwtAudience,
        int jwtTtlSeconds,
        int sessionTtlSeconds,
        String jwtKeyEncryptionSecret,
        String appConfigPath,
        String supportContact,
        String sessionStore,
        String redisUrl,
        boolean allowSelfServiceTenant,
        boolean webhookEnabled,
        int webhookRetryMax,
        int webhookWorkerIntervalSeconds,
        String notificationChannel,
        String smtpHost,
        int smtpPort,
        String smtpUser,
        String smtpPassword,
        String smtpFrom,
        String sendgridApiKey,
        boolean samlSkipSignature,
        String auditSink,
        String kafkaBootstrapServers,
        String kafkaAuditTopic,
        String elasticsearchUrl,
        String stripeWebhookSecret
) {
    public static AppConfig fromEnv() {
        return new AppConfig(
                getInt("PORT", 7070),
                get("DB_HOST", "localhost"),
                getInt("DB_PORT", 54329),
                get("DB_NAME", "volta_auth"),
                get("DB_USER", "volta"),
                get("DB_PASSWORD", "volta"),
                get("BASE_URL", "http://localhost:7070"),
                get("GOOGLE_CLIENT_ID", ""),
                get("GOOGLE_CLIENT_SECRET", ""),
                get("GOOGLE_REDIRECT_URI", "http://localhost:7070/callback"),
                get("ALLOWED_REDIRECT_DOMAINS", "localhost,127.0.0.1"),
                getBoolean("DEV_MODE", false),
                get("VOLTA_SERVICE_TOKEN", ""),
                get("JWT_ISSUER", "volta-auth"),
                get("JWT_AUDIENCE", "volta-apps"),
                getInt("JWT_TTL_SECONDS", 300),
                getInt("SESSION_TTL_SECONDS", 28800),
                get("JWT_KEY_ENCRYPTION_SECRET", "dev-only-secret-change-me"),
                get("APP_CONFIG_PATH", "volta-config.yaml"),
                get("SUPPORT_CONTACT", "管理者にお問い合わせください"),
                get("SESSION_STORE", "postgres"),
                get("REDIS_URL", "redis://localhost:6379"),
                getBoolean("ALLOW_SELF_SERVICE_TENANT", true),
                getBoolean("WEBHOOK_ENABLED", false),
                getInt("WEBHOOK_RETRY_MAX", 3),
                getInt("WEBHOOK_WORKER_INTERVAL_SECONDS", 15),
                get("NOTIFICATION_CHANNEL", "none"),
                get("SMTP_HOST", ""),
                getInt("SMTP_PORT", 587),
                get("SMTP_USER", ""),
                get("SMTP_PASSWORD", ""),
                get("SMTP_FROM", "noreply@example.com"),
                get("SENDGRID_API_KEY", ""),
                getBoolean("SAML_SKIP_SIGNATURE", false),
                get("AUDIT_SINK", "postgres"),
                get("KAFKA_BOOTSTRAP_SERVERS", ""),
                get("KAFKA_AUDIT_TOPIC", "volta-audit"),
                get("ELASTICSEARCH_URL", ""),
                get("STRIPE_WEBHOOK_SECRET", "")
        );
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(dbHost, dbPort, dbName);
    }

    private static String get(String key, String defaultValue) {
        String value = System.getenv(key);
        return Objects.requireNonNullElse(value, defaultValue);
    }

    private static int getInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
