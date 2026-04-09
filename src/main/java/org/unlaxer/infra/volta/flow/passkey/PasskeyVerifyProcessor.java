package org.unlaxer.infra.volta.flow.passkey;

import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.data.*;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;

import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.*;

/**
 * ASSERTION_RECEIVED → USER_RESOLVED: WebAuthn4j signature verification + user resolution.
 * Thin wrapper around existing Main.java passkey verification logic.
 */
public final class PasskeyVerifyProcessor implements StateProcessor {
    private final AppConfig config;
    private final SqlStore store;

    public PasskeyVerifyProcessor(AppConfig config, SqlStore store) {
        this.config = config;
        this.store = store;
    }

    @Override public String name() { return "PasskeyVerifyProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(PasskeyAssertion.class, PasskeyChallenge.class); }
    @Override public Set<Class<?>> produces() { return Set.of(PasskeyVerifiedUser.class); }

    @Override
    public void process(FlowContext ctx) {
        PasskeyAssertion assertion = ctx.get(PasskeyAssertion.class);
        PasskeyChallenge challenge = ctx.get(PasskeyChallenge.class);

        // Decode assertion data
        byte[] credentialId = Base64.getUrlDecoder().decode(assertion.credentialIdB64());
        byte[] clientDataJson = Base64.getUrlDecoder().decode(assertion.clientDataJsonB64());
        byte[] authenticatorData = Base64.getUrlDecoder().decode(assertion.authenticatorDataB64());
        byte[] signature = Base64.getUrlDecoder().decode(assertion.signatureB64());

        // Look up passkey by credential ID
        var passkey = store.findPasskeyByCredentialId(credentialId)
                .orElseThrow(() -> new FlowException("PASSKEY_NOT_FOUND", "Passkey not found"));

        // WebAuthn4j verification
        AuthenticationRequest authRequest = new AuthenticationRequest(
                credentialId, authenticatorData, clientDataJson, signature);

        AAGUID aaguid = passkey.aaguid() != null
                ? new AAGUID(passkey.aaguid()) : AAGUID.ZERO;
        COSEKey publicKey = new com.webauthn4j.converter.util.ObjectConverter()
                .getCborConverter().readValue(passkey.publicKey(), COSEKey.class);

        AttestedCredentialData attestedCredData = new AttestedCredentialData(
                aaguid, credentialId, publicKey);
        var authenticator = new AuthenticatorImpl(attestedCredData, null, passkey.signCount());

        byte[] challengeBytes = Base64.getUrlDecoder().decode(challenge.challengeB64());
        ServerProperty serverProperty = new ServerProperty(
                new Origin(config.webauthnRpOrigin()),
                config.webauthnRpId(),
                new DefaultChallenge(challengeBytes),
                null
        );

        AuthenticationParameters authParams = new AuthenticationParameters(
                serverProperty, authenticator, null, false);

        var manager = WebAuthnManager.createNonStrictWebAuthnManager();
        var result = manager.validate(authRequest, authParams);

        // Update sign count
        long newSignCount = result.getAuthenticatorData().getSignCount();
        store.updatePasskeyCounter(passkey.id(), newSignCount);

        // Resolve user + tenant
        UserRecord user = store.findUserById(passkey.userId())
                .orElseThrow(() -> new FlowException("USER_NOT_FOUND", "User not found"));
        TenantRecord tenant = resolveTenant(user);
        MembershipRecord membership = store.findMembership(user.id(), tenant.id())
                .orElseThrow(() -> new FlowException("TENANT_ACCESS_DENIED", "No membership"));

        ctx.put(PasskeyVerifiedUser.class, new PasskeyVerifiedUser(
                user.id(), user.email(), user.displayName(),
                tenant.id(), tenant.name(), tenant.slug(),
                List.of(membership.role())
        ));
    }

    private TenantRecord resolveTenant(UserRecord user) {
        var tenants = store.findTenantsByUser(user.id());
        if (tenants.isEmpty()) {
            return store.createPersonalTenant(user);
        }
        return tenants.getFirst();
    }
}
