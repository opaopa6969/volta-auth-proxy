package org.unlaxer.infra.volta.flow;
import org.unlaxer.tramli.FlowContext;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveRedactorTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @FlowData("test.with_sensitive")
    record WithSensitive(
            @JsonProperty("name") String name,
            @Sensitive @JsonProperty("secret") String secret,
            @JsonProperty("count") int count
    ) {}

    @FlowData("test.no_sensitive")
    record NoSensitive(
            @JsonProperty("value") String value
    ) {}

    @Test
    void redactsSensitiveFields() {
        var registry = new FlowDataRegistry(mapper);
        registry.register(WithSensitive.class);

        var ctx = new FlowContext("flow-1");
        ctx.put(WithSensitive.class, new WithSensitive("hello", "my-secret", 42));

        var serialized = registry.serialize(ctx);
        var redacted = SensitiveRedactor.redact(serialized, registry);

        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) redacted.get("test.with_sensitive");
        assertEquals("hello", fields.get("name"));
        assertEquals("***REDACTED***", fields.get("secret"));
        assertEquals(42, fields.get("count"));
    }

    @Test
    void nonSensitiveFieldsPassThrough() {
        var registry = new FlowDataRegistry(mapper);
        registry.register(NoSensitive.class);

        var ctx = new FlowContext("flow-1");
        ctx.put(NoSensitive.class, new NoSensitive("visible"));

        var serialized = registry.serialize(ctx);
        var redacted = SensitiveRedactor.redact(serialized, registry);

        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) redacted.get("test.no_sensitive");
        assertEquals("visible", fields.get("value"));
    }
}
