package org.unlaxer.infra.volta;

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

public interface NotificationService {
    void sendInvitationEmail(String to, String inviteLink, String tenantName, String role, String inviterName);
    void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp);
    void sendMagicLinkEmail(String to, String magicLink);

    /**
     * AUTH-004-v2: extended variant that includes a one-click revoke URL.
     * Default implementation ignores the URL and calls the legacy method so
     * existing overrides keep working; SMTP / SendGrid implementations
     * override this to embed the link.
     */
    default void sendNewDeviceEmail(String to, String displayName, String device, String ip,
                                    String timestamp, String revokeUrl) {
        sendNewDeviceEmail(to, displayName, device, ip, timestamp);
    }

    static NotificationService create(AppConfig config) {
        return switch (config.notificationChannel().toLowerCase()) {
            case "smtp" -> new SmtpNotificationService(config);
            case "sendgrid" -> new SendGridNotificationService(config);
            default -> new NotificationService() {
                @Override public void sendInvitationEmail(String to, String inviteLink, String tenantName, String role, String inviterName) {}
                @Override public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp) {}
                @Override public void sendMagicLinkEmail(String to, String magicLink) {}
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

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp) {
        sendNewDeviceEmail(to, displayName, device, ip, timestamp, null);
    }

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp, String revokeUrl) {
        if (to == null || to.isBlank()) return;
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
            msg.setSubject("[" + config.baseUrl().replaceAll("https?://", "") + "] 新しいデバイスからのログイン");
            // AUTH-004-v2: prepend a one-click "this wasn't me" link when available.
            String revokeBlock = (revokeUrl == null || revokeUrl.isBlank()) ? ""
                    : "\n心当たりがなければ以下からこのデバイスを取り消してください:\n  " + revokeUrl + "\n";
            msg.setText("""
                    %s さん、

                    新しいデバイスからのログインがありました。

                      デバイス: %s
                      IP: %s
                      日時: %s
                    %s
                    セッション確認: %s/settings/sessions
                    デバイス一覧:   %s/settings/devices

                    ※ このメールに返信しても届きません。
                    """.formatted(displayName, device, ip, timestamp, revokeBlock, config.baseUrl(), config.baseUrl()));
            Transport.send(msg);
        } catch (Exception e) {
            throw new RuntimeException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendMagicLinkEmail(String to, String magicLink) {
        if (to == null || to.isBlank()) return;
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", config.smtpHost());
            props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
            props.put("mail.smtp.auth", !config.smtpUser().isBlank());
            props.put("mail.smtp.starttls.enable", "true");
            Session session = !config.smtpUser().isBlank()
                    ? Session.getInstance(props, new Authenticator() {
                        @Override protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(config.smtpUser(), config.smtpPassword());
                        }
                    })
                    : Session.getInstance(props);
            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.smtpFrom()));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject("ログインリンク");
            msg.setText("以下のリンクをクリックしてログインしてください:\n\n" + magicLink + "\n\nこのリンクは10分間有効です。\n心当たりがない場合は無視してください。");
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

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp) {
        sendNewDeviceEmail(to, displayName, device, ip, timestamp, null);
    }

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp, String revokeUrl) {
        if (to == null || to.isBlank() || config.sendgridApiKey().isBlank()) return;
        String domain = config.baseUrl().replaceAll("https?://", "");
        String revokeLine = (revokeUrl == null || revokeUrl.isBlank()) ? ""
                : "\\n心当たりがなければ即時に取り消し: " + revokeUrl.replace("\"", "\\\"");
        String text = ("%s さん、\\n\\n新しいデバイスからのログインがありました。\\n\\n  デバイス: %s\\n  IP: %s\\n  日時: %s"
                + revokeLine
                + "\\n\\nセッション確認: %s/settings/sessions\\nデバイス一覧: %s/settings/devices")
                .formatted(displayName, device, ip, timestamp, config.baseUrl(), config.baseUrl());
        String payload = """
                {"personalizations":[{"to":[{"email":"%s"}]}],
                 "from":{"email":"%s"},
                 "subject":"[%s] 新しいデバイスからのログイン",
                 "content":[{"type":"text/plain","value":"%s"}]}
                """.formatted(
                to.replace("\"", ""),
                config.smtpFrom().replace("\"", ""),
                domain.replace("\"", ""),
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

    @Override
    public void sendMagicLinkEmail(String to, String magicLink) {
        if (to == null || to.isBlank() || config.sendgridApiKey().isBlank()) return;
        String text = "以下のリンクをクリックしてログインしてください:\\n\\n" + magicLink + "\\n\\nこのリンクは10分間有効です。";
        String payload = """
                {"personalizations":[{"to":[{"email":"%s"}]}],
                 "from":{"email":"%s"},
                 "subject":"ログインリンク",
                 "content":[{"type":"text/plain","value":"%s"}]}
                """.formatted(to.replace("\"", ""), config.smtpFrom().replace("\"", ""), text.replace("\"", "\\\""));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.sendgrid.com/v3/mail/send"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + config.sendgridApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) throw new RuntimeException("SendGrid returned " + resp.statusCode());
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("SendGrid send failed: " + e.getMessage(), e); }
    }
}
