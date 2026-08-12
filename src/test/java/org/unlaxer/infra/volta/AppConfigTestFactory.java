package org.unlaxer.infra.volta;

/**
 * Minimal AppConfig builder for tests. Provides sane dev defaults so that
 * only the fields under test need to be overridden via the fluent setters.
 *
 * <p>Production code MUST NOT use this — it is test-only and bypasses
 * {@link AppConfig#fromEnv()} so that unit tests don't depend on the
 * process environment.</p>
 */
public final class AppConfigTestFactory {
    private AppConfigTestFactory() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int port = 7070;
        private String dbHost = "localhost";
        private int dbPort = 5432;
        private String dbName = "volta_auth";
        private String dbUser = "volta";
        private String dbPassword = "volta";
        private String baseUrl = "http://localhost:7070";
        private String googleClientId = "";
        private String googleClientSecret = "";
        private String googleRedirectUri = "http://localhost:7070/callback";
        private String githubClientId = "";
        private String githubClientSecret = "";
        private String microsoftClientId = "";
        private String microsoftClientSecret = "";
        private String microsoftTenantId = "common";
        private String allowedRedirectDomains = "localhost,127.0.0.1";
        private boolean devMode = true;
        private String serviceToken = "test-token";
        private String jwtIssuer = "volta-auth";
        private String jwtAudience = "volta-apps";
        private int jwtTtlSeconds = 300;
        private int sessionTtlSeconds = 28800;
        private String jwtKeyEncryptionSecret = "test-jwt-secret";
        private String appConfigPath = "volta-config.yaml";
        private String supportContact = "ops@example.com";
        private String sessionStore = "postgres";
        private String redisUrl = "redis://localhost:6379";
        private boolean allowSelfServiceTenant = true;
        private boolean webhookEnabled = false;
        private int webhookRetryMax = 3;
        private int webhookWorkerIntervalSeconds = 15;
        private String notificationChannel = "none";
        private String smtpHost = "";
        private int smtpPort = 587;
        private String smtpUser = "";
        private String smtpPassword = "";
        private String smtpFrom = "noreply@example.com";
        private String sendgridApiKey = "";
        private boolean samlSkipSignature = false;
        private String auditSink = "postgres";
        private String kafkaBootstrapServers = "";
        private String kafkaAuditTopic = "volta-audit";
        private String elasticsearchUrl = "";
        private String stripeWebhookSecret = "";
        private String stripeSecretKey = "";
        private String stripeCheckoutSuccessUrl = "";
        private String stripeCheckoutCancelUrl = "";
        private String stripePortalReturnUrl = "";
        private String webauthnRpId = "localhost";
        private String webauthnRpName = "volta-auth";
        private String webauthnRpOrigin = "http://localhost:7070";
        private String appleClientId = "";
        private String appleClientSecret = "";
        private String linkedinClientId = "";
        private String linkedinClientSecret = "";
        private String authFlowHmacKey = "test-hmac-key";
        private String fraudAlertUrl = "";
        private String fraudAlertSiteId = "";
        private String fraudAlertApiKey = "";

        public Builder devMode(boolean v) { this.devMode = v; return this; }
        public Builder jwtKeyEncryptionSecret(String v) { this.jwtKeyEncryptionSecret = v; return this; }
        public Builder authFlowHmacKey(String v) { this.authFlowHmacKey = v; return this; }

        public AppConfig build() {
            return new AppConfig(
                    port, dbHost, dbPort, dbName, dbUser, dbPassword, baseUrl,
                    googleClientId, googleClientSecret, googleRedirectUri,
                    githubClientId, githubClientSecret,
                    microsoftClientId, microsoftClientSecret, microsoftTenantId,
                    allowedRedirectDomains, devMode, serviceToken, jwtIssuer,
                    jwtAudience, jwtTtlSeconds, sessionTtlSeconds, jwtKeyEncryptionSecret,
                    appConfigPath, supportContact, sessionStore, redisUrl,
                    allowSelfServiceTenant, webhookEnabled, webhookRetryMax,
                    webhookWorkerIntervalSeconds, notificationChannel, smtpHost,
                    smtpPort, smtpUser, smtpPassword, smtpFrom, sendgridApiKey,
                    samlSkipSignature, auditSink, kafkaBootstrapServers, kafkaAuditTopic,
                    elasticsearchUrl, stripeWebhookSecret, stripeSecretKey,
                    stripeCheckoutSuccessUrl, stripeCheckoutCancelUrl, stripePortalReturnUrl,
                    webauthnRpId, webauthnRpName, webauthnRpOrigin, appleClientId,
                    appleClientSecret, linkedinClientId, linkedinClientSecret,
                    authFlowHmacKey, fraudAlertUrl, fraudAlertSiteId, fraudAlertApiKey);
        }
    }
}
