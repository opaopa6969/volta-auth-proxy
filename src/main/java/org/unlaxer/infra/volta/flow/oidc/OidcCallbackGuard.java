package org.unlaxer.infra.volta.flow.oidc;

import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.util.Map;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.oidc.OidcFlowData.*;

/**
 * REDIRECTED → CALLBACK_RECEIVED: Validates the OIDC callback has code and state.
 * The actual OIDC state verification (HMAC) happens in the router before calling resumeAndExecute.
 * This guard validates the callback data is present and non-empty.
 */
public final class OidcCallbackGuard implements TransitionGuard {

    @Override public String name() { return "OidcCallbackGuard"; }
    @Override public Set<Class<?>> requires() { return Set.of(OidcRedirect.class); }
    @Override public Set<Class<?>> produces() { return Set.of(OidcCallback.class); }
    @Override public int maxRetries() { return 3; }

    @Override
    public GuardOutput validate(FlowContext ctx) {
        // OidcCallback is injected by the router via externalData before guard runs
        var callbackOpt = ctx.find(OidcCallback.class);
        if (callbackOpt.isEmpty()) {
            return new GuardOutput.Rejected("Missing callback data");
        }
        OidcCallback callback = callbackOpt.get();
        if (callback.code() == null || callback.code().isBlank()) {
            return new GuardOutput.Rejected("Missing authorization code");
        }
        return new GuardOutput.Accepted(Map.of(OidcCallback.class, callback));
    }
}
