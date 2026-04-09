package org.unlaxer.infra.volta.flow;
import org.unlaxer.tramli.FlowException;
import org.unlaxer.tramli.FlowContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of @FlowData aliases. Validates uniqueness at boot time.
 * Used for JSONB serialization/deserialization of FlowContext attributes.
 */
public final class FlowDataRegistry {
    private final Map<String, Class<?>> aliasByName = new HashMap<>();
    private final Map<Class<?>, String> nameByAlias = new HashMap<>();
    private final ObjectMapper objectMapper;

    public FlowDataRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(Class<?> clazz) {
        FlowData annotation = clazz.getAnnotation(FlowData.class);
        if (annotation == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @FlowData");
        }
        String alias = annotation.value();
        Class<?> existing = aliasByName.get(alias);
        if (existing != null && !existing.equals(clazz)) {
            throw new FlowException("DUPLICATE_ALIAS",
                    "FlowData alias '" + alias + "' used by both " + existing.getName() + " and " + clazz.getName());
        }
        aliasByName.put(alias, clazz);
        nameByAlias.put(clazz, alias);
    }

    public String aliasFor(Class<?> clazz) {
        String alias = nameByAlias.get(clazz);
        if (alias == null) {
            throw new IllegalArgumentException("Unregistered FlowData class: " + clazz.getName());
        }
        return alias;
    }

    public Class<?> classFor(String alias) {
        Class<?> clazz = aliasByName.get(alias);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown FlowData alias: " + alias);
        }
        return clazz;
    }

    public Map<String, Object> serialize(FlowContext ctx) {
        Map<String, Object> result = new HashMap<>();
        for (var entry : ctx.snapshot().entrySet()) {
            String alias = nameByAlias.get(entry.getKey());
            if (alias != null) {
                result.put(alias, objectMapper.convertValue(entry.getValue(), Map.class));
            }
        }
        return result;
    }

    public void deserializeInto(FlowContext ctx, Map<String, Object> data) {
        for (var entry : data.entrySet()) {
            Class<?> clazz = aliasByName.get(entry.getKey());
            if (clazz != null) {
                Object value = objectMapper.convertValue(entry.getValue(), clazz);
                ctx.put(toRawClass(clazz), value);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putRaw(FlowContext ctx, Class clazz, Object value) {
        ctx.put(clazz, value);
    }

    @SuppressWarnings("rawtypes")
    private static Class toRawClass(Class<?> clazz) {
        return clazz;
    }

    public boolean isRegistered(Class<?> clazz) {
        return nameByAlias.containsKey(clazz);
    }
}
