package com.volta.authproxy.flow.invite;

import com.volta.authproxy.SqlStore;
import com.volta.authproxy.flow.*;

import java.util.Set;

import static com.volta.authproxy.flow.invite.InviteFlowData.*;

/**
 * → ACCEPTED: Calls store.acceptInvitation() to create membership.
 */
public final class InviteAcceptProcessor implements StateProcessor {
    private final SqlStore store;

    public InviteAcceptProcessor(SqlStore store) {
        this.store = store;
    }

    @Override public String name() { return "InviteAcceptProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(InviteContext.class); }
    @Override public Set<Class<?>> produces() { return Set.of(InviteAccepted.class); }

    @Override
    public void process(FlowContext ctx) {
        InviteContext invite = ctx.get(InviteContext.class);

        store.acceptInvitation(
                invite.invitationId(),
                invite.tenantId(),
                invite.userId(),
                invite.inviteRole()
        );

        ctx.put(InviteAccepted.class, new InviteAccepted(
                invite.userId(), invite.tenantId(), invite.inviteRole()
        ));
    }
}
