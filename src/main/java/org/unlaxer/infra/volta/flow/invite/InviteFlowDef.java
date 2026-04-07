package org.unlaxer.infra.volta.flow.invite;

import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.time.Duration;

import static org.unlaxer.infra.volta.flow.InviteFlowState.*;
import static org.unlaxer.infra.volta.flow.invite.InviteFlowData.*;

/**
 * Invite acceptance flow definition.
 * CONSENT_SHOWN → [email match?] → ACCEPTED or ACCOUNT_SWITCHING → ACCEPTED → COMPLETE
 */
public final class InviteFlowDef {

    private InviteFlowDef() {}

    public static FlowDefinition<InviteFlowState> create(
            AuthService authService, SqlStore store, AppConfig config) {

        var acceptProcessor = new InviteAcceptProcessor(store);
        var completeProcessor = new InviteCompleteProcessor(authService, store, config);

        return FlowDefinition.builder("invite", InviteFlowState.class)
                .ttl(Duration.ofDays(7))
                .maxGuardRetries(3)
                .initiallyAvailable(InviteContext.class)

                .from(CONSENT_SHOWN).branch(new EmailMatchGuard())
                    .to(ACCEPTED, EmailMatchGuard.EMAIL_MATCH, acceptProcessor)
                    .to(ACCOUNT_SWITCHING, EmailMatchGuard.EMAIL_MISMATCH)
                    .endBranch()

                .from(ACCOUNT_SWITCHING).external(ACCEPTED,
                        new ResumeGuard(), acceptProcessor)

                .from(ACCEPTED).auto(COMPLETE, completeProcessor)

                .onAnyError(TERMINAL_ERROR)
                .onError(ACCOUNT_SWITCHING, EXPIRED)

                .build();
    }
}
