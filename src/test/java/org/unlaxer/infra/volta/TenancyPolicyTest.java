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
}
