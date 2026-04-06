package com.volta.authproxy.flow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Accumulator for flow data. Each processor puts its produces, subsequent processors get their requires.
 * Keyed by Class — each @FlowData type appears at most once.
 */
public final class FlowContext {
    private final String flowId;
    private final Instant createdAt;
    private final Map<Class<?>, Object> attributes;

    public FlowContext(String flowId) {
        this(flowId, Instant.now(), new LinkedHashMap<>());
    }

    public FlowContext(String flowId, Instant createdAt, Map<Class<?>, Object> attributes) {
        this.flowId = flowId;
        this.createdAt = createdAt;
        this.attributes = new LinkedHashMap<>(attributes);
    }

    public String flowId() { return flowId; }
    public Instant createdAt() { return createdAt; }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> key) {
        Object value = attributes.get(key);
        if (value == null) {
            throw FlowException.missingContext(key);
        }
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> key) {
        return Optional.ofNullable((T) attributes.get(key));
    }

    public <T> void put(Class<T> key, T value) {
        attributes.put(key, value);
    }

    public boolean has(Class<?> key) {
        return attributes.containsKey(key);
    }

    public Map<Class<?>, Object> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
