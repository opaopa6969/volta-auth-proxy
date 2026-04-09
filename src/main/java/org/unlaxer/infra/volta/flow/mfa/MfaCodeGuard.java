package org.unlaxer.infra.volta.flow.mfa;

import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.util.Map;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.mfa.MfaFlowData.*;

/**
 * CHALLENGE_SHOWN → VERIFIED: Validates that a code or recovery code is submitted.
 * Actual TOTP verification happens in MfaVerifyProcessor.
 */
public final class MfaCodeGuard implements TransitionGuard {

    @Override public String name() { return "MfaCodeGuard"; }
    @Override public Set<Class<?>> requires() { return Set.of(MfaSessionContext.class); }
    @Override public Set<Class<?>> produces() { return Set.of(MfaCodeSubmission.class); }
    @Override public int maxRetries() { return 5; }

    @Override
    public GuardOutput validate(FlowContext ctx) {
        var submissionOpt = ctx.find(MfaCodeSubmission.class);
        if (submissionOpt.isEmpty()) {
            return new GuardOutput.Rejected("Missing code submission");
        }
        MfaCodeSubmission submission = submissionOpt.get();
        // At least one of code or recovery_code must be present
        if (submission.code() == 0 && (submission.recoveryCode() == null || submission.recoveryCode().isBlank())) {
            return new GuardOutput.Rejected("Code or recovery code required");
        }
        return new GuardOutput.Accepted(Map.of(MfaCodeSubmission.class, submission));
    }
}
