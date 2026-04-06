package com.volta.authproxy.flow.invite;

import com.volta.authproxy.flow.*;

import java.util.Map;
import java.util.Set;

import static com.volta.authproxy.flow.invite.InviteFlowData.*;

/**
 * ACCOUNT_SWITCHING → ACCEPTED: Validates that the user has re-authenticated
 * after account switch. The router updates InviteContext with the new user info
 * before calling resumeAndExecute.
 */
public final class ResumeGuard implements TransitionGuard {

    @Override public String name() { return "ResumeGuard"; }
    @Override public Set<Class<?>> requires() { return Set.of(InviteContext.class); }
    @Override public Set<Class<?>> produces() { return Set.of(InviteAcceptSubmission.class); }
    @Override public int maxRetries() { return 3; }

    @Override
    public GuardOutput validate(FlowContext ctx) {
        var submissionOpt = ctx.find(InviteAcceptSubmission.class);
        if (submissionOpt.isEmpty()) {
            return new GuardOutput.Rejected("Awaiting re-authentication");
        }
        if (!submissionOpt.get().confirmed()) {
            return new GuardOutput.Rejected("Accept not confirmed");
        }
        return new GuardOutput.Accepted(Map.of(InviteAcceptSubmission.class, submissionOpt.get()));
    }
}
