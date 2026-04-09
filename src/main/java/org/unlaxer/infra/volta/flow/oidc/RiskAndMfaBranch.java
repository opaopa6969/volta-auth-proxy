package org.unlaxer.infra.volta.flow.oidc;

import org.unlaxer.tramli.BranchProcessor;
import org.unlaxer.tramli.FlowContext;

import java.util.Set;

import static org.unlaxer.infra.volta.flow.oidc.OidcFlowData.*;

/**
 * RISK_CHECKED → COMPLETE / COMPLETE_MFA_PENDING / BLOCKED
 * Replaces MfaCheckBranch with risk-aware 3-way branching.
 */
public final class RiskAndMfaBranch implements BranchProcessor {
    public static final String NO_MFA = "no_mfa";
    public static final String MFA_REQUIRED = "mfa_required";
    public static final String BLOCKED = "blocked";

    @Override public String name() { return "RiskAndMfaBranch"; }
    @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class, RiskCheckResult.class); }

    @Override
    public String decide(FlowContext ctx) {
        ResolvedUser user = ctx.get(ResolvedUser.class);
        RiskCheckResult risk = ctx.get(RiskCheckResult.class);

        // Block if risk >= block threshold
        if (risk.riskLevel() >= risk.blockThreshold()) {
            return BLOCKED;
        }

        // Require MFA if: user has MFA required OR risk >= action threshold
        if (user.mfaRequired() || risk.riskLevel() >= risk.actionThreshold()) {
            return MFA_REQUIRED;
        }

        // Step-up for new device (if tenant policy says step_up)
        if (risk.isNewDevice() && "step_up".equals(risk.deviceAction()) && user.mfaRequired()) {
            return MFA_REQUIRED;
        }

        return NO_MFA;
    }
}
