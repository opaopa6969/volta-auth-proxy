package com.volta.authproxy.property;

import java.util.*;

/**
 * Cascading property resolver. First match wins.
 * Port of org.unlaxer.util.property.StackableProperties.
 */
public class StackableProperties implements PropertiesInterface {

    private final PropertiesInterface first = PropertiesInterface.of(new HashMap<>());
    private final List<PropertiesInterface> sources;

    public StackableProperties(boolean enableEnvironments, PropertiesInterface... extras) {
        this(enableEnvironments, new ArrayList<>(List.of(extras)));
    }

    public StackableProperties(boolean enableEnvironments, List<PropertiesInterface> extras) {
        this.sources = new ArrayList<>();
        this.sources.add(first);  // programmatic overrides (highest priority)
        if (enableEnvironments) {
            this.sources.add(PropertiesInterface.javaPropertyOf());     // -D flags
            this.sources.add(PropertiesInterface.systemEnvironmentsOf()); // env vars
        }
        this.sources.addAll(extras);  // file-based sources (lowest priority)
    }

    @Override
    public Optional<String> getRawValue(String key) {
        for (PropertiesInterface source : sources) {
            Optional<String> value = source.get(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    @Override
    public PropertiesInterface set(String key, String value) {
        first.set(key, value);
        return this;
    }

    @Override
    public Set<String> keys() {
        Set<String> all = new HashSet<>();
        sources.forEach(s -> all.addAll(s.keys()));
        return all;
    }

    @Override
    public Properties toProperties() {
        Properties properties = new Properties();
        for (int i = sources.size() - 1; i >= 0; i--) {
            PropertiesInterface source = sources.get(i);
            source.keys().forEach(key -> source.get(key).ifPresent(v -> properties.put(key, v)));
        }
        return properties;
    }
}
