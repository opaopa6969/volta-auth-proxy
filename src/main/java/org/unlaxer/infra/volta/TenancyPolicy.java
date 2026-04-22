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

    public TenancyPolicy(VoltaConfig voltaConfig) {
        this(voltaConfig == null
                ? VoltaConfig.TenancyConfig.defaults().mode()
                : voltaConfig.tenancy().mode(),
             voltaConfig == null
                ? VoltaConfig.TenancyConfig.defaults().creationPolicy()
                : voltaConfig.tenancy().creationPolicy(),
             voltaConfig == null
                ? VoltaConfig.TenancyConfig.Routing.defaults()
                : voltaConfig.tenancy().routing());
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
