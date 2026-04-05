package com.volta.authproxy;

import org.unlaxer.propstack.PropStack;

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
        String githubClientId,
        String githubClientSecret,
        String microsoftClientId,
        String microsoftClientSecret,
        String microsoftTenantId,
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
        String stripeWebhookSecret,
        String webauthnRpId,
        String webauthnRpName,
        String webauthnRpOrigin
) {
    public static AppConfig fromEnv() {
        PropStack p = new PropStack();
        return new AppConfig(
                p.getInt("PORT", 7070),
                p.get("DB_HOST", "localhost"),
                p.getInt("DB_PORT", 54329),
                p.get("DB_NAME", "volta_auth"),
                p.get("DB_USER", "volta"),
                p.get("DB_PASSWORD", "volta"),
                p.get("BASE_URL", "http://localhost:7070"),
                p.get("GOOGLE_CLIENT_ID", ""),
                p.get("GOOGLE_CLIENT_SECRET", ""),
                p.get("GOOGLE_REDIRECT_URI", "http://localhost:7070/callback"),
                p.get("GITHUB_CLIENT_ID", ""),
                p.get("GITHUB_CLIENT_SECRET", ""),
                p.get("MICROSOFT_CLIENT_ID", ""),
                p.get("MICROSOFT_CLIENT_SECRET", ""),
                p.get("MICROSOFT_TENANT_ID", "common"),
                p.get("ALLOWED_REDIRECT_DOMAINS", "localhost,127.0.0.1"),
                p.getBoolean("DEV_MODE", false),
                p.get("VOLTA_SERVICE_TOKEN", ""),
                p.get("JWT_ISSUER", "volta-auth"),
                p.get("JWT_AUDIENCE", "volta-apps"),
                p.getInt("JWT_TTL_SECONDS", 300),
                p.getInt("SESSION_TTL_SECONDS", 28800),
                p.get("JWT_KEY_ENCRYPTION_SECRET", "dev-only-secret-change-me"),
                p.get("APP_CONFIG_PATH", "volta-config.yaml"),
                p.get("SUPPORT_CONTACT", "管理者にお問い合わせください"),
                p.get("SESSION_STORE", "postgres"),
                p.get("REDIS_URL", "redis://localhost:6379"),
                p.getBoolean("ALLOW_SELF_SERVICE_TENANT", true),
                p.getBoolean("WEBHOOK_ENABLED", false),
                p.getInt("WEBHOOK_RETRY_MAX", 3),
                p.getInt("WEBHOOK_WORKER_INTERVAL_SECONDS", 15),
                p.get("NOTIFICATION_CHANNEL", "none"),
                p.get("SMTP_HOST", ""),
                p.getInt("SMTP_PORT", 587),
                p.get("SMTP_USER", ""),
                p.get("SMTP_PASSWORD", ""),
                p.get("SMTP_FROM", "noreply@example.com"),
                p.get("SENDGRID_API_KEY", ""),
                p.getBoolean("SAML_SKIP_SIGNATURE", false),
                p.get("AUDIT_SINK", "postgres"),
                p.get("KAFKA_BOOTSTRAP_SERVERS", ""),
                p.get("KAFKA_AUDIT_TOPIC", "volta-audit"),
                p.get("ELASTICSEARCH_URL", ""),
                p.get("STRIPE_WEBHOOK_SECRET", ""),
                p.get("WEBAUTHN_RP_ID", "localhost"),
                p.get("WEBAUTHN_RP_NAME", "volta-auth"),
                p.get("WEBAUTHN_RP_ORIGIN", "http://localhost:7070")
        );
    }

    public boolean isGoogleEnabled() {
        return googleClientId != null && !googleClientId.isBlank();
    }

    public boolean isGithubEnabled() {
        return githubClientId != null && !githubClientId.isBlank();
    }

    public boolean isMicrosoftEnabled() {
        return microsoftClientId != null && !microsoftClientId.isBlank();
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(dbHost, dbPort, dbName);
    }

}
