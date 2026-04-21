package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AUTH-014 Phase 1: verify the new tenancy section parses correctly. */
class ConfigLoaderTest {

    @Test
    void missingTenancySectionYieldsDefaults() {
        String yaml = "version: 1\nidp: []\n";
        VoltaConfig cfg = ConfigLoader.parse(yaml);
        assertEquals(VoltaConfig.TenancyConfig.defaults(), cfg.tenancy());
    }

    @Test
    void fullTenancySectionIsParsed() {
        String yaml = """
                version: 1
                tenancy:
                  mode: multi
                  creation_policy: invite_only
                  max_orgs_per_user: 5
                  shadow_org: false
                  slug_format: "{name}-{random8}"
                """;
        VoltaConfig cfg = ConfigLoader.parse(yaml);
        var t = cfg.tenancy();
        assertEquals(VoltaConfig.TenancyConfig.Mode.MULTI, t.mode());
        assertEquals(VoltaConfig.TenancyConfig.CreationPolicy.INVITE_ONLY, t.creationPolicy());
        assertEquals(5, t.maxOrgsPerUser());
        assertFalse(t.shadowOrg());
        assertEquals("{name}-{random8}", t.slugFormat());
    }

    @Test
    void creationPolicyDisabled() {
        String yaml = "tenancy:\n  creation_policy: disabled\n";
        VoltaConfig cfg = ConfigLoader.parse(yaml);
        assertEquals(VoltaConfig.TenancyConfig.CreationPolicy.DISABLED, cfg.tenancy().creationPolicy());
    }

    @Test
    void creationPolicyAdminOnlyAcceptsBothKebabAndSnake() {
        // The spec uses snake_case (admin_only). Be permissive to kebab-case
        // to avoid subtle breakage when users type what the docs suggest.
        assertEquals(VoltaConfig.TenancyConfig.CreationPolicy.ADMIN_ONLY,
                ConfigLoader.parse("tenancy:\n  creation_policy: admin_only\n")
                        .tenancy().creationPolicy());
        assertEquals(VoltaConfig.TenancyConfig.CreationPolicy.ADMIN_ONLY,
                ConfigLoader.parse("tenancy:\n  creation_policy: admin-only\n")
                        .tenancy().creationPolicy());
    }

    @Test
    void unknownPolicyFallsBackToDefaults() {
        // Bogus values don't raise — keep the default to preserve uptime when
        // a typo would otherwise take the service down.
        String yaml = "tenancy:\n  creation_policy: yolo\n";
        VoltaConfig cfg = ConfigLoader.parse(yaml);
        assertEquals(VoltaConfig.TenancyConfig.defaults().creationPolicy(),
                cfg.tenancy().creationPolicy());
    }

    @Test
    void unknownModeFallsBackToDefault() {
        String yaml = "tenancy:\n  mode: galactic\n";
        VoltaConfig cfg = ConfigLoader.parse(yaml);
        assertEquals(VoltaConfig.TenancyConfig.defaults().mode(), cfg.tenancy().mode());
    }

    @Test
    void emptyYamlGivesEmptyConfig() {
        assertEquals(VoltaConfig.empty().tenancy(), ConfigLoader.parse("").tenancy());
    }

    @Test
    void defaultsAreSingleAndDisabled() {
        var d = VoltaConfig.TenancyConfig.defaults();
        assertEquals(VoltaConfig.TenancyConfig.Mode.SINGLE, d.mode());
        assertEquals(VoltaConfig.TenancyConfig.CreationPolicy.DISABLED, d.creationPolicy());
        assertEquals(1, d.maxOrgsPerUser());
        assertTrue(d.shadowOrg());
    }
}
