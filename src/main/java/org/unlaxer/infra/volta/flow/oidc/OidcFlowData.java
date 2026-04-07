package org.unlaxer.infra.volta.flow.oidc;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.unlaxer.infra.volta.flow.FlowData;
import org.unlaxer.infra.volta.flow.Sensitive;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All @FlowData records for the OIDC flow.
 * Each record maps to one step's input/output in the flow.
 */
public final class OidcFlowData {
    private OidcFlowData() {}

    /** Initial request data — produced by the router, consumed by OidcInitProcessor. */
    @FlowData("oidc.request")
    public record OidcRequest(
            @JsonProperty("provider") String provider,
            @JsonProperty("return_to") String returnTo,
            @JsonProperty("invite_code") String inviteCode,
            @JsonProperty("client_ip") String clientIp,
            @JsonProperty("user_agent") String userAgent
    ) {}

    /** Redirect data — produced by OidcInitProcessor. */
    @FlowData("oidc.redirect")
    public record OidcRedirect(
            @JsonProperty("authorization_url") String authorizationUrl,
            @Sensitive @JsonProperty("nonce") String nonce,
            @Sensitive @JsonProperty("code_verifier") String codeVerifier,
            @JsonProperty("provider_id") String providerId,
            @JsonProperty("expires_at") Instant expiresAt
    ) {}

    /** Callback data — produced by the router from HTTP params, consumed by OidcCallbackGuard. */
    @FlowData("oidc.callback")
    public record OidcCallback(
            @Sensitive @JsonProperty("code") String code,
            @JsonProperty("state") String state
    ) {}

    /** Token exchange result — produced by OidcTokenExchangeProcessor. */
    @FlowData("oidc.tokens")
    public record OidcTokens(
            @JsonProperty("sub") String sub,
            @Sensitive @JsonProperty("email") String email,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("email_verified") boolean emailVerified,
            @JsonProperty("return_to") String returnTo,
            @JsonProperty("invite_code") String inviteCode,
            @JsonProperty("provider") String provider
    ) {}

    /** Resolved user + tenant — produced by UserResolveProcessor. */
    @FlowData("oidc.resolved_user")
    public record ResolvedUser(
            @JsonProperty("user_id") UUID userId,
            @Sensitive @JsonProperty("email") String email,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("tenant_name") String tenantName,
            @JsonProperty("tenant_slug") String tenantSlug,
            @JsonProperty("roles") List<String> roles,
            @JsonProperty("session_return_to") String sessionReturnTo,
            @JsonProperty("mfa_required") boolean mfaRequired
    ) {}

    /** Risk check result — produced by RiskCheckProcessor. */
    @FlowData("oidc.risk_check")
    public record RiskCheckResult(
            @JsonProperty("risk_level") int riskLevel,
            @JsonProperty("action_threshold") int actionThreshold,
            @JsonProperty("block_threshold") int blockThreshold,
            @JsonProperty("device_action") String deviceAction,
            @JsonProperty("is_new_device") boolean isNewDevice
    ) {}

    /** Issued session — produced by SessionIssueProcessor. */
    @FlowData("oidc.issued_session")
    public record IssuedSession(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("redirect_to") String redirectTo,
            @JsonProperty("mfa_pending") boolean mfaPending
    ) {}
}
