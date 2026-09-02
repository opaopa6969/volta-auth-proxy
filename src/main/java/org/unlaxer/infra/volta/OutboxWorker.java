package org.unlaxer.infra.volta;

import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class OutboxWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("volta.outbox");
    /**
     * webhook 配信の接続タイムアウト。他の HTTP クライアントと同様 10s。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * webhook 配信の要求タイムアウト。未設定だと無限待機になり、
     * 単一スレッド worker が 1 件のハングで全テナントの通知を止める DoS になる。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final AppConfig config;
    private final SqlStore store;
    private final NotificationService notificationService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbox-worker");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
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
        } catch (Exception e) {
            // ワーカーのループを止めないために飲み込む。ただし黙ると
            // 「メールが送られていない」ことに気付けない (#40)。
            LOG.log(System.Logger.Level.WARNING,
                    "outbox worker iteration failed (will retry next tick): {0}", e.toString());
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
            RetryDecision retry = computeRetry(outbox.attemptCount(), config.webhookRetryMax());
            if (retry.giveUp()) {
                store.markOutboxPublished(outbox.id());
                return;
            }
            store.markOutboxRetry(outbox.id(), retry.nextAttempt(), retry.nextAt(), "delivery failed");
        } catch (Exception e) {
            // processNotification と対称にリトライ管理を行う。
            // ここに到達するのは配信以外の DB 操作(listWebhooks / insertWebhookDelivery /
            // markOutboxRetry 等)が例外を投げたとき。clearOutboxLock だけだと
            // attempt_count が進まず毎 tick で無限再取得されるため (#71)。
            store.clearOutboxLock(outbox.id());
            RetryDecision retry = computeRetry(outbox.attemptCount(), config.webhookRetryMax());
            if (retry.giveUp()) {
                store.markOutboxPublished(outbox.id());
                return;
            }
            store.markOutboxRetry(outbox.id(), retry.nextAttempt(), retry.nextAt(),
                    "processing exception: " + e.getMessage());
        }
    }

    private void processNotification(SqlStore.OutboxRecord outbox) {
        try {
            com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(outbox.payload());
            switch (outbox.eventType()) {
                case "notification.invitation" -> {
                    // i18n: optional `locale` field in the payload picks the
                    // resource bundle; absent → default locale.
                    String invLocale = payload.path("locale").asText("");
                    notificationService.sendInvitationEmail(
                            payload.path("to").asText(),
                            payload.path("inviteLink").asText(),
                            payload.path("tenantName").asText(),
                            payload.path("role").asText(),
                            payload.path("inviterName").asText(),
                            invLocale.isBlank() ? null : invLocale);
                }
                case "notification.magic_link" -> {
                    String mlLocale = payload.path("locale").asText("");
                    notificationService.sendMagicLinkEmail(
                            payload.path("to").asText(),
                            payload.path("magicLink").asText(),
                            mlLocale.isBlank() ? null : mlLocale);
                }
                case "notification.new_device" -> {
                    // AUTH-004-v2: pass the optional revoke URL + GeoIP
                    // location through. Older payloads without the fields
                    // still work via empty string → null.
                    // i18n: locale is looked up in AuthRouter from users.locale
                    // and included in the payload. Empty → default locale.
                    String revokeUrl = payload.path("revokeUrl").asText("");
                    String locale    = payload.path("locale").asText("");
                    String location  = payload.path("location").asText("");
                    notificationService.sendNewDeviceEmail(
                            payload.path("to").asText(),
                            payload.path("displayName").asText(),
                            payload.path("device").asText(),
                            payload.path("ip").asText(),
                            payload.path("timestamp").asText(),
                            revokeUrl.isBlank() ? null : revokeUrl,
                            locale.isBlank() ? null : locale,
                            location.isBlank() ? null : location);
                }
                default -> { /* unknown notification type */ }
            }
            store.markOutboxPublished(outbox.id());
        } catch (Exception e) {
            RetryDecision retry = computeRetry(outbox.attemptCount(), config.webhookRetryMax());
            if (retry.giveUp()) {
                store.markOutboxPublished(outbox.id());
                return;
            }
            store.markOutboxRetry(outbox.id(), retry.nextAttempt(), retry.nextAt(), "notification failed: " + e.getMessage());
        }
    }

    private DeliveryResult deliver(SqlStore.WebhookRecord webhook, String eventType, String payloadJson) {
        try {
            URI endpoint = URI.create(webhook.endpointUrl());
            validateDeliveryAddress(endpoint);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
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

    /** Re-check DNS immediately before delivery to reduce webhook DNS-rebinding risk. */
    static void validateDeliveryAddress(URI endpoint) throws Exception {
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("webhook host is missing");
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || isCarrierGradeNat(address)) {
                throw new SecurityException("webhook resolves to a private address");
            }
        }
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
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

    /**
     * 次のリトライを判定する。attemptCount を進め、webhookRetryMax に達したら
     * 打ち切り(giveUp=true)、未達なら backoff 適用済みの nextAt を返す。
     * process と processNotification で同一ロジックを共有する (#71)。
     */
    static RetryDecision computeRetry(int attemptCount, int webhookRetryMax) {
        int nextAttempt = attemptCount + 1;
        if (nextAttempt >= Math.max(1, webhookRetryMax)) {
            return new RetryDecision(true, nextAttempt, null);
        }
        Instant nextAt = Instant.now().plusSeconds(backoffSeconds(nextAttempt));
        return new RetryDecision(false, nextAttempt, nextAt);
    }

    record RetryDecision(boolean giveUp, int nextAttempt, Instant nextAt) {}

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
