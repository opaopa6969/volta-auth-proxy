package com.volta.authproxy.property;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Pluggable property source interface with ${VAR} expansion support.
 * Port of org.unlaxer.util.property.PropertiesInterface.
 */
public interface PropertiesInterface {

    Optional<String> getRawValue(String key);

    PropertiesInterface set(String key, String value);

    Set<String> keys();

    default Optional<String> get(String key) {
        Optional<String> rawValue = getRawValue(key);
        if (rawValue.isPresent()) {
            String value = rawValue.get();
            for (UnaryOperator<String> effector : valueEffectors()) {
                value = effector.apply(value);
                if (value == null) break;
            }
            return Optional.ofNullable(value);
        }
        return Optional.empty();
    }

    default Optional<String> get(String... keys) {
        for (String key : keys) {
            Optional<String> value = get(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    default Optional<String> get(PropertyKey key) {
        return get(key.key());
    }

    default Properties toProperties() {
        Properties properties = new Properties();
        keys().forEach(key -> get(key).ifPresent(v -> properties.put(key, v)));
        return properties;
    }

    default List<UnaryOperator<String>> valueEffectors() {
        return List.of(EnvironmentVariableEffector.INSTANCE);
    }

    default String name() {
        return getClass().getName();
    }

    // --- Factory methods ---

    static PropertiesInterface systemEnvironmentsOf() {
        HashMap<String, String> overrides = new HashMap<>();
        return new PropertiesInterface() {
            @Override
            public Optional<String> getRawValue(String key) {
                String v = overrides.get(key);
                return v != null ? Optional.of(v) : Optional.ofNullable(System.getenv(key));
            }

            @Override
            public PropertiesInterface set(String key, String value) {
                overrides.put(key, value);
                return this;
            }

            @Override
            public Set<String> keys() {
                Set<String> keys = new HashSet<>(overrides.keySet());
                keys.addAll(System.getenv().keySet());
                return keys;
            }

            @Override
            public String name() { return "SystemEnvironment"; }
        };
    }

    static PropertiesInterface javaPropertyOf() {
        return new PropertiesInterface() {
            @Override
            public Optional<String> getRawValue(String key) {
                return Optional.ofNullable(System.getProperty(key));
            }

            @Override
            public PropertiesInterface set(String key, String value) {
                System.setProperty(key, value);
                return this;
            }

            @Override
            public Set<String> keys() {
                return System.getProperties().keySet().stream()
                        .filter(o -> o instanceof String)
                        .map(String.class::cast)
                        .collect(Collectors.toSet());
            }

            @Override
            public String name() { return "JavaProperties"; }
        };
    }

    static PropertiesInterface pathOf(Path path) {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(path)) {
            props.load(is);
        } catch (Exception ignored) {
        }
        return of(props);
    }

    static PropertiesInterface classPathOf(String resource) {
        Properties props = new Properties();
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (is != null) props.load(is);
        } catch (Exception ignored) {
        }
        return of(props);
    }

    static PropertiesInterface of(Properties properties) {
        return new PropertiesInterface() {
            @Override
            public Optional<String> getRawValue(String key) {
                return Optional.ofNullable(properties.getProperty(key));
            }

            @Override
            public PropertiesInterface set(String key, String value) {
                properties.setProperty(key, value);
                return this;
            }

            @Override
            public Set<String> keys() {
                return properties.keySet().stream()
                        .filter(o -> o instanceof String)
                        .map(String.class::cast)
                        .collect(Collectors.toSet());
            }
        };
    }

    static PropertiesInterface of(Map<String, String> map) {
        return new PropertiesInterface() {
            @Override
            public Optional<String> getRawValue(String key) {
                return Optional.ofNullable(map.get(key));
            }

            @Override
            public PropertiesInterface set(String key, String value) {
                map.put(key, value);
                return this;
            }

            @Override
            public Set<String> keys() { return map.keySet(); }
        };
    }
}
