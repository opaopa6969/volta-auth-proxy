package org.unlaxer.infra.volta.flow.mfa;

import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import com.warrenstrange.googleauth.GoogleAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.mfa.MfaFlowData.*;

/**
 * CHALLENGE_SHOWN → VERIFIED: Verifies TOTP code or recovery code, marks session MFA verified.
 * Thin wrapper around existing TOTP verification logic from Main.java.
 */
public final class MfaVerifyProcessor implements StateProcessor {
    private final SqlStore store;
    private final AuthService authService;
    private final KeyCipher secretCipher;
    private final AppRegistry appRegistry;
    private final TenancyPolicy tenancy;

    public MfaVerifyProcessor(SqlStore store, AuthService authService, KeyCipher secretCipher, AppRegistry appRegistry) {
        this(store, authService, secretCipher, appRegistry, new TenancyPolicy((VoltaConfig) null));
    }

    public MfaVerifyProcessor(SqlStore store, AuthService authService, KeyCipher secretCipher,
                              AppRegistry appRegistry, TenancyPolicy tenancy) {
        this.store = store;
        this.authService = authService;
        this.secretCipher = secretCipher;
        this.appRegistry = appRegistry;
        this.tenancy = tenancy == null ? new TenancyPolicy((VoltaConfig) null) : tenancy;
    }

    @Override public String name() { return "MfaVerifyProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(MfaCodeSubmission.class, MfaSessionContext.class); }
    @Override public Set<Class<?>> produces() { return Set.of(MfaVerified.class); }

    @Override
    public void process(FlowContext ctx) {
        MfaCodeSubmission submission = ctx.get(MfaCodeSubmission.class);
        MfaSessionContext session = ctx.get(MfaSessionContext.class);

        boolean verified;

        if (submission.recoveryCode() != null && !submission.recoveryCode().isBlank()) {
            // Recovery code verification
            String normalized = submission.recoveryCode().replace("-", "").toUpperCase();
            String hash = sha256Hex(normalized);
            verified = store.consumeRecoveryCode(session.userId(), hash);
        } else {
            // TOTP verification
            var mfa = store.findUserMfa(session.userId(), "totp")
                    .orElseThrow(() -> new FlowException("MFA_NOT_CONFIGURED", "TOTP not configured"));
            String decryptedSecret = secretCipher.decrypt(mfa.secret());
            GoogleAuthenticator ga = new GoogleAuthenticator();
            verified = ga.authorize(decryptedSecret, submission.code());
        }

        if (!verified) {
            throw new FlowException("MFA_INVALID_CODE", "Invalid MFA code");
        }

        // Mark session as MFA verified
        authService.markMfaVerified(session.sessionId());

        // Resolve redirect
        String redirectTo = resolveRedirect(session);

        ctx.put(MfaVerified.class, new MfaVerified(session.sessionId(), redirectTo));
    }

    private String resolveRedirect(MfaSessionContext session) {
        String returnTo = session.returnTo();
        if (returnTo != null && returnTo.startsWith("invite:")) {
            return "/invite/" + returnTo.substring("invite:".length());
        }
        if (returnTo != null && !returnTo.isBlank()) {
            return returnTo;
        }
        // No return_to — decide based on tenancy mode.
        var tenants = store.findTenantsByUser(session.userId());
        if (tenancy.shouldSelectTenant(tenants.size())) return "/select-tenant";
        return appRegistry.defaultAppUrl().orElse(tenancy.isSingle() ? "/" : "/select-tenant");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}
