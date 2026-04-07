package org.unlaxer.infra.volta;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AppRegistry {
    private final Map<String, AppPolicy> byId;
    private final Map<String, AppPolicy> byHost;
    private final List<AppPolicy> ordered;

    public AppRegistry(AppConfig config) {
        this(load(config.appConfigPath()));
    }

    public AppRegistry(List<AppPolicy> policies) {
        Map<String, AppPolicy> idMap = new HashMap<>();
        Map<String, AppPolicy> hostMap = new HashMap<>();
        for (AppPolicy policy : policies) {
            idMap.put(policy.id(), policy);
            hostMap.put(hostOf(policy.url()), policy);
        }
        this.byId = Map.copyOf(idMap);
        this.byHost = Map.copyOf(hostMap);
        this.ordered = List.copyOf(policies);
    }

    public Optional<AppPolicy> resolve(String appId, String host) {
        if (appId != null && !appId.isBlank()) {
            AppPolicy byAppId = byId.get(appId);
            if (byAppId != null) {
                return Optional.of(byAppId);
            }
        }
        if (host != null && !host.isBlank()) {
            String normalized = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
            AppPolicy byAppHost = byHost.get(normalized);
            if (byAppHost != null) {
                return Optional.of(byAppHost);
            }
        }
        return Optional.empty();
    }

    public Optional<String> defaultAppUrl() {
        if (ordered.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ordered.getFirst().url());
    }

    @SuppressWarnings("unchecked")
    private static List<AppPolicy> load(String path) {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(p)) {
            Yaml yaml = new Yaml();
            Object rootObj = yaml.load(in);
            if (!(rootObj instanceof Map<?, ?> root)) {
                return List.of();
            }
            Object appsObj = root.get("apps");
            if (!(appsObj instanceof List<?> apps)) {
                return List.of();
            }
            List<AppPolicy> result = new ArrayList<>();
            for (Object item : apps) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String id = Objects.toString(m.get("id"), "");
                String url = Objects.toString(m.get("url"), "");
                Object rolesObj = m.get("allowed_roles");
                List<String> roles = new ArrayList<>();
                if (rolesObj instanceof List<?> r) {
                    for (Object role : r) {
                        roles.add(String.valueOf(role));
                    }
                }
                if (!id.isBlank() && !url.isBlank() && !roles.isEmpty()) {
                    result.add(new AppPolicy(id, url, List.copyOf(roles)));
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load app config: " + path, e);
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    public record AppPolicy(String id, String url, List<String> allowedRoles) {
    }
}
