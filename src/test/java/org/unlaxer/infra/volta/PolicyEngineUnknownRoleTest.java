package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 境界値・異常系の追加カバレッジ for {@link PolicyEngine} の未知ロール処理.
 *
 * <p>既存 {@link PolicyEngineTest} は既知の OWNER/ADMIN/MEMBER/VIEWER のみを検証し、
 * 未知ロール (typo・カスタムロール・空文字・null) の挙動を固定していない。
 * 認可ロジックの穴になる回帰しやすい分岐:
 * <ul>
 *   <li>{@code rank("UNKNOWN")} = {@code Integer.MAX_VALUE}</li>
 *   <li>{@code can("UNKNOWN", ...)} = {@code false} (NPE なし)</li>
 *   <li>{@code isAtLeast} の未知ロール境界</li>
 *   <li>{@code permissions("UNKNOWN")} = empty Set (NPE なし)</li>
 *   <li>{@code enforce} が未知ロールのみの principal を拒否</li>
 *   <li>{@code enforceMinRole} が未知ロールのみの principal を拒否</li>
 * </ul>
 */
class PolicyEngineUnknownRoleTest {

    private final PolicyEngine policy = PolicyEngine.defaultPolicy();

    // ── rank ────────────────────────────────────────────────────────────

    @Test
    void rank_unknownRoleIsMaxValue() {
        // 未知ロールは最下位。これが MAX_VALUE でないと isAtLeast の比較が壊れる。
        assertEquals(Integer.MAX_VALUE, policy.rank("SUPERADMIN"));
        assertEquals(Integer.MAX_VALUE, policy.rank(""));
        assertEquals(Integer.MAX_VALUE, policy.rank("nonexistent"));
    }

    @Test
    void rank_nullCurrentlyThrowsNpe() {
        // 既知のバグ: List.indexOf(null) が NPE を投げる (#76).
        // null ロールは未知と同じ MAX_VALUE を返すべきだが、現状は NPE。
        // 実装を変えない制約の下で現挙動を固定し、回帰を検知する。
        assertThrows(NullPointerException.class, () -> policy.rank(null));
    }

    // ── can ─────────────────────────────────────────────────────────────

    @Test
    void can_unknownRoleReturnsFalse() {
        // 未知ロールはいかなる permission も持たない。NPE なし。
        assertFalse(policy.can("SUPERADMIN", "delete_tenant"));
        assertFalse(policy.can("unknown", "use_apps"));
        assertFalse(policy.can("", "read_only"));
    }

    @Test
    void can_nullRoleCurrentlyThrowsNpe() {
        // 既知のバグ: Map.copyOf / HashMap が null key を拒否し NPE (#76).
        // null ロールは false を返すべきだが、現状は NPE。現挙動を固定する。
        assertThrows(NullPointerException.class, () -> policy.can(null, "use_apps"));
    }

    @Test
    void can_knownRoleWithUnknownPermissionReturnsFalse() {
        // 既知ロールでも未定義 permission は false。
        assertFalse(policy.can("ADMIN", "nonexistent_permission"));
        assertFalse(policy.can("OWNER", ""));
    }

    // ── isAtLeast ───────────────────────────────────────────────────────

    @Test
    void isAtLeast_unknownRoleIsBelowEverything() {
        // 未知ロールは最下位なので、VIEWER にも及ばない。
        assertFalse(policy.isAtLeast("UNKNOWN", "VIEWER"));
        assertFalse(policy.isAtLeast("UNKNOWN", "OWNER"));
        assertFalse(policy.isAtLeast("SUPERADMIN", "MEMBER"));
    }

    @Test
    void isAtLeast_knownRoleIsAboveUnknown() {
        // 既知ロールは未知ロールより上位(MAX_VALUE より rank が小さい)。
        assertTrue(policy.isAtLeast("VIEWER", "UNKNOWN"));
        assertTrue(policy.isAtLeast("OWNER", "nonexistent"));
    }

    @Test
    void isAtLeast_bothUnknownIsEqual() {
        // 両方 MAX_VALUE → rank 同士で等しい → isAtLeast は true。
        // 「未知は未知以上」は直感に反するが、現実装の挙動を固定しておく。
        assertTrue(policy.isAtLeast("UNKNOWN_A", "UNKNOWN_B"));
    }

    // ── permissions ─────────────────────────────────────────────────────

    @Test
    void permissions_unknownRoleReturnsEmptySet() {
        // NPE にならず空集合が返ること。呼び出し元が perms.contains() しても安全。
        assertEquals(Set.of(), policy.permissions("UNKNOWN"));
        assertEquals(Set.of(), policy.permissions(""));
        assertTrue(policy.permissions("UNKNOWN").isEmpty());
    }

    @Test
    void permissions_nullRoleCurrentlyThrowsNpe() {
        // 既知のバグ: getOrDefault(null, ...) が HashMap で NPE (#76).
        // null ロールは空集合を返すべきだが、現状は NPE。現挙動を固定する。
        assertThrows(NullPointerException.class, () -> policy.permissions(null));
    }

    @Test
    void permissions_knownRoleReturnsNonEmpty() {
        // 対照: 既知ロールは継承込みの権限集合を返す。
        assertFalse(policy.permissions("OWNER").isEmpty());
        assertFalse(policy.permissions("VIEWER").isEmpty());
    }

    // ── canAny ──────────────────────────────────────────────────────────

    @Test
    void canAny_withUnknownRoleStillChecksOthers() {
        // 未知ロールが混ざっていても、他のロールが権限を持てば true。
        assertTrue(policy.canAny(List.of("UNKNOWN", "ADMIN"), "invite_members"));
    }

    @Test
    void canAny_allUnknownReturnsFalse() {
        assertFalse(policy.canAny(List.of("UNKNOWN", "SUPERADMIN"), "use_apps"));
    }

    @Test
    void canAny_emptyListReturnsFalse() {
        // roles が空 → stream が空 → anyMatch なし → false。
        assertFalse(policy.canAny(List.of(), "use_apps"));
    }

    // ── enforce / enforceMinRole with unknown roles ─────────────────────

    @Test
    void enforce_rejectsPrincipalWithOnlyUnknownRoles() {
        // 未知ロールのみの principal はいかなる permission も持たない → 403。
        var principal = principal("SUPERADMIN");
        ApiException ex = assertThrows(ApiException.class,
                () -> policy.enforce(principal, "use_apps"));
        assertEquals(403, ex.status());
        assertEquals("ROLE_INSUFFICIENT", ex.code());
    }

    @Test
    void enforce_allowsWhenKnownRoleMixedWithUnknown() {
        // 未知ロールが混ざっていても既知ロールが権限を持てば通す。
        var principal = new AuthPrincipal(UUID.randomUUID(), "u", "u",
                UUID.randomUUID(), "t", "t", List.of("UNKNOWN", "MEMBER"), false);
        assertDoesNotThrow(() -> policy.enforce(principal, "use_apps"));
    }

    @Test
    void enforceMinRole_rejectsPrincipalBelowMin() {
        // 未知ロールのみ → rank=MAX → どの minRole にも達しない → 403。
        var principal = principal("UNKNOWN_ROLE");
        ApiException ex = assertThrows(ApiException.class,
                () -> policy.enforceMinRole(principal, "VIEWER"));
        assertEquals(403, ex.status());
        assertEquals("ROLE_INSUFFICIENT", ex.code());
    }

    @Test
    void enforceMinRole_allowsUnknownWhenKnownRoleAlsoPresent() {
        // 未知ロールが 1 つでも、既知ロールが minRole 以上なら通す。
        var principal = new AuthPrincipal(UUID.randomUUID(), "u", "u",
                UUID.randomUUID(), "t", "t", List.of("UNKNOWN", "ADMIN"), false);
        assertDoesNotThrow(() -> policy.enforceMinRole(principal, "ADMIN"));
    }

    // ── helper ──────────────────────────────────────────────────────────

    private static AuthPrincipal principal(String role) {
        return new AuthPrincipal(UUID.randomUUID(), "test@example.com", "Test",
                UUID.randomUUID(), "TestTenant", "test", List.of(role), false);
    }
}
