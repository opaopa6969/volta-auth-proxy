package org.unlaxer.infra.volta;

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
        var props = new org.unlaxer.propstack.PropStack();
        return ENV_VAR.matcher(input).replaceAll(m -> {
            String val = props.get(m.group(1)).orElse("");
            return Matcher.quoteReplacement(val);
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

        VoltaConfig.TenancyConfig tenancy = parseTenancy(root.get("tenancy"));
        return new VoltaConfig(version, List.copyOf(idps), tenancy);
    }

    private static VoltaConfig.TenancyConfig parseTenancy(Object section) {
        VoltaConfig.TenancyConfig def = VoltaConfig.TenancyConfig.defaults();
        if (!(section instanceof Map<?, ?> m)) return def;

        VoltaConfig.TenancyConfig.Mode mode = def.mode();
        Object modeRaw = m.get("mode");
        if (modeRaw != null) {
            try { mode = VoltaConfig.TenancyConfig.Mode.valueOf(modeRaw.toString().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { /* keep default */ }
        }

        VoltaConfig.TenancyConfig.CreationPolicy policy = def.creationPolicy();
        Object policyRaw = m.get("creation_policy");
        if (policyRaw != null) {
            // YAML value is kebab-like (disabled / auto / admin_only / invite_only) —
            // upper-case and swap '-' for '_' to match the enum literal.
            String normalized = policyRaw.toString().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
            try { policy = VoltaConfig.TenancyConfig.CreationPolicy.valueOf(normalized); }
            catch (IllegalArgumentException ignored) { /* keep default */ }
        }

        int maxOrgs = def.maxOrgsPerUser();
        Object maxRaw = m.get("max_orgs_per_user");
        if (maxRaw instanceof Number n) maxOrgs = n.intValue();

        boolean shadowOrg = def.shadowOrg();
        Object shadowRaw = m.get("shadow_org");
        if (shadowRaw instanceof Boolean b) shadowOrg = b;

        String slugFormat = def.slugFormat();
        Object slugRaw = m.get("slug_format");
        if (slugRaw != null && !slugRaw.toString().isBlank()) slugFormat = slugRaw.toString();

        VoltaConfig.TenancyConfig.Routing routing = parseRouting(m.get("routing"));

        // AUTH-014 Phase 4 item 4-5: isolation + custom_roles (config-only scaffold).
        VoltaConfig.TenancyConfig.Isolation isolation = def.isolation();
        Object isolationRaw = m.get("isolation");
        if (isolationRaw != null) {
            String norm = isolationRaw.toString().toUpperCase(java.util.Locale.ROOT);
            try { isolation = VoltaConfig.TenancyConfig.Isolation.valueOf(norm); }
            catch (IllegalArgumentException ignored) { /* keep default */ }
        }

        boolean customRoles = def.customRoles();
        Object customRolesRaw = m.get("custom_roles");
        if (customRolesRaw instanceof Boolean b) customRoles = b;

        return new VoltaConfig.TenancyConfig(mode, policy, maxOrgs, shadowOrg, slugFormat, routing,
                                             isolation, customRoles);
    }

    private static VoltaConfig.TenancyConfig.Routing parseRouting(Object section) {
        VoltaConfig.TenancyConfig.Routing def = VoltaConfig.TenancyConfig.Routing.defaults();
        if (!(section instanceof Map<?, ?> m)) return def;

        VoltaConfig.TenancyConfig.Routing.Mode mode = def.mode();
        Object modeRaw = m.get("mode");
        if (modeRaw != null) {
            String norm = modeRaw.toString().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
            try { mode = VoltaConfig.TenancyConfig.Routing.Mode.valueOf(norm); }
            catch (IllegalArgumentException ignored) { /* keep default */ }
        }

        String baseDomain = def.baseDomain();
        Object baseDomainRaw = m.get("base_domain");
        if (baseDomainRaw != null) baseDomain = baseDomainRaw.toString();

        String slugHeader = def.slugHeader();
        Object slugHeaderRaw = m.get("slug_header");
        if (slugHeaderRaw != null && !slugHeaderRaw.toString().isBlank()) slugHeader = slugHeaderRaw.toString();

        String slugPrefix = def.slugPrefix();
        Object slugPrefixRaw = m.get("slug_prefix");
        if (slugPrefixRaw != null && !slugPrefixRaw.toString().isBlank()) {
            String p = slugPrefixRaw.toString();
            // Normalize to leading + trailing slash so callers can do
            // startsWith + substring without edge cases.
            if (!p.startsWith("/")) p = "/" + p;
            if (!p.endsWith("/")) p = p + "/";
            slugPrefix = p;
        }

        VoltaConfig.TenancyConfig.Routing.CookieScope cookieScope = def.cookieScope();
        Object scopeRaw = m.get("cookie_scope");
        if (scopeRaw != null) {
            String norm = scopeRaw.toString().toUpperCase(java.util.Locale.ROOT);
            try { cookieScope = VoltaConfig.TenancyConfig.Routing.CookieScope.valueOf(norm); }
            catch (IllegalArgumentException ignored) { /* keep default */ }
        }

        return new VoltaConfig.TenancyConfig.Routing(mode, baseDomain, slugHeader, slugPrefix, cookieScope);
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }
}
