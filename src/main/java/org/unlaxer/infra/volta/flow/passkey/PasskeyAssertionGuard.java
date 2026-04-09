package org.unlaxer.infra.volta.flow.passkey;

import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.util.Map;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.*;

/**
 * CHALLENGE_ISSUED → ASSERTION_RECEIVED: Validates assertion data from browser.
 * The actual WebAuthn cryptographic verification happens in PasskeyVerifyProcessor.
 * This guard just checks that all required fields are present.
 */
public final class PasskeyAssertionGuard implements TransitionGuard {

    @Override public String name() { return "PasskeyAssertionGuard"; }
    @Override public Set<Class<?>> requires() { return Set.of(PasskeyChallenge.class); }
    @Override public Set<Class<?>> produces() { return Set.of(PasskeyAssertion.class); }
    @Override public int maxRetries() { return 3; }

    @Override
    public GuardOutput validate(FlowContext ctx) {
        var assertionOpt = ctx.find(PasskeyAssertion.class);
        if (assertionOpt.isEmpty()) {
            return new GuardOutput.Rejected("Missing assertion data");
        }
        PasskeyAssertion assertion = assertionOpt.get();
        if (assertion.credentialIdB64() == null || assertion.credentialIdB64().isBlank()) {
            return new GuardOutput.Rejected("Missing credential ID");
        }
        if (assertion.clientDataJsonB64() == null || assertion.clientDataJsonB64().isBlank()) {
            return new GuardOutput.Rejected("Missing client data JSON");
        }
        if (assertion.authenticatorDataB64() == null || assertion.authenticatorDataB64().isBlank()) {
            return new GuardOutput.Rejected("Missing authenticator data");
        }
        if (assertion.signatureB64() == null || assertion.signatureB64().isBlank()) {
            return new GuardOutput.Rejected("Missing signature");
        }
        return new GuardOutput.Accepted(Map.of(PasskeyAssertion.class, assertion));
    }
}
