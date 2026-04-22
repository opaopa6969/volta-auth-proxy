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
                                String slugFormat, Routing routing,
                                Isolation isolation, boolean customRoles) {
        public enum Mode { SINGLE, MULTI }
        public enum CreationPolicy { DISABLED, AUTO, ADMIN_ONLY, INVITE_ONLY }

        /**
         * AUTH-014 Phase 4 item 4: data isolation strategy.
         *
         * <ul>
         *   <li>{@code SHARED}   — all tenants share tables; {@code tenant_id} column scopes rows. Default.</li>
         *   <li>{@code SCHEMA}   — PostgreSQL schema per tenant. (Scaffolded; migration work deferred to a dedicated issue.)</li>
         *   <li>{@code DATABASE} — database per tenant. (Scaffolded; not yet implemented.)</li>
         * </ul>
         */
        public enum Isolation { SHARED, SCHEMA, DATABASE }

        /**
         * AUTH-014 Phase 2 item 3: how the request URL identifies which
         * tenant scope is active.
         *
         * <ul>
         *   <li>{@code NONE}      — no URL-level tenancy; session tenant is authoritative.</li>
         *   <li>{@code SLUG}      — URL path prefix {@code /o/:slug/} (or configured {@code slugPrefix}) selects tenant.</li>
         *   <li>{@code SUBDOMAIN} — {@code tenant.baseDomain} (Phase 4).</li>
         *   <li>{@code DOMAIN}    — custom per-tenant domain (Phase 4).</li>
         * </ul>
         */
        public record Routing(Mode mode, String baseDomain, String slugHeader, String slugPrefix,
                              CookieScope cookieScope) {
            public enum Mode { NONE, SLUG, SUBDOMAIN, DOMAIN }

            /**
             * AUTH-014 Phase 4 item 2: how session cookies cross subdomains.
             * {@code SHARED}   → {@code Domain=.<baseDomain>} so a login on
             *                    auth.x.org is visible on acme.x.org too.
             *                    Enables SSO-like flow across tenants.
             * {@code ISOLATED} → no {@code Domain} attribute; the cookie is
             *                    scoped to the exact host. Each tenant has a
             *                    separate session.
             */
            public enum CookieScope { SHARED, ISOLATED }

            public static Routing defaults() {
                return new Routing(Mode.NONE, "", "X-Volta-Tenant-Slug", "/o/", CookieScope.SHARED);
            }

            // Back-compat for older callers / tests that don't pass cookieScope.
            public Routing(Mode mode, String baseDomain, String slugHeader, String slugPrefix) {
                this(mode, baseDomain, slugHeader, slugPrefix, CookieScope.SHARED);
            }
        }

        public static TenancyConfig defaults() {
            return new TenancyConfig(
                    Mode.SINGLE,
                    CreationPolicy.DISABLED,
                    1,
                    true,
                    "{name}-{random6}",
                    Routing.defaults(),
                    Isolation.SHARED,
                    false
            );
        }

        // Backward-compat constructor used by older call sites / tests that
        // don't yet supply a routing block.
        public TenancyConfig(Mode mode, CreationPolicy creationPolicy,
                             int maxOrgsPerUser, boolean shadowOrg, String slugFormat) {
            this(mode, creationPolicy, maxOrgsPerUser, shadowOrg, slugFormat, Routing.defaults(),
                 Isolation.SHARED, false);
        }

        // Back-compat for the routing-only constructor (Phase 2 code).
        public TenancyConfig(Mode mode, CreationPolicy creationPolicy,
                             int maxOrgsPerUser, boolean shadowOrg,
                             String slugFormat, Routing routing) {
            this(mode, creationPolicy, maxOrgsPerUser, shadowOrg, slugFormat, routing,
                 Isolation.SHARED, false);
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
