package org.unlaxer.infra.volta.flow;
import com.tramli.FlowContext;
import com.tramli.BranchProcessor;
import com.tramli.TransitionGuard;
import com.tramli.StateProcessor;

import java.util.Map;
import java.util.Set;

/**
 * Stub implementations for testing flow definitions without real business logic.
 */
final class StubProcessors {

    private StubProcessors() {}

    // ─── Stub FlowData types ────────────────────────────────

    @FlowData("test.request")
    record TestRequest(String provider, String returnTo) {}

    @FlowData("test.redirect")
    record TestRedirect(String url, String state) {}

    @FlowData("test.callback")
    record TestCallback(String code, String state) {}

    @FlowData("test.token")
    record TestToken(String accessToken, String idToken) {}

    @FlowData("test.user")
    record TestUser(String email, boolean mfaRequired) {}

    @FlowData("test.session")
    record TestSession(String sessionId) {}

    // ─── Processors ─────────────────────────────────────────

    static StateProcessor named(String name) {
        return new NoOpProcessor(name);
    }

    static StateProcessor named(String name, Set<Class<?>> requires, Set<Class<?>> produces) {
        return new NoOpProcessor(name, requires, produces);
    }

    record NoOpProcessor(String name, Set<Class<?>> requires, Set<Class<?>> produces) implements StateProcessor {
        NoOpProcessor(String name) { this(name, Set.of(), Set.of()); }
        @Override public void process(FlowContext ctx) {}
    }

    // ─── Guards ─────────────────────────────────────────────

    static TransitionGuard acceptingGuard(String name) {
        return new StubGuard(name, true, Set.of(), Set.of());
    }

    static TransitionGuard rejectingGuard(String name) {
        return new StubGuard(name, false, Set.of(), Set.of());
    }

    static TransitionGuard acceptingGuard(String name, Set<Class<?>> requires, Set<Class<?>> produces) {
        return new StubGuard(name, true, requires, produces);
    }

    record StubGuard(String name, boolean accepts, Set<Class<?>> requires,
                     Set<Class<?>> produces) implements TransitionGuard {
        @Override public int maxRetries() { return 3; }
        @Override public GuardOutput validate(FlowContext ctx) {
            return accepts ? new GuardOutput.Accepted() : new GuardOutput.Rejected("stub rejected");
        }
    }

    // ─── Branch Processors ──────────────────────────────────

    static BranchProcessor fixedBranch(String name, String label) {
        return new FixedBranch(name, label, Set.of());
    }

    static BranchProcessor fixedBranch(String name, String label, Set<Class<?>> requires) {
        return new FixedBranch(name, label, requires);
    }

    record FixedBranch(String name, String label, Set<Class<?>> requires) implements BranchProcessor {
        @Override public String decide(FlowContext ctx) { return label; }
    }
}
