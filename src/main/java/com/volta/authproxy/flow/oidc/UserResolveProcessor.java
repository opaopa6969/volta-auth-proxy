package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.*;
import com.volta.authproxy.flow.*;

import java.util.List;
import java.util.Set;

import static com.volta.authproxy.flow.oidc.OidcFlowData.*;

/**
 * TOKEN_EXCHANGED → USER_RESOLVED: Upserts user, resolves tenant, builds principal.
 * Thin wrapper — delegates to SqlStore methods.
 */
public final class UserResolveProcessor implements StateProcessor {
    private final SqlStore store;

    public UserResolveProcessor(SqlStore store) {
        this.store = store;
    }

    @Override public String name() { return "UserResolveProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(OidcTokens.class); }
    @Override public Set<Class<?>> produces() { return Set.of(ResolvedUser.class); }

    @Override
    public void process(FlowContext ctx) {
        OidcTokens tokens = ctx.get(OidcTokens.class);

        // Upsert user
        UserRecord user = store.upsertUser(tokens.email(), tokens.displayName(), tokens.sub());

        // Resolve tenant
        TenantRecord tenant = resolveTenant(user, tokens.inviteCode());

        // Build roles and session return_to
        List<String> roles;
        String sessionReturnTo = tokens.returnTo();
        boolean mfaRequired;

        if (tokens.inviteCode() != null) {
            InvitationRecord invitation = store.findInvitationByCode(tokens.inviteCode())
                    .orElseThrow(() -> new FlowException("INVITATION_NOT_FOUND", "招待リンクが見つかりません。"));
            roles = List.of("INVITED");
            sessionReturnTo = "invite:" + invitation.code();
        } else {
            MembershipRecord membership = store.findMembership(user.id(), tenant.id())
                    .orElseThrow(() -> new FlowException("TENANT_ACCESS_DENIED", "Tenant membership not found"));
            if (!membership.active()) {
                throw new FlowException("TENANT_ACCESS_DENIED", "Tenant membership not active");
            }
            roles = List.of(membership.role());
        }

        mfaRequired = store.hasActiveMfa(user.id());

        ctx.put(ResolvedUser.class, new ResolvedUser(
                user.id(), user.email(), user.displayName(),
                tenant.id(), tenant.name(), tenant.slug(),
                roles, sessionReturnTo, mfaRequired
        ));
    }

    private TenantRecord resolveTenant(UserRecord user, String inviteCode) {
        if (inviteCode != null) {
            InvitationRecord invitation = store.findInvitationByCode(inviteCode)
                    .orElseThrow(() -> new FlowException("INVITATION_NOT_FOUND", "招待リンクが見つかりません。"));
            return store.findTenantById(invitation.tenantId()).orElseThrow();
        }
        List<TenantRecord> tenants = store.findTenantsByUser(user.id());
        if (tenants.isEmpty()) {
            return store.createPersonalTenant(user);
        }
        return tenants.getFirst();
    }
}
