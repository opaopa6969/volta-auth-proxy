package org.unlaxer.infra.volta.flow.mfa;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.unlaxer.infra.volta.flow.FlowData;
import org.unlaxer.infra.volta.flow.Sensitive;

import java.util.UUID;

public final class MfaFlowData {
    private MfaFlowData() {}

    /** Session context — produced by router when starting MFA flow. */
    @FlowData("mfa.session_context")
    public record MfaSessionContext(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("return_to") String returnTo
    ) {}

    /** Code submission — injected by router as external data. */
    @FlowData("mfa.code_submission")
    public record MfaCodeSubmission(
            @JsonProperty("code") int code,
            @Sensitive @JsonProperty("recovery_code") String recoveryCode
    ) {}

    /** Verification result — produced by MfaVerifyProcessor. */
    @FlowData("mfa.verified")
    public record MfaVerified(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("redirect_to") String redirectTo
    ) {}
}
