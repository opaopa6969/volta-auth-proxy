package com.volta.authproxy.flow;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto-generated invalid transition tests.
 * For each flow, computes the complement of valid transitions and asserts they don't exist.
 */
class InvalidTransitionTest {

    // ─── OIDC ───────────────────────────────────────────────

    static Stream<Object[]> oidcInvalidTransitions() {
        return invalidTransitions(OidcFlowDefinitions.create());
    }

    @ParameterizedTest(name = "OIDC: {0} -> {1} should be invalid")
    @MethodSource("oidcInvalidTransitions")
    void oidcInvalidTransition(OidcFlowState from, OidcFlowState to) {
        assertNoDirectTransition(OidcFlowDefinitions.create(), from, to);
    }

    // ─── Passkey ────────────────────────────────────────────

    static Stream<Object[]> passkeyInvalidTransitions() {
        return invalidTransitions(PasskeyFlowDefinitions.create());
    }

    @ParameterizedTest(name = "Passkey: {0} -> {1} should be invalid")
    @MethodSource("passkeyInvalidTransitions")
    void passkeyInvalidTransition(PasskeyFlowState from, PasskeyFlowState to) {
        assertNoDirectTransition(PasskeyFlowDefinitions.create(), from, to);
    }

    // ─── MFA ────────────────────────────────────────────────

    static Stream<Object[]> mfaInvalidTransitions() {
        return invalidTransitions(MfaFlowDefinitions.create());
    }

    @ParameterizedTest(name = "MFA: {0} -> {1} should be invalid")
    @MethodSource("mfaInvalidTransitions")
    void mfaInvalidTransition(MfaFlowState from, MfaFlowState to) {
        assertNoDirectTransition(MfaFlowDefinitions.create(), from, to);
    }

    // ─── Invite ─────────────────────────────────────────────

    static Stream<Object[]> inviteInvalidTransitions() {
        return invalidTransitions(InviteFlowDefinitions.create());
    }

    @ParameterizedTest(name = "Invite: {0} -> {1} should be invalid")
    @MethodSource("inviteInvalidTransitions")
    void inviteInvalidTransition(InviteFlowState from, InviteFlowState to) {
        assertNoDirectTransition(InviteFlowDefinitions.create(), from, to);
    }

    // ─── Helpers ────────────────────────────────────────────

    private static <S extends Enum<S> & FlowState> Stream<Object[]> invalidTransitions(
            FlowDefinition<S> def) {
        // Collect all valid (from, to) pairs
        Set<String> valid = new HashSet<>();
        for (Transition<S> t : def.transitions()) {
            valid.add(t.from().name() + "->" + t.to().name());
        }
        // Also include error transitions
        for (var entry : def.errorTransitions().entrySet()) {
            valid.add(entry.getKey().name() + "->" + entry.getValue().name());
        }

        // Generate complement
        List<Object[]> invalid = new ArrayList<>();
        for (S from : def.allStates()) {
            for (S to : def.allStates()) {
                String key = from.name() + "->" + to.name();
                if (!valid.contains(key)) {
                    invalid.add(new Object[]{from, to});
                }
            }
        }
        return invalid.stream();
    }

    private static <S extends Enum<S> & FlowState> void assertNoDirectTransition(
            FlowDefinition<S> def, S from, S to) {
        boolean hasTransition = def.transitions().stream()
                .anyMatch(t -> t.from() == from && t.to() == to);
        boolean hasErrorTransition = to.equals(def.errorTransitions().get(from));
        assertFalse(hasTransition || hasErrorTransition,
                "Expected no transition from " + from.name() + " to " + to.name() + " but one exists");
    }
}
