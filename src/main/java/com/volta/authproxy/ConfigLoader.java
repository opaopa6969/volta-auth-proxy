package com.volta.authproxy;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code volta-config.yaml} and produces a {@link VoltaConfig}.
 *
 * <p>{@code ${ENV_VAR}} references in the YAML are expanded from
 * {@link System#getenv} before parsing.  Missing variables resolve to an
 * empty string, which causes the provider's {@code isEnabled()} check to
 * return false.
 *
 * <p>If the file does not exist, {@link VoltaConfig#empty()} is returned so
 * the system falls back to pure ENV-var detection (backward compatible).
 */
public final class ConfigLoader {

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)\\}");

    private ConfigLoader() {}

    public static VoltaConfig load(String path) {
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            return VoltaConfig.empty();
        }
        try {
            String raw      = Files.readString(file);
            String expanded = expandEnvVars(raw);
            return parse(expanded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config: " + path, e);
        }
    }

    // package-private for testing
    static String expandEnvVars(String input) {
        return ENV_VAR.matcher(input).replaceAll(m -> {
            String val = System.getenv(m.group(1));
            return Matcher.quoteReplacement(val != null ? val : "");
        });
    }

    @SuppressWarnings("unchecked")
    static VoltaConfig parse(String yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        if (root == null) return VoltaConfig.empty();

        int version = root.containsKey("version")
                ? ((Number) root.get("version")).intValue() : 1;

        List<VoltaConfig.IdpEntry> idps = new ArrayList<>();
        Object idpSection = root.get("idp");
        if (idpSection instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String id           = str(m, "id");
                String clientId     = str(m, "client_id");
                String clientSecret = str(m, "client_secret");
                if (id.isBlank()) continue;

                Map<String, String> extra = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (!Set.of("id", "client_id", "client_secret").contains(k)) {
                        extra.put(k, e.getValue() != null ? e.getValue().toString() : "");
                    }
                }
                idps.add(new VoltaConfig.IdpEntry(
                        id.toUpperCase(java.util.Locale.ROOT),
                        clientId, clientSecret,
                        Map.copyOf(extra)));
            }
        }
        return new VoltaConfig(version, List.copyOf(idps));
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }
}
