package com.volta.authproxy.flow.passkey;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.volta.authproxy.flow.FlowData;
import com.volta.authproxy.flow.Sensitive;

import java.util.List;
import java.util.UUID;

public final class PasskeyFlowData {
    private PasskeyFlowData() {}

    /** Initial request — produced by router. */
    @FlowData("passkey.request")
    public record PasskeyRequest(
            @JsonProperty("client_ip") String clientIp,
            @JsonProperty("user_agent") String userAgent,
            @JsonProperty("return_to") String returnTo
    ) {}

    /** Challenge — produced by PasskeyChallengeProcessor. */
    @FlowData("passkey.challenge")
    public record PasskeyChallenge(
            @Sensitive @JsonProperty("challenge") String challengeB64,
            @JsonProperty("rp_id") String rpId,
            @JsonProperty("rp_name") String rpName,
            @JsonProperty("timeout") int timeout
    ) {}

    /** Assertion from browser — injected by router as external data. */
    @FlowData("passkey.assertion")
    public record PasskeyAssertion(
            @JsonProperty("credential_id") String credentialIdB64,
            @Sensitive @JsonProperty("client_data_json") String clientDataJsonB64,
            @Sensitive @JsonProperty("authenticator_data") String authenticatorDataB64,
            @Sensitive @JsonProperty("signature") String signatureB64,
            @JsonProperty("user_handle") String userHandleB64
    ) {}

    /** Verified user — produced by PasskeyVerifyProcessor. */
    @FlowData("passkey.verified_user")
    public record PasskeyVerifiedUser(
            @JsonProperty("user_id") UUID userId,
            @Sensitive @JsonProperty("email") String email,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("tenant_name") String tenantName,
            @JsonProperty("tenant_slug") String tenantSlug,
            @JsonProperty("roles") List<String> roles
    ) {}

    /** Issued session — produced by PasskeySessionProcessor. */
    @FlowData("passkey.issued_session")
    public record PasskeyIssuedSession(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("redirect_to") String redirectTo
    ) {}
}
