package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

interface AuditSink extends AutoCloseable {
    void publish(Map<String, Object> event);

    @Override
    default void close() {
    }

    static AuditSink create(AppConfig config) {
        String type = config.auditSink().toLowerCase();
        return switch (type) {
            case "kafka" -> new KafkaAuditSink(config);
            case "elasticsearch" -> new ElasticsearchAuditSink(config);
            default -> new NoopAuditSink();
        };
    }
}

final class NoopAuditSink implements AuditSink {
    @Override
    public void publish(Map<String, Object> event) {
    }
}

final class KafkaAuditSink implements AuditSink {
    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final ObjectMapper objectMapper = new ObjectMapper();

    KafkaAuditSink(AppConfig config) {
        if (config.kafkaBootstrapServers().isBlank()) {
            throw new IllegalArgumentException("KAFKA_BOOTSTRAP_SERVERS is required for AUDIT_SINK=kafka");
        }
        Properties properties = new Properties();
        properties.put("bootstrap.servers", config.kafkaBootstrapServers());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        properties.put("acks", "1");
        this.producer = new KafkaProducer<>(properties);
        this.topic = config.kafkaAuditTopic();
    }

    @Override
    public void publish(Map<String, Object> event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            producer.send(new ProducerRecord<>(topic, payload));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        producer.flush();
        producer.close(Duration.ofSeconds(2));
    }
}

final class ElasticsearchAuditSink implements AuditSink {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String endpoint;

    ElasticsearchAuditSink(AppConfig config) {
        if (config.elasticsearchUrl().isBlank()) {
            throw new IllegalArgumentException("ELASTICSEARCH_URL is required for AUDIT_SINK=elasticsearch");
        }
        this.endpoint = config.elasticsearchUrl().replaceAll("/+$", "") + "/volta-audit/_doc";
    }

    @Override
    public void publish(Map<String, Object> event) {
        try {
            String body = objectMapper.writeValueAsString(event);
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }
}
