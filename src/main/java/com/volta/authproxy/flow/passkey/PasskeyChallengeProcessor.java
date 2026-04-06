package com.volta.authproxy.flow.passkey;

import com.volta.authproxy.AppConfig;
import com.volta.authproxy.flow.*;

import java.util.Base64;
import java.util.Set;

import static com.volta.authproxy.flow.passkey.PasskeyFlowData.*;

/**
 * INIT → CHALLENGE_ISSUED: Generates WebAuthn challenge and stores in FlowContext.
 * Challenge is DB-backed (via FlowContext JSONB) instead of in-memory ConcurrentHashMap.
 */
public final class PasskeyChallengeProcessor implements StateProcessor {
    private final AppConfig config;

    public PasskeyChallengeProcessor(AppConfig config) {
        this.config = config;
    }

    @Override public String name() { return "PasskeyChallengeProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(PasskeyRequest.class); }
    @Override public Set<Class<?>> produces() { return Set.of(PasskeyChallenge.class); }

    @Override
    public void process(FlowContext ctx) {
        byte[] challenge = new byte[32];
        new java.security.SecureRandom().nextBytes(challenge);
        String challengeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);

        ctx.put(PasskeyChallenge.class, new PasskeyChallenge(
                challengeB64,
                config.webauthnRpId(),
                config.webauthnRpName(),
                300_000 // 5 minutes
        ));
    }
}
