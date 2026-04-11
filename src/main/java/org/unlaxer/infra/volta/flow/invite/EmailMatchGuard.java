package org.unlaxer.infra.volta.flow.invite;

import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.invite.InviteFlowData.*;

/**
 * CONSENT_SHOWN → ACCEPTED or ACCOUNT_SWITCHING: Checks if logged-in email matches invite email.
 * This is modeled as a BranchProcessor, not a Guard — the decision determines the next state.
 */
public final class EmailMatchGuard implements BranchProcessor {
    public static final String EMAIL_MATCH = "email_match";
    public static final String EMAIL_MISMATCH = "email_mismatch";

    @Override public String name() { return "EmailMatchBranch"; }
    @Override public Set<Class<?>> requires() { return Set.of(InviteContext.class); }

    @Override
    public String decide(FlowContext ctx) {
        InviteContext invite = ctx.get(InviteContext.class);

        // If no specific email required, always match
        if (invite.inviteEmail() == null || invite.inviteEmail().isBlank()) {
            return EMAIL_MATCH;
        }

        // Compare emails case-insensitively with Unicode NFC normalization
        if (invite.userEmail() != null &&
                normalizeEmail(invite.inviteEmail())
                        .equals(normalizeEmail(invite.userEmail()))) {
            return EMAIL_MATCH;
        }

        return EMAIL_MISMATCH;
    }

    private static String normalizeEmail(String email) {
        return Normalizer.normalize(email, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
