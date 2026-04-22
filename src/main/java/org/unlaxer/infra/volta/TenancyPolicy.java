package org.unlaxer.infra.volta;

/**
 * AUTH-014 Phase 2: single-vs-multi tenancy mode policy.
 *
 * <p>Single-source helper for every place that needs to decide whether a
 * user should land on {@code /select-tenant} or go straight into the app.
 *
 * <p>In {@code SINGLE} mode the discovery flow is suppressed even when a
 * user has multiple active memberships — auth consumers treat the effective
 * tenant as "the first one" (or the session's {@code tenantId}) so
 * end-users never see the selector. In {@code MULTI} mode the selector is
 * shown whenever the user has more than one tenant.
 */
public final class TenancyPolicy {

    private final VoltaConfig.TenancyConfig.Mode mode;
    private final VoltaConfig.TenancyConfig.CreationPolicy creationPolicy;
    private final VoltaConfig.TenancyConfig.Routing routing;
    private final VoltaConfig.TenancyConfig.Isolation isolation;
    private final boolean customRoles;

    public TenancyPolicy(VoltaConfig voltaConfig) {
        VoltaConfig.TenancyConfig t = voltaConfig == null
                ? VoltaConfig.TenancyConfig.defaults()
                : voltaConfig.tenancy();
        this.mode           = t.mode()           == null ? VoltaConfig.TenancyConfig.Mode.SINGLE : t.mode();
        this.creationPolicy = t.creationPolicy() == null ? VoltaConfig.TenancyConfig.CreationPolicy.DISABLED : t.creationPolicy();
        this.routing        = t.routing()        == null ? VoltaConfig.TenancyConfig.Routing.defaults() : t.routing();
        this.isolation      = t.isolation()      == null ? VoltaConfig.TenancyConfig.Isolation.SHARED : t.isolation();
        this.customRoles    = t.customRoles();
    }

    public TenancyPolicy(VoltaConfig.TenancyConfig.Mode mode) {
        this(mode, VoltaConfig.TenancyConfig.defaults().creationPolicy(),
             VoltaConfig.TenancyConfig.Routing.defaults());
    }

    public TenancyPolicy(VoltaConfig.TenancyConfig.Mode mode,
                         VoltaConfig.TenancyConfig.CreationPolicy creationPolicy) {
        this(mode, creationPolicy, VoltaConfig.TenancyConfig.Routing.defaults());
    }

    public TenancyPolicy(VoltaConfig.TenancyConfig.Mode mode,
                         VoltaConfig.TenancyConfig.CreationPolicy creationPolicy,
                         VoltaConfig.TenancyConfig.Routing routing) {
        this.mode = mode == null ? VoltaConfig.TenancyConfig.Mode.SINGLE : mode;
        this.creationPolicy = creationPolicy == null
                ? VoltaConfig.TenancyConfig.defaults().creationPolicy()
                : creationPolicy;
        this.routing = routing == null
                ? VoltaConfig.TenancyConfig.Routing.defaults()
                : routing;
        this.isolation = VoltaConfig.TenancyConfig.Isolation.SHARED;
        this.customRoles = false;
    }

    public VoltaConfig.TenancyConfig.Mode mode() {
        return mode;
    }

    public VoltaConfig.TenancyConfig.CreationPolicy creationPolicy() {
        return creationPolicy;
    }

    /** True when any authenticated user may create a new tenant (policy=AUTO). */
    public boolean allowsSelfServiceCreation() {
        return creationPolicy == VoltaConfig.TenancyConfig.CreationPolicy.AUTO;
    }

    public VoltaConfig.TenancyConfig.Routing routing() { return routing; }

    public boolean isSlugRouting() {
        return routing.mode() == VoltaConfig.TenancyConfig.Routing.Mode.SLUG;
    }

    public boolean isSubdomainRouting() {
        return routing.mode() == VoltaConfig.TenancyConfig.Routing.Mode.SUBDOMAIN;
    }

    public boolean isDomainRouting() {
        return routing.mode() == VoltaConfig.TenancyConfig.Routing.Mode.DOMAIN;
    }

    public VoltaConfig.TenancyConfig.Isolation isolation() {
        return isolation;
    }

    public boolean hasCustomRoles() {
        return customRoles;
    }

    /**
     * AUTH-014 Phase 4 item 4-5: log a warning for config flags that are
     * accepted but not yet honored at runtime. Callers should invoke this
     * once at startup so operators see the gap.
     */
    public java.util.List<String> unimplementedWarnings() {
        var out = new java.util.ArrayList<String>();
        if (isolation == VoltaConfig.TenancyConfig.Isolation.SCHEMA) {
            out.add("tenancy.isolation=schema — config accepted but schema-per-tenant migration is deferred");
        }
        if (isolation == VoltaConfig.TenancyConfig.Isolation.DATABASE) {
            out.add("tenancy.isolation=database — config accepted but not yet implemented");
        }
        if (customRoles) {
            out.add("tenancy.custom_roles=true — config accepted but the custom-role loader is not yet implemented");
        }
        if (routing.mode() == VoltaConfig.TenancyConfig.Routing.Mode.DOMAIN
                && routing.baseDomain() != null && !routing.baseDomain().isBlank()) {
            out.add("tenancy.routing=domain — base_domain is configured but only the explicit tenant_domains lookup is used; base_domain has no effect in DOMAIN mode.");
        }
        return out;
    }

    /**
     * AUTH-014 Phase 4 item 2: compute the effective {@code Domain=} attribute
     * for session cookies.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>When the volta-config.yaml has a non-default routing section:
     *       SHARED + baseDomain → {@code .<baseDomain>}; ISOLATED → empty.</li>
     *   <li>Otherwise fall back to the caller-supplied value (typically
     *       {@code System.getenv("COOKIE_DOMAIN")}), preserving pre-Phase-4
     *       behaviour.</li>
     * </ol>
     *
     * @param envFallback the legacy {@code COOKIE_DOMAIN} env value
     * @return cookie Domain attribute value, or empty string for "no Domain attr"
     */
    public String effectiveCookieDomain(String envFallback) {
        boolean configured = routing.mode() != VoltaConfig.TenancyConfig.Routing.Mode.NONE
                || (routing.baseDomain() != null && !routing.baseDomain().isBlank());
        if (configured) {
            if (routing.cookieScope() == VoltaConfig.TenancyConfig.Routing.CookieScope.ISOLATED) {
                return "";
            }
            // SHARED (default): use baseDomain with a leading dot if present,
            // otherwise fall through to env fallback.
            String base = routing.baseDomain();
            if (base != null && !base.isBlank()) {
                return base.startsWith(".") ? base : "." + base;
            }
        }
        return envFallback == null ? "" : envFallback;
    }

    /**
     * AUTH-014 Phase 4: extract the tenant slug from a host header when
     * subdomain routing is active and {@code routing.baseDomain} is set.
     *
     * <p>Examples with {@code baseDomain=unlaxer.org}:
     * <pre>
     *   acme.unlaxer.org            → "acme"
     *   app.acme.unlaxer.org        → "acme"  (strip app. / admin. etc prefixes)
     *   unlaxer.org                 → null    (base domain itself)
     *   www.unlaxer.org             → null    (www alias ignored)
     *   acme.other.tld              → null    (different base domain)
     *   null / ""                   → null
     * </pre>
     *
     * <p>App-subdomain handling: when the left-most label is one of the
     * reserved app names ({@code www, app, admin, api, auth}) the next
     * label is treated as the tenant. This lets operators run
     * {@code auth.acme.unlaxer.org} while still scoping to tenant "acme".
     */
    public String slugFromHost(String host) {
        if (!isSubdomainRouting() || host == null || host.isBlank()) return null;
        String baseDomain = routing.baseDomain();
        if (baseDomain == null || baseDomain.isBlank()) return null;

        // Strip port if present (host:443)
        int colon = host.indexOf(':');
        String hostname = colon >= 0 ? host.substring(0, colon) : host;
        hostname = hostname.toLowerCase(java.util.Locale.ROOT);

        if (hostname.equals(baseDomain.toLowerCase(java.util.Locale.ROOT))) return null;
        String suffix = "." + baseDomain.toLowerCase(java.util.Locale.ROOT);
        if (!hostname.endsWith(suffix)) return null;

        String prefix = hostname.substring(0, hostname.length() - suffix.length());
        if (prefix.isEmpty()) return null;

        // Skip over reserved app-subdomain labels from the left. If every
        // label is reserved (e.g. the bare "www.unlaxer.org" case), there
        // is no tenant slug and we must return null.
        String[] labels = prefix.split("\\.");
        java.util.Set<String> reserved = java.util.Set.of("www", "app", "admin", "api", "auth", "console");
        int i = 0;
        while (i < labels.length && reserved.contains(labels[i])) i++;
        if (i >= labels.length) return null;
        String slug = labels[i];
        return slug.isBlank() ? null : slug;
    }

    /**
     * Extract the tenant slug from a forwarded URI when slug routing is
     * enabled. Returns {@code null} when routing is off or the URI does not
     * start with the configured slug prefix.
     *
     * <p>Examples with default {@code slugPrefix=/o/}:
     * <pre>
     *   /o/acme/services      → "acme"
     *   /o/acme/              → "acme"
     *   /o/acme               → "acme"
     *   /settings             → null
     *   null                  → null
     * </pre>
     */
    public String slugFromPath(String path) {
        if (!isSlugRouting() || path == null || path.isBlank()) return null;
        String prefix = routing.slugPrefix();
        if (prefix == null || prefix.isBlank() || !path.startsWith(prefix)) return null;
        String rest = path.substring(prefix.length());
        if (rest.isEmpty()) return null;
        int end = rest.indexOf('/');
        String slug = end < 0 ? rest : rest.substring(0, end);
        // Strip query / fragment if present (the path parameter usually has
        // them stripped already, but be defensive).
        int q = slug.indexOf('?');
        if (q >= 0) slug = slug.substring(0, q);
        int h = slug.indexOf('#');
        if (h >= 0) slug = slug.substring(0, h);
        return slug.isBlank() ? null : slug;
    }

    public boolean isSingle() {
        return mode == VoltaConfig.TenancyConfig.Mode.SINGLE;
    }

    public boolean isMulti() {
        return mode == VoltaConfig.TenancyConfig.Mode.MULTI;
    }

    /**
     * Should the auth flow redirect the user to {@code /select-tenant}?
     * True only in {@code MULTI} mode with more than one membership.
     */
    public boolean shouldSelectTenant(int tenantCount) {
        return isMulti() && tenantCount > 1;
    }
}
