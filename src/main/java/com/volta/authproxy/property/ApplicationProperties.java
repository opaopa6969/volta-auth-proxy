package com.volta.authproxy.property;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Application-wide property resolver for volta-auth-proxy.
 *
 * Resolution order (first match wins):
 *   1. Programmatic set() calls
 *   2. -D Java System Properties
 *   3. Environment variables
 *   4. ~/.volta/application.properties
 *   5. classpath application.properties (bundled defaults)
 *
 * Port of org.unlaxer.util.property.ApplicationProperties.
 */
public class ApplicationProperties implements PropertiesInterface {

    private final StackableProperties stackableProperties;

    public ApplicationProperties() {
        this(
                true,
                PropertiesInterface.pathOf(
                        Path.of(System.getProperty("user.home"), ".volta", "application.properties")),
                PropertiesInterface.classPathOf("application.properties")
        );
    }

    public ApplicationProperties(boolean enableEnvironments, PropertiesInterface... extras) {
        this.stackableProperties = new StackableProperties(enableEnvironments, extras);
    }

    @Override
    public Optional<String> getRawValue(String key) {
        return stackableProperties.get(key);
    }

    @Override
    public PropertiesInterface set(String key, String value) {
        return stackableProperties.set(key, value);
    }

    @Override
    public Set<String> keys() {
        return stackableProperties.keys();
    }

    @Override
    public Properties toProperties() {
        return stackableProperties.toProperties();
    }

    // --- Convenience methods for AppConfig ---

    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return get(key).filter(s -> !s.isBlank()).map(Integer::parseInt).orElse(defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key).filter(s -> !s.isBlank()).map(Boolean::parseBoolean).orElse(defaultValue);
    }
}
