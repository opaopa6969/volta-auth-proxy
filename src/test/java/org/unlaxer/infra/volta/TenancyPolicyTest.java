package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AUTH-014 Phase 2 item 1: single-vs-multi mode policy behaviour. */
class TenancyPolicyTest {

    @Test
    void defaultsToSingleMode() {
        TenancyPolicy p = new TenancyPolicy((VoltaConfig) null);
        assertTrue(p.isSingle());
        assertFalse(p.isMulti());
    }

    @Test
    void emptyVoltaConfigIsSingle() {
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.empty());
        assertTrue(p.isSingle());
    }

    @Test
    void multiModeHonored() {
        String yaml = "tenancy:\n  mode: multi\n";
        VoltaConfig cfg = ConfigLoader.parse(yaml);
        TenancyPolicy p = new TenancyPolicy(cfg);
        assertTrue(p.isMulti());
        assertFalse(p.isSingle());
    }

    @Test
    void shouldSelectTenantNeverInSingleMode() {
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.TenancyConfig.Mode.SINGLE);
        assertFalse(p.shouldSelectTenant(0));
        assertFalse(p.shouldSelectTenant(1));
        assertFalse(p.shouldSelectTenant(5));
        assertFalse(p.shouldSelectTenant(999));
    }

    @Test
    void shouldSelectTenantOnlyWithMultipleInMulti() {
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.TenancyConfig.Mode.MULTI);
        assertFalse(p.shouldSelectTenant(0));
        assertFalse(p.shouldSelectTenant(1));
        assertTrue(p.shouldSelectTenant(2));
        assertTrue(p.shouldSelectTenant(42));
    }

    @Test
    void nullModeDegradesToSingle() {
        TenancyPolicy p = new TenancyPolicy((VoltaConfig.TenancyConfig.Mode) null);
        assertTrue(p.isSingle());
    }

    // ── Phase 2 item 3: slug routing ────────────────────────────────────────

    @Test
    void slugRoutingDefaultsToNone() {
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.empty());
        assertFalse(p.isSlugRouting());
        assertNull(p.slugFromPath("/o/acme/services"));
    }

    @Test
    void slugRoutingEnabled() {
        String yaml = """
                tenancy:
                  mode: multi
                  routing:
                    mode: slug
                """;
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertTrue(p.isSlugRouting());
        assertEquals("/o/", p.routing().slugPrefix());
    }

    @Test
    void slugFromPathHappyCases() {
        String yaml = "tenancy:\n  routing:\n    mode: slug\n";
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertEquals("acme",    p.slugFromPath("/o/acme/services"));
        assertEquals("acme",    p.slugFromPath("/o/acme/"));
        assertEquals("acme",    p.slugFromPath("/o/acme"));
        assertEquals("acme-co", p.slugFromPath("/o/acme-co/deep/path?q=x"));
    }

    @Test
    void slugFromPathSadCases() {
        String yaml = "tenancy:\n  routing:\n    mode: slug\n";
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertNull(p.slugFromPath(null));
        assertNull(p.slugFromPath(""));
        assertNull(p.slugFromPath("/settings"));
        assertNull(p.slugFromPath("/o/"));         // no slug after prefix
        assertNull(p.slugFromPath("/o"));          // missing trailing slash
        assertNull(p.slugFromPath("/other/acme")); // wrong prefix
    }

    @Test
    void customSlugPrefix() {
        String yaml = """
                tenancy:
                  routing:
                    mode: slug
                    slug_prefix: /t
                """;
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertEquals("/t/", p.routing().slugPrefix()); // auto-normalized to leading + trailing
        assertEquals("acme", p.slugFromPath("/t/acme/x"));
        assertNull(p.slugFromPath("/o/acme/x"));
    }

    @Test
    void nonSlugModeDoesNotParseSlug() {
        // routing.mode=none — even a /o/... path should not be interpreted.
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.empty());
        assertNull(p.slugFromPath("/o/acme/services"));
    }

    // ── Phase 4 item 1: subdomain routing ──────────────────────────────────

    @Test
    void subdomainRoutingDefaultsOff() {
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.empty());
        assertFalse(p.isSubdomainRouting());
        assertNull(p.slugFromHost("acme.unlaxer.org"));
    }

    @Test
    void subdomainRoutingExtractsSlug() {
        String yaml = """
                tenancy:
                  routing:
                    mode: subdomain
                    base_domain: unlaxer.org
                """;
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertTrue(p.isSubdomainRouting());
        assertEquals("acme",    p.slugFromHost("acme.unlaxer.org"));
        assertEquals("acme",    p.slugFromHost("acme.unlaxer.org:443"));
        assertEquals("acme",    p.slugFromHost("ACME.UNLAXER.ORG"));
        assertEquals("acme",    p.slugFromHost("auth.acme.unlaxer.org"));
        assertEquals("acme",    p.slugFromHost("admin.api.acme.unlaxer.org"));
        assertEquals("acme-co", p.slugFromHost("acme-co.unlaxer.org"));
    }

    @Test
    void subdomainRoutingRejectsBadHosts() {
        String yaml = """
                tenancy:
                  routing:
                    mode: subdomain
                    base_domain: unlaxer.org
                """;
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertNull(p.slugFromHost(null));
        assertNull(p.slugFromHost(""));
        assertNull(p.slugFromHost("unlaxer.org"));         // base domain only
        assertNull(p.slugFromHost("www.unlaxer.org"));     // reserved with no tenant after
        assertNull(p.slugFromHost("acme.example.com"));    // different base domain
        assertNull(p.slugFromHost("malicious-unlaxer.org"));// not a subdomain
    }

    @Test
    void subdomainRoutingNoBaseDomainDegrades() {
        String yaml = "tenancy:\n  routing:\n    mode: subdomain\n";
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        // Mode is subdomain but base_domain is empty — host extraction returns null.
        assertTrue(p.isSubdomainRouting());
        assertNull(p.slugFromHost("acme.unlaxer.org"));
    }

    // ── Phase 4 item 2: cookie scope ───────────────────────────────────────

    @Test
    void cookieDomainDefaultsToEnvFallback() {
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.empty());
        assertEquals(".unlaxer.org", p.effectiveCookieDomain(".unlaxer.org"));
        assertEquals("", p.effectiveCookieDomain(""));
    }

    @Test
    void cookieDomainSharedScopeAddsLeadingDot() {
        String yaml = """
                tenancy:
                  routing:
                    mode: subdomain
                    base_domain: unlaxer.org
                    cookie_scope: shared
                """;
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        // Config overrides env fallback.
        assertEquals(".unlaxer.org", p.effectiveCookieDomain(""));
        assertEquals(".unlaxer.org", p.effectiveCookieDomain("existing.env"));
    }

    @Test
    void cookieDomainIsolatedScopeReturnsEmpty() {
        String yaml = """
                tenancy:
                  routing:
                    mode: subdomain
                    base_domain: unlaxer.org
                    cookie_scope: isolated
                """;
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertEquals("", p.effectiveCookieDomain(".unlaxer.org"));
    }

    @Test
    void cookieDomainHonorsExplicitLeadingDot() {
        String yaml = """
                tenancy:
                  routing:
                    mode: subdomain
                    base_domain: .example.com
                    cookie_scope: shared
                """;
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertEquals(".example.com", p.effectiveCookieDomain(""));
    }

    // ── Phase 4 item 4-5: isolation + custom_roles scaffolding ─────────────

    @Test
    void isolationDefaultsToShared() {
        TenancyPolicy p = new TenancyPolicy(VoltaConfig.empty());
        assertEquals(VoltaConfig.TenancyConfig.Isolation.SHARED, p.isolation());
        assertFalse(p.hasCustomRoles());
        assertTrue(p.unimplementedWarnings().isEmpty());
    }

    @Test
    void isolationSchemaEmitsWarning() {
        String yaml = "tenancy:\n  isolation: schema\n";
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertEquals(VoltaConfig.TenancyConfig.Isolation.SCHEMA, p.isolation());
        assertTrue(p.unimplementedWarnings().stream().anyMatch(s -> s.contains("isolation=schema")));
    }

    @Test
    void customRolesFlagEmitsWarning() {
        String yaml = "tenancy:\n  custom_roles: true\n";
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertTrue(p.hasCustomRoles());
        assertTrue(p.unimplementedWarnings().stream().anyMatch(s -> s.contains("custom_roles=true")));
    }

    @Test
    void creationPolicyExposedForDiscoveryUi() {
        // Phase 2 item 2: tenant-select.jte reads the policy to decide
        // what empty-state CTA to show. Verify the plumbing.
        String yaml = "tenancy:\n  mode: multi\n  creation_policy: auto\n";
        TenancyPolicy p = new TenancyPolicy(ConfigLoader.parse(yaml));
        assertEquals(VoltaConfig.TenancyConfig.CreationPolicy.AUTO, p.creationPolicy());
        assertTrue(p.allowsSelfServiceCreation());

        TenancyPolicy disabled = new TenancyPolicy(VoltaConfig.empty());
        assertEquals(VoltaConfig.TenancyConfig.CreationPolicy.DISABLED, disabled.creationPolicy());
        assertFalse(disabled.allowsSelfServiceCreation());
    }
}
