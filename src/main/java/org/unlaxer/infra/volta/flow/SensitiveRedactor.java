package org.unlaxer.infra.volta.flow;
import org.unlaxer.tramli.FlowContext;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redacts @Sensitive fields from FlowContext snapshots for audit logging.
 * Used when writing to auth_flow_transitions.context_snapshot.
 */
public final class SensitiveRedactor {
    private static final String REDACTED = "***REDACTED***";

    private SensitiveRedactor() {}

    // Cache of sensitive JSON field names per FlowData record class.
    // Reflection over RecordComponent + @Sensitive is invariant per class,
    // so the result is cached once per class and reused across all transitions.
    // Key: FlowData record class. Value: unmodifiable set of sensitive JSON field names.
    private static final ConcurrentHashMap<Class<?>, Set<String>> SENSITIVE_CACHE = new ConcurrentHashMap<>();

    private static Set<String> sensitiveFieldNames(Class<?> clazz) {
        Set<String> cached = SENSITIVE_CACHE.get(clazz);
        if (cached != null) return cached;
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (clazz.isRecord()) {
            for (RecordComponent component : clazz.getRecordComponents()) {
                if (component.isAnnotationPresent(Sensitive.class)) {
                    names.add(component.getName());
                    var jsonProp = component.getAnnotation(
                            com.fasterxml.jackson.annotation.JsonProperty.class);
                    if (jsonProp != null && !jsonProp.value().isEmpty()) {
                        names.add(jsonProp.value());
                    }
                }
            }
        }
        Set<String> immutable = java.util.Collections.unmodifiableSet(names);
        // putIfAbsent to avoid replacing a concurrently-computed entry
        Set<String> existing = SENSITIVE_CACHE.putIfAbsent(clazz, immutable);
        return existing != null ? existing : immutable;
    }

    /**
     * Redact @Sensitive fields from a serialized context map.
     * Input: {"alias": {"field1": "value", "field2": "secret"}}
     * Output: {"alias": {"field1": "value", "field2": "***REDACTED***"}}
     */
    public static Map<String, Object> redact(Map<String, Object> serializedContext,
                                              FlowDataRegistry registry) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : serializedContext.entrySet()) {
            String alias = entry.getKey();
            Object value = entry.getValue();

            try {
                Class<?> clazz = registry.classFor(alias);
                if (value instanceof Map<?, ?> map) {
                    result.put(alias, redactFields(clazz, map));
                } else {
                    result.put(alias, value);
                }
            } catch (IllegalArgumentException e) {
                // Unknown alias — pass through
                result.put(alias, value);
            }
        }
        return result;
    }

    private static Map<String, Object> redactFields(Class<?> clazz, Map<?, ?> fields) {
        Set<String> sensitive = sensitiveFieldNames(clazz);
        if (sensitive.isEmpty()) {
            // No sensitive fields on this type — return the input map as-is
            @SuppressWarnings("unchecked")
            Map<String, Object> unchecked = (Map<String, Object>) fields;
            return unchecked;
        }
        Map<String, Object> redacted = new LinkedHashMap<>(fields.size());
        for (var entry : fields.entrySet()) {
            String fieldName = String.valueOf(entry.getKey());
            if (sensitive.contains(fieldName)) {
                redacted.put(fieldName, REDACTED);
            } else {
                redacted.put(fieldName, entry.getValue());
            }
        }
        return redacted;
    }
}
