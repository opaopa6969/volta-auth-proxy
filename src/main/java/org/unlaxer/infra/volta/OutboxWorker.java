package org.unlaxer.infra.volta;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class OutboxWorker implements AutoCloseable {
    private final AppConfig config;
    private final SqlStore store;
    private final NotificationService notificationService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbox-worker");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    OutboxWorker(AppConfig config, SqlStore store, NotificationService notificationService) {
        this.config = config;
        this.store = store;
        this.notificationService = notificationService;
    }

    void start() {
        // Outbox worker handles both webhooks and notification emails
        scheduler.scheduleWithFixedDelay(this::runOnce, 2, Math.max(5, config.webhookWorkerIntervalSeconds()), TimeUnit.SECONDS);
    }

    void runOnce() {
        try {
            List<SqlStore.OutboxRecord> pending = store.claimPendingOutboxEvents(50, workerId, 60);
            for (SqlStore.OutboxRecord outbox : pending) {
                process(outbox);
            }
        } catch (Exception ignored) {
        }
    }

    private void process(SqlStore.OutboxRecord outbox) {
        try {
            // Notification events: delegate to NotificationService
            if (outbox.eventType() != null && outbox.eventType().startsWith("notification.")) {
                processNotification(outbox);
                return;
            }

            UUID tenantId = outbox.tenantId();
            if (tenantId == null) {
                store.markOutboxPublished(outbox.id());
                return;
            }
            List<SqlStore.WebhookRecord> webhooks = store.listWebhooks(tenantId).stream()
                    .filter(SqlStore.WebhookRecord::active)
                    .filter(w -> acceptsEvent(w.events(), outbox.eventType()))
                    .toList();
            if (webhooks.isEmpty()) {
                store.markOutboxPublished(outbox.id());
                return;
            }

            boolean allOk = true;
            for (SqlStore.WebhookRecord webhook : webhooks) {
                DeliveryResult result = deliver(webhook, outbox.eventType(), outbox.payload());
                if (result.ok()) {
                    store.markWebhookSuccess(webhook.id());
                } else {
                    allOk = false;
                    store.markWebhookFailure(webhook.id());
                }
                store.insertWebhookDelivery(outbox.id(), webhook.id(), outbox.eventType(), result.ok() ? "success" : "failed", result.statusCode(), result.body());
            }
            if (allOk) {
                store.markOutboxPublished(outbox.id());
                return;
            }
            int nextAttempt = outbox.attemptCount() + 1;
            if (nextAttempt >= Math.max(1, config.webhookRetryMax())) {
                store.markOutboxPublished(outbox.id());
                return;
            }
            Instant nextAt = Instant.now().plusSeconds(backoffSeconds(nextAttempt));
            store.markOutboxRetry(outbox.id(), nextAttempt, nextAt, "delivery failed");
        } catch (Exception e) {
            store.clearOutboxLock(outbox.id());
        }
    }

    private void processNotification(SqlStore.OutboxRecord outbox) {
        try {
            com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(outbox.payload());
            switch (outbox.eventType()) {
                case "notification.invitation" -> {
                    notificationService.sendInvitationEmail(
                            payload.path("to").asText(),
                            payload.path("inviteLink").asText(),
                            payload.path("tenantName").asText(),
                            payload.path("role").asText(),
                            payload.path("inviterName").asText());
                }
                case "notification.magic_link" -> {
                    notificationService.sendMagicLinkEmail(
                            payload.path("to").asText(),
                            payload.path("magicLink").asText());
                }
                case "notification.new_device" -> {
                    notificationService.sendNewDeviceEmail(
                            payload.path("to").asText(),
                            payload.path("displayName").asText(),
                            payload.path("device").asText(),
                            payload.path("ip").asText(),
                            payload.path("timestamp").asText());
                }
                default -> { /* unknown notification type */ }
            }
            store.markOutboxPublished(outbox.id());
        } catch (Exception e) {
            int nextAttempt = outbox.attemptCount() + 1;
            if (nextAttempt >= Math.max(1, config.webhookRetryMax())) {
                store.markOutboxPublished(outbox.id());
                return;
            }
            Instant nextAt = Instant.now().plusSeconds(backoffSeconds(nextAttempt));
            store.markOutboxRetry(outbox.id(), nextAttempt, nextAt, "notification failed: " + e.getMessage());
        }
    }

    private DeliveryResult deliver(SqlStore.WebhookRecord webhook, String eventType, String payloadJson) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook.endpointUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-Volta-Event", eventType)
                    .header("X-Volta-Signature", SecurityUtils.hmacSha256Hex(webhook.secret(), payloadJson))
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return new DeliveryResult(ok, response.statusCode(), trim(response.body()));
        } catch (Exception e) {
            return new DeliveryResult(false, null, trim(e.getMessage()));
        }
    }

    private static String trim(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static int backoffSeconds(int attempt) {
        return switch (attempt) {
            case 1 -> 60;
            case 2 -> 300;
            default -> 1800;
        };
    }

    private static boolean acceptsEvent(String eventsCsv, String eventType) {
        if (eventsCsv == null || eventsCsv.isBlank()) {
            return true;
        }
        List<String> events = List.of(eventsCsv.split(","));
        for (String event : events) {
            if (eventType.equalsIgnoreCase(event.trim())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private record DeliveryResult(boolean ok, Integer statusCode, String body) {
    }
}
