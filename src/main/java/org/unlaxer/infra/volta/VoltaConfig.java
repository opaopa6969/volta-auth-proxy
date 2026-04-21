package org.unlaxer.infra.volta;

import java.util.List;
import java.util.Map;

/**
 * Typed representation of {@code volta-config.yaml}.
 *
 * <p>Credentials ({@code client_id}, {@code client_secret}) are resolved
 * from {@code ${ENV_VAR}} references by {@link ConfigLoader} before this
 * record is created.  An entry whose {@code clientId} is blank is treated
 * as disabled (the matching env var was not set).
 *
 * <p>{@code tenancy} is optional — when absent, {@link TenancyConfig#defaults()}
 * is used and callers should fall back to the equivalent AppConfig env
 * vars (e.g. {@code ALLOW_SELF_SERVICE_TENANT}). See AUTH-014 Phase 1.
 */
public record VoltaConfig(int version, List<IdpEntry> idp, TenancyConfig tenancy) {

    public record IdpEntry(String id, String clientId, String clientSecret,
                           Map<String, String> extra) {
        /** True when the client_id resolved to a non-blank value. */
        public boolean isEnabled() {
            return clientId != null && !clientId.isBlank();
        }
    }

    /**
     * {@code tenancy:} section. See docs/TENANT-SPEC.md §4.
     *
     * <p>All fields have sensible defaults so a YAML file without this
     * section degrades to single-tenant behaviour matching the legacy
     * {@code allowSelfServiceTenant=false} mode.
     */
    public record TenancyConfig(Mode mode, CreationPolicy creationPolicy,
                                int maxOrgsPerUser, boolean shadowOrg,
                                String slugFormat) {
        public enum Mode { SINGLE, MULTI }
        public enum CreationPolicy { DISABLED, AUTO, ADMIN_ONLY, INVITE_ONLY }

        public static TenancyConfig defaults() {
            return new TenancyConfig(
                    Mode.SINGLE,
                    CreationPolicy.DISABLED,
                    1,
                    true,
                    "{name}-{random6}"
            );
        }
    }

    public static VoltaConfig empty() {
        return new VoltaConfig(1, List.of(), TenancyConfig.defaults());
    }

    /** True when the YAML file contained an {@code idp:} section. */
    public boolean hasIdpSection() {
        return !idp.isEmpty();
    }
}
