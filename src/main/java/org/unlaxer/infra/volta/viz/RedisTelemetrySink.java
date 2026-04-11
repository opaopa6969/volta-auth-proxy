package org.unlaxer.infra.volta.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unlaxer.tramli.plugins.observability.TelemetryEvent;
import org.unlaxer.tramli.plugins.observability.TelemetrySink;
import redis.clients.jedis.JedisPooled;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TelemetrySink that publishes flow events to Redis Pub/Sub for real-time visualization.
 * Events are published as JSON to the configured channel (default: volta:viz:events).
 * PII is excluded by design — only state machine metadata is published.
 */
public final class RedisTelemetrySink implements TelemetrySink {

    private static final System.Logger LOG = System.getLogger("volta.viz");

    private final JedisPooled jedis;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final AtomicLong seq = new AtomicLong(0);

    public RedisTelemetrySink(JedisPooled jedis, ObjectMapper objectMapper, String channel) {
        this.jedis = jedis;
        this.objectMapper = objectMapper;
        this.channel = channel;
    }

    @Override
    public void emit(TelemetryEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("seq", seq.incrementAndGet());
            payload.put("type", event.type());
            payload.put("flowId", event.flowId());
            payload.put("flowName", event.flowName());
            payload.put("data", buildData(event));
            payload.put("timestamp", event.timestamp().toString());

            String json = objectMapper.writeValueAsString(payload);
            jedis.publish(channel, json);
        } catch (Exception e) {
            // Fire-and-forget: never let viz failure break auth flow
            LOG.log(System.Logger.Level.WARNING, "Failed to publish telemetry to Redis: " + e.getMessage());
        }
    }

    private static Map<String, Object> buildData(TelemetryEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        // Parse "from -> to via trigger" format from transition messages
        String msg = event.message();
        if ("transition".equals(event.type()) && msg != null && msg.contains(" -> ")) {
            String[] parts = msg.split(" -> ", 2);
            data.put("from", parts[0].trim());
            if (parts.length > 1) {
                String rest = parts[1];
                int viaIdx = rest.indexOf(" via ");
                if (viaIdx >= 0) {
                    data.put("to", rest.substring(0, viaIdx).trim());
                    data.put("trigger", rest.substring(viaIdx + 5).trim());
                } else {
                    data.put("to", rest.trim());
                }
            }
        } else {
            data.put("message", msg);
        }
        data.put("durationMicros", event.durationMicros());
        return data;
    }
}
