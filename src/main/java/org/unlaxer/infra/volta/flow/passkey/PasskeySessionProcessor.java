package org.unlaxer.infra.volta.flow.passkey;

import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.util.Set;
import java.util.UUID;

import static org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.*;

/**
 * USER_RESOLVED → COMPLETE: Issues session with MFA already verified.
 * Passkey = MFA equivalent, so mfaVerifiedAt is set immediately.
 */
public final class PasskeySessionProcessor implements StateProcessor {
    private final AuthService authService;
    private final AppRegistry appRegistry;
    private final SqlStore store;
    private final AppConfig config;

    public PasskeySessionProcessor(AuthService authService, AppRegistry appRegistry,
                                   SqlStore store, AppConfig config) {
        this.authService = authService;
        this.appRegistry = appRegistry;
        this.store = store;
        this.config = config;
    }

    @Override public String name() { return "PasskeySessionProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(PasskeyVerifiedUser.class, PasskeyRequest.class); }
    @Override public Set<Class<?>> produces() { return Set.of(PasskeyIssuedSession.class); }

    @Override
    public void process(FlowContext ctx) {
        PasskeyVerifiedUser user = ctx.get(PasskeyVerifiedUser.class);
        PasskeyRequest request = ctx.get(PasskeyRequest.class);

        AuthPrincipal principal = new AuthPrincipal(
                user.userId(), user.email(), user.displayName(),
                user.tenantId(), user.tenantName(), user.tenantSlug(),
                user.roles(), false
        );

        UUID sessionId = authService.issueSession(
                principal, null, request.clientIp(), request.userAgent());

        // Passkey = MFA equivalent → mark MFA verified immediately
        authService.markMfaVerified(sessionId);

        String redirectTo = resolveRedirectTo(user, request);
        ctx.put(PasskeyIssuedSession.class, new PasskeyIssuedSession(sessionId, redirectTo));
    }

    private String resolveRedirectTo(PasskeyVerifiedUser user, PasskeyRequest request) {
        var tenants = store.findTenantsByUser(user.userId());
        if (tenants.size() > 1) return "/select-tenant";

        if (request.returnTo() != null && HttpSupport.isAllowedReturnTo(
                request.returnTo(), config.allowedRedirectDomains())) {
            return request.returnTo();
        }
        return appRegistry.defaultAppUrl().orElse("/select-tenant");
    }
}
