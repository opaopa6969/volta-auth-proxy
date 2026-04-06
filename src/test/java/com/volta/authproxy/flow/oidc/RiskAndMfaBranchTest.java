package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.flow.FlowContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.volta.authproxy.flow.oidc.OidcFlowData.*;
import static org.junit.jupiter.api.Assertions.*;

class RiskAndMfaBranchTest {
    private final RiskAndMfaBranch branch = new RiskAndMfaBranch();

    @Test
    void lowRisk_noMfa_complete() {
        var ctx = makeContext(1, false, false);
        assertEquals("no_mfa", branch.decide(ctx));
    }

    @Test
    void lowRisk_mfaRequired_mfaPending() {
        var ctx = makeContext(1, true, false);
        assertEquals("mfa_required", branch.decide(ctx));
    }

    @Test
    void highRisk_noMfa_mfaPending() {
        var ctx = makeContext(4, false, false);
        assertEquals("mfa_required", branch.decide(ctx));
    }

    @Test
    void maxRisk_blocked() {
        var ctx = makeContext(5, false, false);
        assertEquals("blocked", branch.decide(ctx));
    }

    @Test
    void newDevice_stepUp_mfaRequired() {
        var ctx = makeContext(2, true, true);
        assertEquals("mfa_required", branch.decide(ctx));
    }

    private FlowContext makeContext(int riskLevel, boolean mfaRequired, boolean isNewDevice) {
        var ctx = new FlowContext("test");
        ctx.put(ResolvedUser.class, new ResolvedUser(
                UUID.randomUUID(), "test@example.com", "Test",
                UUID.randomUUID(), "Tenant", "tenant",
                List.of("MEMBER"), null, mfaRequired));
        ctx.put(RiskCheckResult.class, new RiskCheckResult(
                riskLevel, 4, 5, "step_up", isNewDevice));
        return ctx;
    }
}
