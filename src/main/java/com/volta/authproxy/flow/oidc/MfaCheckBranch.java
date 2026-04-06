package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.flow.BranchProcessor;
import com.volta.authproxy.flow.FlowContext;

import java.util.Set;

import static com.volta.authproxy.flow.oidc.OidcFlowData.*;

/**
 * USER_RESOLVED → COMPLETE or COMPLETE_MFA_PENDING: Decides based on MFA requirement.
 */
public final class MfaCheckBranch implements BranchProcessor {
    public static final String NO_MFA = "no_mfa";
    public static final String MFA_REQUIRED = "mfa_required";

    @Override public String name() { return "MfaCheckBranch"; }
    @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class); }

    @Override
    public String decide(FlowContext ctx) {
        ResolvedUser user = ctx.get(ResolvedUser.class);
        return user.mfaRequired() ? MFA_REQUIRED : NO_MFA;
    }
}
