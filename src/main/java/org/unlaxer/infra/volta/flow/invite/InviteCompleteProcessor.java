package org.unlaxer.infra.volta.flow.invite;

import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.unlaxer.infra.volta.flow.invite.InviteFlowData.*;

/**
 * ACCEPTED → COMPLETE: Issues new session with the invited tenant.
 */
public final class InviteCompleteProcessor implements StateProcessor {
    private final AuthService authService;
    private final SqlStore store;
    private final AppConfig config;

    public InviteCompleteProcessor(AuthService authService, SqlStore store, AppConfig config) {
        this.authService = authService;
        this.store = store;
        this.config = config;
    }

    @Override public String name() { return "InviteCompleteProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(InviteAccepted.class, InviteContext.class); }
    @Override public Set<Class<?>> produces() { return Set.of(InviteCompleted.class); }

    @Override
    public void process(FlowContext ctx) {
        InviteAccepted accepted = ctx.get(InviteAccepted.class);
        InviteContext invite = ctx.get(InviteContext.class);

        // Look up user and tenant for principal
        UserRecord user = store.findUserById(accepted.userId())
                .orElseThrow(() -> new FlowException("USER_NOT_FOUND", "User not found"));
        TenantRecord tenant = store.findTenantById(accepted.tenantId())
                .orElseThrow(() -> new FlowException("TENANT_NOT_FOUND", "Tenant not found"));

        AuthPrincipal principal = new AuthPrincipal(
                user.id(), user.email(), user.displayName(),
                tenant.id(), tenant.name(), tenant.slug(),
                List.of(accepted.role()), false
        );

        // Issue new session with invited tenant
        UUID sessionId = authService.issueSession(principal, null, "flow", "flow");

        ctx.put(InviteCompleted.class, new InviteCompleted(
                sessionId, "/console/"
        ));
    }
}
