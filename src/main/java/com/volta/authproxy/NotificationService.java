package com.volta.authproxy;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

interface NotificationService {
    void sendInvitationEmail(String to, String inviteLink, String tenantName, String role, String inviterName);

    static NotificationService create(AppConfig config) {
        return switch (config.notificationChannel().toLowerCase()) {
            case "smtp" -> new SmtpNotificationService(config);
            case "sendgrid" -> new SendGridNotificationService(config);
            default -> (to, inviteLink, tenantName, role, inviterName) -> {
            };
        };
    }
}

final class SmtpNotificationService implements NotificationService {
    private final AppConfig config;

    SmtpNotificationService(AppConfig config) {
        this.config = config;
    }

    @Override
    public void sendInvitationEmail(String to, String inviteLink, String tenantName, String role, String inviterName) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", config.smtpHost());
            props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
            props.put("mail.smtp.auth", !config.smtpUser().isBlank());
            props.put("mail.smtp.starttls.enable", "true");
            Session session;
            if (!config.smtpUser().isBlank()) {
                session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(config.smtpUser(), config.smtpPassword());
                    }
                });
            } else {
                session = Session.getInstance(props);
            }
            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.smtpFrom()));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject("招待: " + tenantName + " に参加");
            msg.setText("""
                    %s さんから %s に招待されました。
                    Role: %s
                    以下のリンクから参加してください:
                    %s
                    """.formatted(inviterName == null ? "メンバー" : inviterName, tenantName, role, inviteLink));
            Transport.send(msg);
        } catch (Exception e) {
            throw new RuntimeException("SMTP send failed: " + e.getMessage(), e);
        }
    }
}

final class SendGridNotificationService implements NotificationService {
    private final AppConfig config;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    SendGridNotificationService(AppConfig config) {
        this.config = config;
    }

    @Override
    public void sendInvitationEmail(String to, String inviteLink, String tenantName, String role, String inviterName) {
        if (to == null || to.isBlank() || config.sendgridApiKey().isBlank()) {
            return;
        }
        String text = "%s さんから %s に招待されました。\nRole: %s\n参加リンク: %s"
                .formatted(inviterName == null ? "メンバー" : inviterName, tenantName, role, inviteLink);
        String payload = """
                {"personalizations":[{"to":[{"email":"%s"}]}],
                 "from":{"email":"%s"},
                 "subject":"招待: %s に参加",
                 "content":[{"type":"text/plain","value":"%s"}]}
                """.formatted(
                to.replace("\"", ""),
                config.smtpFrom().replace("\"", ""),
                tenantName.replace("\"", ""),
                text.replace("\"", "\\\"")
        );
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.sendgrid.com/v3/mail/send"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + config.sendgridApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("SendGrid returned " + resp.statusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("SendGrid send failed: " + e.getMessage(), e);
        }
    }
}
