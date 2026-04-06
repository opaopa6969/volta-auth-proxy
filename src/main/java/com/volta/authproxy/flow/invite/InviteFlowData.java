package com.volta.authproxy.flow.invite;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.volta.authproxy.flow.FlowData;
import com.volta.authproxy.flow.Sensitive;

import java.util.List;
import java.util.UUID;

public final class InviteFlowData {
    private InviteFlowData() {}

    /** Initial invite context — produced by router. */
    @FlowData("invite.context")
    public record InviteContext(
            @JsonProperty("invitation_id") UUID invitationId,
            @JsonProperty("invitation_code") String invitationCode,
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("tenant_name") String tenantName,
            @JsonProperty("invite_email") String inviteEmail,
            @JsonProperty("invite_role") String inviteRole,
            @JsonProperty("user_id") UUID userId,
            @Sensitive @JsonProperty("user_email") String userEmail,
            @JsonProperty("session_id") UUID sessionId
    ) {}

    /** Accept submission — injected by router as external data. */
    @FlowData("invite.accept_submission")
    public record InviteAcceptSubmission(
            @JsonProperty("confirmed") boolean confirmed
    ) {}

    /** Accepted result — produced by InviteAcceptProcessor. */
    @FlowData("invite.accepted")
    public record InviteAccepted(
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("role") String role
    ) {}

    /** Completed — produced by InviteCompleteProcessor. */
    @FlowData("invite.completed")
    public record InviteCompleted(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("redirect_to") String redirectTo
    ) {}
}
