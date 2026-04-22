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

    public TenancyPolicy(VoltaConfig voltaConfig) {
        this(voltaConfig == null
                ? VoltaConfig.TenancyConfig.defaults().mode()
                : voltaConfig.tenancy().mode(),
             voltaConfig == null
                ? VoltaConfig.TenancyConfig.defaults().creationPolicy()
                : voltaConfig.tenancy().creationPolicy());
    }

    public TenancyPolicy(VoltaConfig.TenancyConfig.Mode mode) {
        this(mode, VoltaConfig.TenancyConfig.defaults().creationPolicy());
    }

    public TenancyPolicy(VoltaConfig.TenancyConfig.Mode mode,
                         VoltaConfig.TenancyConfig.CreationPolicy creationPolicy) {
        this.mode = mode == null ? VoltaConfig.TenancyConfig.Mode.SINGLE : mode;
        this.creationPolicy = creationPolicy == null
                ? VoltaConfig.TenancyConfig.defaults().creationPolicy()
                : creationPolicy;
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
