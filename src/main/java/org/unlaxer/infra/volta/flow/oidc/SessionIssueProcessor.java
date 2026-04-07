package org.unlaxer.infra.volta.flow.oidc;

import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.util.List;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.oidc.OidcFlowData.*;

/**
 * USER_RESOLVED → COMPLETE/COMPLETE_MFA_PENDING: Issues session via AuthService.
 * Does NOT set cookies — that's the router's job (HTTP concern, not business logic).
 */
public final class SessionIssueProcessor implements StateProcessor {
    private final AuthService authService;
    private final AppRegistry appRegistry;
    private final SqlStore store;
    private final AppConfig config;

    public SessionIssueProcessor(AuthService authService, AppRegistry appRegistry,
                                 SqlStore store, AppConfig config) {
        this.authService = authService;
        this.appRegistry = appRegistry;
        this.store = store;
        this.config = config;
    }

    @Override public String name() { return "SessionIssueProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class, OidcRequest.class); }
    @Override public Set<Class<?>> produces() { return Set.of(IssuedSession.class); }

    @Override
    public void process(FlowContext ctx) {
        ResolvedUser user = ctx.get(ResolvedUser.class);
        OidcRequest request = ctx.get(OidcRequest.class);

        AuthPrincipal principal = new AuthPrincipal(
                user.userId(), user.email(), user.displayName(),
                user.tenantId(), user.tenantName(), user.tenantSlug(),
                user.roles(), false
        );

        java.util.UUID sessionId = authService.issueSession(
                principal, user.sessionReturnTo(),
                request.clientIp(), request.userAgent()
        );

        boolean mfaPending = user.mfaRequired();
        String redirectTo = resolveRedirectTo(user, request);

        ctx.put(IssuedSession.class, new IssuedSession(sessionId, redirectTo, mfaPending));
    }

    private String resolveRedirectTo(ResolvedUser user, OidcRequest request) {
        // Invite flow takes priority
        if (request.inviteCode() != null) {
            return "/invite/" + request.inviteCode();
        }

        // Multiple tenants → tenant selection
        List<TenantRecord> tenants = store.findTenantsByUser(user.userId());
        if (tenants.size() > 1) {
            return "/select-tenant";
        }

        // Validated return_to
        if (request.returnTo() != null && HttpSupport.isAllowedReturnTo(
                request.returnTo(), config.allowedRedirectDomains())) {
            return request.returnTo();
        }

        return appRegistry.defaultAppUrl().orElse("/select-tenant");
    }
}
