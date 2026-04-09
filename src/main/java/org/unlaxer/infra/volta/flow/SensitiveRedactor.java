package org.unlaxer.infra.volta.flow;
import org.unlaxer.tramli.FlowContext;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redacts @Sensitive fields from FlowContext snapshots for audit logging.
 * Used when writing to auth_flow_transitions.context_snapshot.
 */
public final class SensitiveRedactor {
    private static final String REDACTED = "***REDACTED***";

    private SensitiveRedactor() {}

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
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            String fieldName = String.valueOf(entry.getKey());
            if (isSensitiveField(clazz, fieldName)) {
                redacted.put(fieldName, REDACTED);
            } else {
                redacted.put(fieldName, entry.getValue());
            }
        }
        return redacted;
    }

    private static boolean isSensitiveField(Class<?> clazz, String jsonFieldName) {
        if (!clazz.isRecord()) return false;
        for (RecordComponent component : clazz.getRecordComponents()) {
            // Check @Sensitive on the record component
            if (component.isAnnotationPresent(Sensitive.class)) {
                // Match by component name or @JsonProperty value
                if (component.getName().equals(jsonFieldName)) return true;
                var jsonProp = component.getAnnotation(
                        com.fasterxml.jackson.annotation.JsonProperty.class);
                if (jsonProp != null && jsonProp.value().equals(jsonFieldName)) return true;
            }
        }
        return false;
    }
}
