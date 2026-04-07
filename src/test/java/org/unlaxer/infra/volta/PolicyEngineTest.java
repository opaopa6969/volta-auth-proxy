package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {
    private final PolicyEngine policy = PolicyEngine.defaultPolicy();

    // ─── Hierarchy ──────────────────────────────────────────

    @Test
    void hierarchyOrder() {
        assertEquals(0, policy.rank("OWNER"));
        assertEquals(1, policy.rank("ADMIN"));
        assertEquals(2, policy.rank("MEMBER"));
        assertEquals(3, policy.rank("VIEWER"));
    }

    @Test
    void isAtLeast() {
        assertTrue(policy.isAtLeast("OWNER", "ADMIN"));
        assertTrue(policy.isAtLeast("ADMIN", "ADMIN"));
        assertFalse(policy.isAtLeast("MEMBER", "ADMIN"));
        assertTrue(policy.isAtLeast("MEMBER", "VIEWER"));
    }

    // ─── Permissions ────────────────────────────────────────

    @Test
    void ownerCanDoEverything() {
        assertTrue(policy.can("OWNER", "delete_tenant"));
        assertTrue(policy.can("OWNER", "invite_members")); // inherited from ADMIN
        assertTrue(policy.can("OWNER", "use_apps"));        // inherited from MEMBER
        assertTrue(policy.can("OWNER", "read_only"));       // inherited from VIEWER
    }

    @Test
    void adminCannotDeleteTenant() {
        assertFalse(policy.can("ADMIN", "delete_tenant"));
        assertTrue(policy.can("ADMIN", "invite_members"));
        assertTrue(policy.can("ADMIN", "use_apps"));  // inherited from MEMBER
    }

    @Test
    void memberCannotInvite() {
        assertFalse(policy.can("MEMBER", "invite_members"));
        assertTrue(policy.can("MEMBER", "use_apps"));
        assertTrue(policy.can("MEMBER", "accept_invitation"));
    }

    @Test
    void viewerCanOnlyRead() {
        assertTrue(policy.can("VIEWER", "read_only"));
        assertFalse(policy.can("VIEWER", "use_apps"));
        assertFalse(policy.can("VIEWER", "invite_members"));
    }

    @Test
    void canAnyChecksAllRoles() {
        assertTrue(policy.canAny(List.of("MEMBER", "ADMIN"), "invite_members"));
        assertFalse(policy.canAny(List.of("MEMBER", "VIEWER"), "invite_members"));
    }

    // ─── Enforcement ────────────────────────────────────────

    @Test
    void enforceAllowsPermitted() {
        var admin = principal("ADMIN");
        assertDoesNotThrow(() -> policy.enforce(admin, "invite_members"));
    }

    @Test
    void enforceDeniesUnpermitted() {
        var member = principal("MEMBER");
        var ex = assertThrows(ApiException.class, () -> policy.enforce(member, "invite_members"));
        assertEquals(403, ex.status());
        assertEquals("ROLE_INSUFFICIENT", ex.code());
    }

    @Test
    void enforceSkipsServiceToken() {
        var service = new AuthPrincipal(UUID.randomUUID(), "svc", "svc",
                UUID.randomUUID(), "t", "t", List.of("VIEWER"), true);
        assertDoesNotThrow(() -> policy.enforce(service, "delete_tenant"));
    }

    @Test
    void enforceMinRole() {
        var admin = principal("ADMIN");
        assertDoesNotThrow(() -> policy.enforceMinRole(admin, "ADMIN"));
        assertDoesNotThrow(() -> policy.enforceMinRole(admin, "MEMBER"));
        assertThrows(ApiException.class, () -> policy.enforceMinRole(admin, "OWNER"));
    }

    @Test
    void enforceTenantMatch() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        var p = new AuthPrincipal(UUID.randomUUID(), "u", "u",
                tenantA, "t", "t", List.of("MEMBER"), false);
        assertDoesNotThrow(() -> policy.enforceTenantMatch(p, tenantA));
        assertThrows(ApiException.class, () -> policy.enforceTenantMatch(p, tenantB));
    }

    // ─── Helpers ────────────────────────────────────────────

    private static AuthPrincipal principal(String role) {
        return new AuthPrincipal(UUID.randomUUID(), "test@example.com", "Test",
                UUID.randomUUID(), "TestTenant", "test", List.of(role), false);
    }
}
