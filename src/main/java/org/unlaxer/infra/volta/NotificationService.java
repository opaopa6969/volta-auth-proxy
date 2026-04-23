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

    /** i18n: invitation email variant that picks a locale. */
    default void sendInvitationEmail(String to, String inviteLink, String tenantName, String role,
                                     String inviterName, String locale) {
        sendInvitationEmail(to, inviteLink, tenantName, role, inviterName);
    }

    /** i18n: magic-link email variant that picks a locale. */
    default void sendMagicLinkEmail(String to, String magicLink, String locale) {
        sendMagicLinkEmail(to, magicLink);
    }

    /**
     * AUTH-004-v2: extended variant that includes a one-click revoke URL.
     * Default implementation ignores the URL and calls the legacy method so
     * existing overrides keep working; SMTP / SendGrid implementations
     * override this to embed the link.
     */
    default void sendNewDeviceEmail(String to, String displayName, String device, String ip,
                                    String timestamp, String revokeUrl) {
        sendNewDeviceEmail(to, displayName, device, ip, timestamp, revokeUrl, null);
    }

    /**
     * AUTH-004-v2 + i18n: variant that resolves email text from the
     * {@code messages_<locale>.properties} bundle. Falls back to the
     * revoke-URL variant (which falls back to the legacy variant) when
     * implementations don't override.
     */
    default void sendNewDeviceEmail(String to, String displayName, String device, String ip,
                                    String timestamp, String revokeUrl, String locale) {
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
        sendInvitationEmail(to, inviteLink, tenantName, role, inviterName, null);
    }

    @Override
    public void sendInvitationEmail(String to, String inviteLink, String tenantName, String role,
                                    String inviterName, String locale) {
        if (to == null || to.isBlank()) return;
        try {
            Session session = buildSession();
            Messages m = Messages.forLocale(locale);
            String inviter = (inviterName == null || inviterName.isBlank())
                    ? m.get("email.invite.defaultInviter")
                    : inviterName;
            Message mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(config.smtpFrom()));
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            mime.setSubject(m.get("email.invite.subject", tenantName));
            mime.setText(String.join("\n",
                    m.get("email.invite.body", inviter, tenantName, role, inviteLink),
                    "",
                    m.get("email.invite.footer")));
            Transport.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    // AUTH-004-v2 i18n refactor: common SMTP Session builder so i18n-aware
    // overloads don't repeat the auth plumbing.
    private Session buildSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.smtpHost());
        props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
        props.put("mail.smtp.auth", !config.smtpUser().isBlank());
        props.put("mail.smtp.starttls.enable", "true");
        if (!config.smtpUser().isBlank()) {
            return Session.getInstance(props, new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.smtpUser(), config.smtpPassword());
                }
            });
        }
        return Session.getInstance(props);
    }

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp) {
        sendNewDeviceEmail(to, displayName, device, ip, timestamp, null, null);
    }

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp, String revokeUrl) {
        sendNewDeviceEmail(to, displayName, device, ip, timestamp, revokeUrl, null);
    }

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp, String revokeUrl, String locale) {
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
            // AUTH-004-v2 + i18n: build the body from messages_<locale>.properties
            // so translations live in resource bundles, not Java source.
            Messages msg = Messages.forLocale(locale);
            String host = config.baseUrl().replaceAll("https?://", "");
            String subject = msg.get("email.newDevice.subject", host);
            String revokeBlock = (revokeUrl == null || revokeUrl.isBlank()) ? ""
                    : "\n" + msg.get("email.newDevice.revokePrompt") + "\n  " + revokeUrl + "\n";
            String body = String.join("\n",
                    msg.get("email.newDevice.greeting", displayName),
                    "",
                    msg.get("email.newDevice.intro"),
                    "",
                    "  " + msg.get("email.newDevice.field.device", device),
                    "  " + msg.get("email.newDevice.field.ip", ip),
                    "  " + msg.get("email.newDevice.field.time", timestamp),
                    revokeBlock,
                    msg.get("email.newDevice.sessionLink", config.baseUrl()),
                    msg.get("email.newDevice.devicesLink", config.baseUrl()),
                    "",
                    msg.get("email.newDevice.footer"));
            Message mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(config.smtpFrom()));
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            mime.setSubject(subject);
            mime.setText(body);
            Transport.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendMagicLinkEmail(String to, String magicLink) {
        sendMagicLinkEmail(to, magicLink, null);
    }

    @Override
    public void sendMagicLinkEmail(String to, String magicLink, String locale) {
        if (to == null || to.isBlank()) return;
        try {
            Messages m = Messages.forLocale(locale);
            Message mime = new MimeMessage(buildSession());
            mime.setFrom(new InternetAddress(config.smtpFrom()));
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            mime.setSubject(m.get("email.magicLink.subject"));
            mime.setText(String.join("\n",
                    m.get("email.magicLink.body", magicLink),
                    "",
                    m.get("email.magicLink.footer")));
            Transport.send(mime);
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
        sendInvitationEmail(to, inviteLink, tenantName, role, inviterName, null);
    }

    @Override
    public void sendInvitationEmail(String to, String inviteLink, String tenantName, String role,
                                    String inviterName, String locale) {
        if (to == null || to.isBlank() || config.sendgridApiKey().isBlank()) {
            return;
        }
        Messages m = Messages.forLocale(locale);
        String inviter = (inviterName == null || inviterName.isBlank())
                ? m.get("email.invite.defaultInviter") : inviterName;
        String subject = m.get("email.invite.subject", tenantName);
        String text = (m.get("email.invite.body", inviter, tenantName, role, inviteLink)
                + "\n\n" + m.get("email.invite.footer")).replace("\n", "\\n");
        String payload = """
                {"personalizations":[{"to":[{"email":"%s"}]}],
                 "from":{"email":"%s"},
                 "subject":"%s",
                 "content":[{"type":"text/plain","value":"%s"}]}
                """.formatted(
                to.replace("\"", ""),
                config.smtpFrom().replace("\"", ""),
                subject.replace("\"", "\\\""),
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
        sendNewDeviceEmail(to, displayName, device, ip, timestamp, null, null);
    }

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp, String revokeUrl) {
        sendNewDeviceEmail(to, displayName, device, ip, timestamp, revokeUrl, null);
    }

    @Override
    public void sendNewDeviceEmail(String to, String displayName, String device, String ip, String timestamp, String revokeUrl, String locale) {
        if (to == null || to.isBlank() || config.sendgridApiKey().isBlank()) return;
        String domain = config.baseUrl().replaceAll("https?://", "");
        // AUTH-004-v2 + i18n: resource-bundle lookup.
        Messages m = Messages.forLocale(locale);
        String subject = m.get("email.newDevice.subject", domain);
        String revokeLine = (revokeUrl == null || revokeUrl.isBlank()) ? ""
                : "\\n" + m.get("email.newDevice.revokePrompt").replace("\"", "\\\"")
                + "\\n  " + revokeUrl.replace("\"", "\\\"");
        // SendGrid JSON payload needs \n escaped. Compose per-line then join.
        String[] lines = {
                m.get("email.newDevice.greeting", displayName),
                "",
                m.get("email.newDevice.intro"),
                "",
                "  " + m.get("email.newDevice.field.device", device),
                "  " + m.get("email.newDevice.field.ip", ip),
                "  " + m.get("email.newDevice.field.time", timestamp),
                revokeLine,
                "",
                m.get("email.newDevice.sessionLink", config.baseUrl()),
                m.get("email.newDevice.devicesLink", config.baseUrl()),
                "",
                m.get("email.newDevice.footer")
        };
        String text = String.join("\\n",
                java.util.Arrays.stream(lines).map(s -> s.replace("\"", "\\\"")).toArray(String[]::new));
        String payload = """
                {"personalizations":[{"to":[{"email":"%s"}]}],
                 "from":{"email":"%s"},
                 "subject":"%s",
                 "content":[{"type":"text/plain","value":"%s"}]}
                """.formatted(
                to.replace("\"", ""),
                config.smtpFrom().replace("\"", ""),
                subject.replace("\"", "\\\""),
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
        sendMagicLinkEmail(to, magicLink, null);
    }

    @Override
    public void sendMagicLinkEmail(String to, String magicLink, String locale) {
        if (to == null || to.isBlank() || config.sendgridApiKey().isBlank()) return;
        Messages m = Messages.forLocale(locale);
        String subject = m.get("email.magicLink.subject");
        String text = (m.get("email.magicLink.body", magicLink)
                + "\n\n" + m.get("email.magicLink.footer")).replace("\n", "\\n");
        String payload = """
                {"personalizations":[{"to":[{"email":"%s"}]}],
                 "from":{"email":"%s"},
                 "subject":"%s",
                 "content":[{"type":"text/plain","value":"%s"}]}
                """.formatted(
                to.replace("\"", ""),
                config.smtpFrom().replace("\"", ""),
                subject.replace("\"", "\\\""),
                text.replace("\"", "\\\""));
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
