package com.volta.authproxy;

import java.util.*;

/**
 * Role-based policy engine. Reads role hierarchy and permissions from policy.yaml (via VoltaConfig).
 * Replaces hardcoded role checks throughout Main.java.
 *
 * <p>Role hierarchy: OWNER > ADMIN > MEMBER > VIEWER.
 * Each role inherits all permissions from roles below it.
 *
 * <pre>
 * PolicyEngine policy = PolicyEngine.fromDsl(policyConfig);
 * policy.can("ADMIN", "invite_members");     // true
 * policy.can("MEMBER", "invite_members");    // false
 * policy.enforce(principal, "invite_members"); // throws if denied
 * policy.enforceMinRole(principal, "ADMIN");  // throws if below ADMIN
 * </pre>
 */
public final class PolicyEngine {

    private final List<String> hierarchy;  // highest first: [OWNER, ADMIN, MEMBER, VIEWER]
    private final Map<String, Set<String>> effectivePermissions; // role -> all permissions (including inherited)

    private PolicyEngine(List<String> hierarchy, Map<String, Set<String>> effectivePermissions) {
        this.hierarchy = List.copyOf(hierarchy);
        this.effectivePermissions = Map.copyOf(effectivePermissions);
    }

    // ─── Query API ──────────────────────────────────────────

    /** Check if a role has a specific permission (including inherited). */
    public boolean can(String role, String permission) {
        Set<String> perms = effectivePermissions.get(role);
        return perms != null && perms.contains(permission);
    }

    /** Check if any of the roles has the permission. */
    public boolean canAny(List<String> roles, String permission) {
        return roles.stream().anyMatch(r -> can(r, permission));
    }

    /** Get the rank of a role (0 = highest). Returns Integer.MAX_VALUE if unknown. */
    public int rank(String role) {
        int idx = hierarchy.indexOf(role);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }

    /** Check if roleA is at least as high as roleB in the hierarchy. */
    public boolean isAtLeast(String roleA, String roleB) {
        return rank(roleA) <= rank(roleB);
    }

    /** Get all effective permissions for a role. */
    public Set<String> permissions(String role) {
        return effectivePermissions.getOrDefault(role, Set.of());
    }

    /** Get the role hierarchy (highest first). */
    public List<String> hierarchy() { return hierarchy; }

    // ─── Enforcement API ────────────────────────────────────

    /** Enforce that the principal has the given permission. Throws 403 if denied. */
    public void enforce(AuthPrincipal principal, String permission) {
        if (principal.serviceToken()) return;
        if (!canAny(principal.roles(), permission)) {
            throw new ApiException(403, "ROLE_INSUFFICIENT",
                    "Permission '" + permission + "' denied for roles " + principal.roles());
        }
    }

    /** Enforce that the principal has at least the given role. */
    public void enforceMinRole(AuthPrincipal principal, String minRole) {
        if (principal.serviceToken()) return;
        boolean hasMinRole = principal.roles().stream().anyMatch(r -> isAtLeast(r, minRole));
        if (!hasMinRole) {
            throw new ApiException(403, "ROLE_INSUFFICIENT",
                    "Minimum role '" + minRole + "' required, but user has " + principal.roles());
        }
    }

    /** Enforce tenant match (unchanged from existing logic). */
    public void enforceTenantMatch(AuthPrincipal principal, java.util.UUID tenantId) {
        if (principal.serviceToken()) return;
        if (!principal.tenantId().equals(tenantId)) {
            throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied");
        }
    }

    // ─── Builder ────────────────────────────────────────────

    /**
     * Build from policy.yaml DSL structure.
     */
    public static PolicyEngine fromDsl(PolicyConfig config) {
        List<String> hierarchy = config.hierarchy();

        // Build raw permissions per role
        Map<String, Set<String>> rawPerms = new LinkedHashMap<>();
        for (var entry : config.permissions().entrySet()) {
            rawPerms.put(entry.getKey(), new LinkedHashSet<>(entry.getValue().can()));
        }

        // Resolve inheritance: walk hierarchy bottom-up
        Map<String, Set<String>> effective = new LinkedHashMap<>();
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            String role = hierarchy.get(i);
            Set<String> perms = new LinkedHashSet<>(rawPerms.getOrDefault(role, Set.of()));

            // Inherit from the role below (if inherits is declared)
            var roleConfig = config.permissions().get(role);
            if (roleConfig != null && roleConfig.inherits() != null) {
                Set<String> inherited = effective.get(roleConfig.inherits());
                if (inherited != null) perms.addAll(inherited);
            }

            effective.put(role, Collections.unmodifiableSet(perms));
        }

        return new PolicyEngine(hierarchy, effective);
    }

    /**
     * Build with default volta policy (hardcoded fallback if no policy.yaml).
     */
    public static PolicyEngine defaultPolicy() {
        return fromDsl(PolicyConfig.defaults());
    }

    // ─── Policy config record ───────────────────────────────

    public record PolicyConfig(
            List<String> hierarchy,
            Map<String, RoleConfig> permissions
    ) {
        public record RoleConfig(String inherits, List<String> can) {
            public RoleConfig(List<String> can) { this(null, can); }
        }

        /** Default volta policy matching dsl/policy.yaml */
        public static PolicyConfig defaults() {
            return new PolicyConfig(
                    List.of("OWNER", "ADMIN", "MEMBER", "VIEWER"),
                    Map.of(
                            "OWNER", new RoleConfig("ADMIN", List.of(
                                    "delete_tenant", "transfer_ownership", "manage_signing_keys", "change_tenant_slug")),
                            "ADMIN", new RoleConfig("MEMBER", List.of(
                                    "invite_members", "remove_members", "change_member_role",
                                    "view_invitations", "create_invitations", "cancel_invitations",
                                    "change_tenant_name", "view_audit_logs")),
                            "MEMBER", new RoleConfig("VIEWER", List.of(
                                    "use_apps", "view_own_profile", "update_own_profile",
                                    "manage_own_sessions", "view_tenant_members", "switch_tenant", "accept_invitation")),
                            "VIEWER", new RoleConfig(List.of("read_only"))
                    )
            );
        }
    }
}
