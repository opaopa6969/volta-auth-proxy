package org.unlaxer.infra.volta;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SqlStore {
    private final DataSource dataSource;

    public SqlStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public UserRecord upsertUser(String email, String displayName, String googleSub) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement update = conn.prepareStatement("""
                    UPDATE users
                    SET email = ?, display_name = ?, is_active = true
                    WHERE google_sub = ?
                    RETURNING id, email, display_name, google_sub
                    """)) {
                update.setString(1, email);
                update.setString(2, displayName);
                update.setString(3, googleSub);
                try (ResultSet rs = update.executeQuery()) {
                    if (rs.next()) {
                        return readUser(rs);
                    }
                }
            }
            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO users(email, display_name, google_sub)
                    VALUES (?, ?, ?)
                    RETURNING id, email, display_name, google_sub
                    """)) {
                insert.setString(1, email);
                insert.setString(2, displayName);
                insert.setString(3, googleSub);
                try (ResultSet rs = insert.executeQuery()) {
                    rs.next();
                    return readUser(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<UserRecord> findUserById(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, email, display_name, google_sub
                     FROM users
                     WHERE id = ?
                     """)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readUser(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<TenantRecord> findTenantsByUser(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT t.id, t.name, t.slug, t.mfa_required, t.mfa_grace_until
                     FROM memberships m
                     JOIN tenants t ON t.id = m.tenant_id
                     WHERE m.user_id = ? AND m.is_active = true AND t.is_active = true
                     ORDER BY t.created_at
                     """)) {
            ps.setObject(1, userId);
            List<TenantRecord> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TenantRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getString("slug"),
                            rs.getBoolean("mfa_required"),
                            rs.getTimestamp("mfa_grace_until") == null ? null : rs.getTimestamp("mfa_grace_until").toInstant()
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<UserTenantInfo> findTenantInfosByUser(UUID userId) {
        // AUTH-014 Phase 2 item 2: Discovery UI needs richer metadata per
        // tenant. We join a COUNT of active memberships and pull plan +
        // email_domain straight off the tenant row.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT t.id, t.name, t.slug, m.role, t.plan, t.email_domain,
                            (SELECT COUNT(*) FROM memberships m2
                              WHERE m2.tenant_id = t.id AND m2.is_active = true) AS member_count
                     FROM memberships m
                     JOIN tenants t ON t.id = m.tenant_id
                     WHERE m.user_id = ? AND m.is_active = true AND t.is_active = true
                     ORDER BY t.created_at
                     """)) {
            ps.setObject(1, userId);
            List<UserTenantInfo> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new UserTenantInfo(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getString("slug"),
                            rs.getString("role"),
                            rs.getString("plan"),
                            rs.getInt("member_count"),
                            rs.getString("email_domain")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<MembershipRecord> findMembership(UUID userId, UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, tenant_id, role, is_active
                     FROM memberships
                     WHERE user_id = ? AND tenant_id = ?
                     """)) {
            ps.setObject(1, userId);
            ps.setObject(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MembershipRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("role"),
                        rs.getBoolean("is_active")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public TenantRecord createPersonalTenant(UserRecord user) {
        String baseSlug = user.email().split("@")[0].toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String slug = baseSlug + "-" + user.id().toString().substring(0, 6);
        String name = (user.displayName() == null || user.displayName().isBlank())
                ? (baseSlug + " workspace")
                : user.displayName() + " workspace";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantRecord tenant;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO tenants(name, slug, created_by, auto_join)
                        VALUES (?, ?, ?, true)
                        RETURNING id, name, slug
                        """)) {
                    ps.setString(1, name);
                    ps.setString(2, slug);
                    ps.setObject(3, user.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        tenant = new TenantRecord(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getString("slug"),
                                false,
                                null
                        );
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO memberships(user_id, tenant_id, role)
                        VALUES (?, ?, 'OWNER')
                        ON CONFLICT(user_id, tenant_id) DO NOTHING
                        """)) {
                    ps.setObject(1, user.id());
                    ps.setObject(2, tenant.id());
                    ps.executeUpdate();
                }
                conn.commit();
                return tenant;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<TenantRecord> findTenantById(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, name, slug, mfa_required, mfa_grace_until FROM tenants WHERE id = ? AND is_active = true
                     """)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TenantRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getBoolean("mfa_required"),
                        rs.getTimestamp("mfa_grace_until") == null ? null : rs.getTimestamp("mfa_grace_until").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * AUTH-014 Phase 4 item 3: resolve a tenant by a verified custom domain.
     * Used for {@code tenancy.routing.mode=domain} where each tenant owns
     * a distinct hostname. Only verified domains count (prevents squatting).
     */
    public Optional<TenantRecord> findTenantByDomain(String domain) {
        if (domain == null || domain.isBlank()) return Optional.empty();
        String normalized = domain.toLowerCase(java.util.Locale.ROOT).trim();
        int colon = normalized.indexOf(':');
        if (colon >= 0) normalized = normalized.substring(0, colon);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT t.id, t.name, t.slug, t.mfa_required, t.mfa_grace_until
                     FROM tenant_domains td
                     JOIN tenants t ON t.id = td.tenant_id
                     WHERE td.domain = ? AND td.verified = true AND t.is_active = true
                     LIMIT 1
                     """)) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new TenantRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getBoolean("mfa_required"),
                        rs.getTimestamp("mfa_grace_until") == null ? null : rs.getTimestamp("mfa_grace_until").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * AUTH-014 Phase 2 item 3: resolve a tenant by URL slug. Used by slug
     * routing ({@code /o/:slug/...}) to re-scope the request from the
     * session's default tenant to the URL-specified one.
     */
    public Optional<TenantRecord> findTenantBySlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, name, slug, mfa_required, mfa_grace_until
                     FROM tenants
                     WHERE slug = ? AND is_active = true
                     """)) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new TenantRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getBoolean("mfa_required"),
                        rs.getTimestamp("mfa_grace_until") == null ? null : rs.getTimestamp("mfa_grace_until").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<TenantDetailRecord> findTenantDetailById(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, name, slug, auto_join, plan, max_members, is_active, logo_url, primary_color, theme
                     FROM tenants
                     WHERE id = ?
                     """)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TenantDetailRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getBoolean("auto_join"),
                        rs.getString("plan"),
                        rs.getInt("max_members"),
                        rs.getBoolean("is_active"),
                        rs.getString("logo_url"),
                        rs.getString("primary_color"),
                        rs.getString("theme")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<TenantDetailRecord> updateTenantSettings(UUID tenantId, String name, Boolean autoJoin, String logoUrl, String primaryColor, String theme) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE tenants
                     SET name = COALESCE(?, name),
                         auto_join = COALESCE(?, auto_join),
                         logo_url = COALESCE(?, logo_url),
                         primary_color = COALESCE(?, primary_color),
                         theme = COALESCE(?, theme)
                     WHERE id = ?
                     RETURNING id, name, slug, auto_join, plan, max_members, is_active, logo_url, primary_color, theme
                     """)) {
            ps.setString(1, name);
            if (autoJoin == null) {
                ps.setNull(2, Types.BOOLEAN);
            } else {
                ps.setBoolean(2, autoJoin);
            }
            ps.setString(3, logoUrl);
            ps.setString(4, primaryColor);
            ps.setString(5, theme);
            ps.setObject(6, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TenantDetailRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getBoolean("auto_join"),
                        rs.getString("plan"),
                        rs.getInt("max_members"),
                        rs.getBoolean("is_active"),
                        rs.getString("logo_url"),
                        rs.getString("primary_color"),
                        rs.getString("theme")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void createSession(UUID sessionId, UUID userId, UUID tenantId, String returnTo, Instant expiresAt, Instant mfaVerifiedAt, String ip, String userAgent, String csrfToken) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO sessions(id, user_id, tenant_id, return_to, expires_at, mfa_verified_at, ip_address, user_agent, csrf_token)
                     VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?)
                     """)) {
            ps.setObject(1, sessionId);
            ps.setObject(2, userId);
            ps.setObject(3, tenantId);
            ps.setString(4, returnTo);
            ps.setTimestamp(5, Timestamp.from(expiresAt));
            ps.setTimestamp(6, mfaVerifiedAt == null ? null : Timestamp.from(mfaVerifiedAt));
            ps.setString(7, ip);
            ps.setString(8, userAgent);
            ps.setString(9, csrfToken);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<SessionRecord> findSession(UUID sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, tenant_id, return_to, created_at, last_active_at, expires_at, invalidated_at, mfa_verified_at, ip_address, user_agent, csrf_token
                     FROM sessions
                     WHERE id = ?
                     """)) {
            ps.setObject(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SessionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("return_to"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_active_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant(),
                        rs.getTimestamp("mfa_verified_at") == null ? null : rs.getTimestamp("mfa_verified_at").toInstant(),
                        rs.getString("ip_address"),
                        rs.getString("user_agent"),
                        rs.getString("csrf_token")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void touchSession(UUID sessionId, Instant expiresAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sessions
                     SET last_active_at = now(), expires_at = ?
                     WHERE id = ? AND invalidated_at IS NULL
                     """)) {
            ps.setTimestamp(1, Timestamp.from(expiresAt));
            ps.setObject(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void revokeSession(UUID sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sessions SET invalidated_at = now() WHERE id = ?
                     """)) {
            ps.setObject(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void revokeAllSessions(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sessions SET invalidated_at = now()
                     WHERE user_id = ? AND invalidated_at IS NULL
                     """)) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public record AdminSessionView(UUID sessionId, UUID userId, String email, String displayName,
                                       String ipAddress, String userAgent, Instant createdAt,
                                       Instant lastActiveAt, Instant expiresAt, Instant invalidatedAt, UUID tenantId) {}

    public List<AdminSessionView> listAllActiveSessions(int offset, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT s.id, s.user_id, u.email, u.display_name, s.ip_address, s.user_agent,
                            s.created_at, s.last_active_at, s.expires_at, s.invalidated_at, s.tenant_id
                     FROM sessions s
                     JOIN users u ON u.id = s.user_id
                     WHERE s.invalidated_at IS NULL AND s.expires_at > now()
                     ORDER BY s.last_active_at DESC
                     LIMIT ? OFFSET ?
                     """)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            List<AdminSessionView> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new AdminSessionView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getString("ip_address"),
                            rs.getString("user_agent"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("last_active_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant(),
                            rs.getObject("tenant_id", UUID.class)
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countActiveSessions() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM sessions WHERE invalidated_at IS NULL AND expires_at > now()")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<SessionRecord> listUserSessions(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, tenant_id, return_to, created_at, last_active_at, expires_at, invalidated_at, mfa_verified_at, ip_address, user_agent, csrf_token
                     FROM sessions
                     WHERE user_id = ?
                     ORDER BY created_at DESC
                     LIMIT 50
                     """)) {
            ps.setObject(1, userId);
            List<SessionRecord> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SessionRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("return_to"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("last_active_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant(),
                            rs.getTimestamp("mfa_verified_at") == null ? null : rs.getTimestamp("mfa_verified_at").toInstant(),
                            rs.getString("ip_address"),
                            rs.getString("user_agent"),
                            rs.getString("csrf_token")
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveOidcFlow(OidcFlowRecord flow) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO oidc_flows(state, nonce, code_verifier, return_to, invite_code, expires_at, provider)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, flow.state());
            ps.setString(2, flow.nonce());
            ps.setString(3, flow.codeVerifier());
            ps.setString(4, flow.returnTo());
            ps.setString(5, flow.inviteCode());
            ps.setTimestamp(6, Timestamp.from(flow.expiresAt()));
            ps.setString(7, flow.provider() != null ? flow.provider() : "GOOGLE");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<OidcFlowRecord> consumeOidcFlow(String state) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Optional<OidcFlowRecord> result;
                try (PreparedStatement select = conn.prepareStatement("""
                        SELECT state, nonce, code_verifier, return_to, invite_code, expires_at, provider
                        FROM oidc_flows
                        WHERE state = ?
                        FOR UPDATE
                        """)) {
                    select.setString(1, state);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) {
                            result = Optional.empty();
                        } else {
                            result = Optional.of(new OidcFlowRecord(
                                    rs.getString("state"),
                                    rs.getString("nonce"),
                                    rs.getString("code_verifier"),
                                    rs.getString("return_to"),
                                    rs.getString("invite_code"),
                                    rs.getTimestamp("expires_at").toInstant(),
                                    rs.getString("provider")
                            ));
                        }
                    }
                }
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM oidc_flows WHERE state = ?")) {
                    delete.setString(1, state);
                    delete.executeUpdate();
                }
                conn.commit();
                return result;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<InvitationRecord> findInvitationByCode(String code) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, code, email, role, max_uses, used_count, created_by, expires_at
                     FROM invitations
                     WHERE code = ?
                     """)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readInvitation(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void acceptInvitation(UUID invitationId, UUID tenantId, UUID userId, String role) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE invitations
                        SET used_count = used_count + 1
                        WHERE id = ? AND used_count < max_uses AND expires_at > now()
                        """)) {
                    ps.setObject(1, invitationId);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        throw new IllegalStateException("Invitation is not usable");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO memberships(user_id, tenant_id, role)
                        VALUES (?, ?, ?)
                        ON CONFLICT(user_id, tenant_id) DO UPDATE SET is_active = true,
                          role = CASE
                            WHEN memberships.role = 'OWNER' THEN memberships.role
                            WHEN memberships.role = 'ADMIN' AND EXCLUDED.role IN ('MEMBER','VIEWER') THEN memberships.role
                            ELSE EXCLUDED.role
                          END
                        """)) {
                    ps.setObject(1, userId);
                    ps.setObject(2, tenantId);
                    ps.setString(3, role);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO invitation_usages(invitation_id, used_by)
                        VALUES (?, ?)
                        """)) {
                    ps.setObject(1, invitationId);
                    ps.setObject(2, userId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<MembershipRecord> listTenantMembers(UUID tenantId, int offset, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, tenant_id, role, is_active
                     FROM memberships
                     WHERE tenant_id = ?
                     ORDER BY joined_at
                     OFFSET ? LIMIT ?
                     """)) {
            ps.setObject(1, tenantId);
            ps.setInt(2, offset);
            ps.setInt(3, limit);
            List<MembershipRecord> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MembershipRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("role"),
                            rs.getBoolean("is_active")
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int updateUserDisplayName(UUID userId, String displayName) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE users SET display_name = ? WHERE id = ?
                     """)) {
            ps.setString(1, displayName);
            ps.setObject(2, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int updateMemberRole(UUID tenantId, UUID memberId, String role) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE memberships SET role = ?
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setString(1, role);
            ps.setObject(2, tenantId);
            ps.setObject(3, memberId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * AUTH-014 Phase 1 (creation_policy=ADMIN_ONLY): returns true when the
     * user holds an active OWNER membership in any tenant. Used as a
     * stand-in for platform-admin until a dedicated role is introduced.
     */
    public boolean hasOwnerRoleAnyTenant(UUID userId) {
        return hasRoleAtLeast(userId, "OWNER");
    }

    /**
     * AUTH-014 Phase 1 (creation_policy=INVITE_ONLY): returns true when the
     * user holds an active ADMIN or OWNER membership in any tenant.
     */
    public boolean hasAdminRoleAnyTenant(UUID userId) {
        return hasRoleAtLeast(userId, "ADMIN");
    }

    private boolean hasRoleAtLeast(UUID userId, String minRole) {
        // Explicit enumeration avoids coupling to the role hierarchy in this
        // SQL layer; callers use the two public helpers above.
        String sql = "OWNER".equals(minRole)
                ? "SELECT 1 FROM memberships WHERE user_id = ? AND is_active = true AND role = 'OWNER' LIMIT 1"
                : "SELECT 1 FROM memberships WHERE user_id = ? AND is_active = true AND role IN ('OWNER','ADMIN') LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countActiveOwners(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT COUNT(*)
                     FROM memberships
                     WHERE tenant_id = ? AND is_active = true AND role = 'OWNER'
                     """)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int deactivateMember(UUID tenantId, UUID memberId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE memberships
                     SET is_active = false
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, memberId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void revokeSessionsForUserTenant(UUID userId, UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sessions
                     SET invalidated_at = now()
                     WHERE user_id = ? AND tenant_id = ? AND invalidated_at IS NULL
                     """)) {
            ps.setObject(1, userId);
            ps.setObject(2, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID createInvitation(UUID tenantId, String code, String email, String role, int maxUses, UUID createdBy, Instant expiresAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO invitations(tenant_id, code, email, role, max_uses, created_by, expires_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     RETURNING id
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, email);
            ps.setString(4, role);
            ps.setInt(5, maxUses);
            ps.setObject(6, createdBy);
            ps.setTimestamp(7, Timestamp.from(expiresAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<InvitationRecord> listInvitations(UUID tenantId, int offset, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, code, email, role, max_uses, used_count, created_by, expires_at
                     FROM invitations
                     WHERE tenant_id = ?
                     ORDER BY created_at DESC
                     OFFSET ? LIMIT ?
                     """)) {
            ps.setObject(1, tenantId);
            ps.setInt(2, offset);
            ps.setInt(3, limit);
            List<InvitationRecord> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readInvitation(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int cancelInvitation(UUID tenantId, UUID invitationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     DELETE FROM invitations
                     WHERE tenant_id = ? AND id = ? AND used_count = 0 AND expires_at > now()
                     """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, invitationId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveSigningKey(String kid, String publicPem, String privatePem) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO signing_keys(kid, public_key, private_key, status)
                     VALUES (?, ?, ?, 'active')
                     ON CONFLICT(kid) DO UPDATE SET public_key = EXCLUDED.public_key, private_key = EXCLUDED.private_key
                     """)) {
            ps.setString(1, kid);
            ps.setString(2, publicPem);
            ps.setString(3, privatePem);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<SigningKeyRecord> loadActiveSigningKey() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT kid, public_key, private_key
                     FROM signing_keys
                     WHERE status = 'active'
                     ORDER BY created_at DESC
                     LIMIT 1
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SigningKeyRecord(
                        rs.getString("kid"),
                        rs.getString("public_key"),
                        rs.getString("private_key")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static UserRecord readUser(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("google_sub")
        );
    }

    private static InvitationRecord readInvitation(ResultSet rs) throws SQLException {
        return new InvitationRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("code"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getInt("max_uses"),
                rs.getInt("used_count"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("expires_at").toInstant()
        );
    }

    public record SigningKeyRecord(String kid, String publicPem, String privatePem) {
    }

    public List<SigningKeyMeta> listSigningKeys() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT kid, status, created_at, rotated_at, expires_at
                     FROM signing_keys
                     ORDER BY created_at DESC
                     """)) {
            List<SigningKeyMeta> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SigningKeyMeta(
                            rs.getString("kid"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("rotated_at") == null ? null : rs.getTimestamp("rotated_at").toInstant(),
                            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant()
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rotateSigningKey(String oldKid, String newKid, String publicKey, String privateKey) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement upd = conn.prepareStatement("""
                        UPDATE signing_keys
                        SET status = 'rotated', rotated_at = now()
                        WHERE kid = ? AND status = 'active'
                        """)) {
                    upd.setString(1, oldKid);
                    upd.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement("""
                        INSERT INTO signing_keys(kid, public_key, private_key, status)
                        VALUES (?, ?, ?, 'active')
                        """)) {
                    ins.setString(1, newKid);
                    ins.setString(2, publicKey);
                    ins.setString(3, privateKey);
                    ins.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void revokeSigningKey(String kid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE signing_keys
                     SET status = 'revoked', rotated_at = now()
                     WHERE kid = ?
                     """)) {
            ps.setString(1, kid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countActiveSessions(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT COUNT(*)
                     FROM sessions
                     WHERE user_id = ? AND invalidated_at IS NULL AND expires_at > now()
                     """)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int revokeOldestActiveSessions(UUID userId, int count) {
        if (count <= 0) {
            return 0;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     WITH oldest AS (
                       SELECT id
                       FROM sessions
                       WHERE user_id = ? AND invalidated_at IS NULL AND expires_at > now()
                       ORDER BY created_at ASC
                       LIMIT ?
                     )
                     UPDATE sessions
                     SET invalidated_at = now()
                     WHERE id IN (SELECT id FROM oldest)
                     """)) {
            ps.setObject(1, userId);
            ps.setInt(2, count);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<MembershipRecord> findMembershipById(UUID memberId, UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, tenant_id, role, is_active
                     FROM memberships
                     WHERE id = ? AND tenant_id = ?
                     """)) {
            ps.setObject(1, memberId);
            ps.setObject(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MembershipRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("role"),
                        rs.getBoolean("is_active")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<MembershipRecord> findMembershipByUser(UUID tenantId, UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, tenant_id, role, is_active
                     FROM memberships
                     WHERE tenant_id = ? AND user_id = ?
                     """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new MembershipRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("role"),
                        rs.getBoolean("is_active")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int transferOwnership(UUID tenantId, UUID fromUserId, UUID toUserId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement actor = conn.prepareStatement("""
                        SELECT role, is_active
                        FROM memberships
                        WHERE tenant_id = ? AND user_id = ?
                        FOR UPDATE
                        """)) {
                    actor.setObject(1, tenantId);
                    actor.setObject(2, fromUserId);
                    try (ResultSet rs = actor.executeQuery()) {
                        if (!rs.next() || !rs.getBoolean("is_active") || !"OWNER".equalsIgnoreCase(rs.getString("role"))) {
                            conn.rollback();
                            return 0;
                        }
                    }
                }
                try (PreparedStatement target = conn.prepareStatement("""
                        SELECT is_active
                        FROM memberships
                        WHERE tenant_id = ? AND user_id = ?
                        FOR UPDATE
                        """)) {
                    target.setObject(1, tenantId);
                    target.setObject(2, toUserId);
                    try (ResultSet rs = target.executeQuery()) {
                        if (!rs.next() || !rs.getBoolean("is_active")) {
                            conn.rollback();
                            return 0;
                        }
                    }
                }
                try (PreparedStatement demote = conn.prepareStatement("""
                        UPDATE memberships
                        SET role = 'ADMIN'
                        WHERE tenant_id = ? AND user_id = ? AND role = 'OWNER'
                        """)) {
                    demote.setObject(1, tenantId);
                    demote.setObject(2, fromUserId);
                    demote.executeUpdate();
                }
                try (PreparedStatement promote = conn.prepareStatement("""
                        UPDATE memberships
                        SET role = 'OWNER'
                        WHERE tenant_id = ? AND user_id = ?
                        """)) {
                    promote.setObject(1, tenantId);
                    promote.setObject(2, toUserId);
                    int updated = promote.executeUpdate();
                    conn.commit();
                    return updated;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void insertAuditLog(
            String eventType,
            UUID actorId,
            String actorIp,
            UUID tenantId,
            String targetType,
            String targetId,
            String detailJson,
            UUID requestId
    ) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO audit_logs(
                       event_type, actor_id, actor_ip, tenant_id, target_type, target_id, detail, request_id
                     ) VALUES (?, ?, ?::inet, ?, ?, ?, ?::jsonb, ?)
                     """)) {
            ps.setString(1, eventType);
            ps.setObject(2, actorId);
            ps.setString(3, actorIp);
            ps.setObject(4, tenantId);
            ps.setString(5, targetType);
            ps.setString(6, targetId);
            ps.setString(7, detailJson == null ? "{}" : detailJson);
            ps.setObject(8, requestId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public record SigningKeyMeta(String kid, String status, Instant createdAt, Instant rotatedAt, Instant expiresAt) {
    }

    public record UserTenantInfo(UUID id, String name, String slug, String role,
                                 String plan, int memberCount, String emailDomain) {
        // Backward-compat constructor used by older call sites / tests.
        public UserTenantInfo(UUID id, String name, String slug, String role) {
            this(id, name, slug, role, "FREE", 0, null);
        }
    }

    public record TenantDetailRecord(
            UUID id,
            String name,
            String slug,
            boolean autoJoin,
            String plan,
            int maxMembers,
            boolean active,
            String logoUrl,
            String primaryColor,
            String theme
    ) {
    }

    public Optional<M2mClientRecord> findM2mClient(String clientId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, client_id, client_secret_hash, scopes, is_active
                     FROM m2m_clients
                     WHERE client_id = ?
                     """)) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new M2mClientRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("client_id"),
                        rs.getString("client_secret_hash"),
                        rs.getString("scopes"),
                        rs.getBoolean("is_active")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID createM2mClient(UUID tenantId, String clientId, String secretHash, String scopesCsv) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO m2m_clients(tenant_id, client_id, client_secret_hash, scopes)
                     VALUES (?, ?, ?, ?)
                     RETURNING id
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, clientId);
            ps.setString(3, secretHash);
            ps.setString(4, scopesCsv);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<M2mClientRecord> listM2mClients(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, client_id, client_secret_hash, scopes, is_active
                     FROM m2m_clients
                     WHERE tenant_id = ?
                     ORDER BY created_at DESC
                     """)) {
            ps.setObject(1, tenantId);
            List<M2mClientRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new M2mClientRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("client_id"),
                            rs.getString("client_secret_hash"),
                            rs.getString("scopes"),
                            rs.getBoolean("is_active")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID createWebhook(UUID tenantId, String endpoint, String secret, String eventsCsv) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO webhook_subscriptions(tenant_id, endpoint_url, secret, events)
                     VALUES (?, ?, ?, ?)
                     RETURNING id
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, endpoint);
            ps.setString(3, secret);
            ps.setString(4, eventsCsv);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<WebhookRecord> listWebhooks(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, endpoint_url, secret, events, is_active, created_at
                     FROM webhook_subscriptions
                     WHERE tenant_id = ?
                     ORDER BY created_at DESC
                     """)) {
            ps.setObject(1, tenantId);
            List<WebhookRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WebhookRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("endpoint_url"),
                            rs.getString("secret"),
                            rs.getString("events"),
                            rs.getBoolean("is_active"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<WebhookRecord> findWebhook(UUID tenantId, UUID webhookId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, endpoint_url, secret, events, is_active, created_at
                     FROM webhook_subscriptions
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, webhookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new WebhookRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("endpoint_url"),
                            rs.getString("secret"),
                            rs.getString("events"),
                            rs.getBoolean("is_active"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int updateWebhook(UUID tenantId, UUID webhookId, String endpointUrl, String events, boolean isActive) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE webhook_subscriptions
                     SET endpoint_url = ?, events = ?, is_active = ?
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setString(1, endpointUrl);
            ps.setString(2, events);
            ps.setBoolean(3, isActive);
            ps.setObject(4, tenantId);
            ps.setObject(5, webhookId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int deactivateWebhook(UUID tenantId, UUID webhookId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE webhook_subscriptions
                     SET is_active = false
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, webhookId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID upsertIdpConfig(UUID tenantId, String providerType, String metadataUrl, String issuer, String clientId, String x509Cert) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO idp_configs(tenant_id, provider_type, metadata_url, issuer, client_id, x509_cert, is_active)
                     VALUES (?, ?, ?, ?, ?, ?, true)
                     ON CONFLICT(tenant_id, provider_type) DO UPDATE SET
                       metadata_url = EXCLUDED.metadata_url,
                       issuer = EXCLUDED.issuer,
                       client_id = EXCLUDED.client_id,
                       x509_cert = EXCLUDED.x509_cert,
                       is_active = true
                     RETURNING id
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, providerType);
            ps.setString(3, metadataUrl);
            ps.setString(4, issuer);
            ps.setString(5, clientId);
            ps.setString(6, x509Cert);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<IdpConfigRecord> listIdpConfigs(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, provider_type, metadata_url, issuer, client_id, x509_cert, is_active, created_at
                     FROM idp_configs
                     WHERE tenant_id = ?
                     ORDER BY created_at DESC
                     """)) {
            ps.setObject(1, tenantId);
            List<IdpConfigRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new IdpConfigRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("provider_type"),
                            rs.getString("metadata_url"),
                            rs.getString("issuer"),
                            rs.getString("client_id"),
                            rs.getString("x509_cert"),
                            rs.getBoolean("is_active"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<IdpConfigRecord> findIdpConfig(UUID tenantId, String providerType) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, provider_type, metadata_url, issuer, client_id, x509_cert, is_active, created_at
                     FROM idp_configs
                     WHERE tenant_id = ? AND provider_type = ? AND is_active = true
                     LIMIT 1
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, providerType);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new IdpConfigRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("provider_type"),
                        rs.getString("metadata_url"),
                        rs.getString("issuer"),
                        rs.getString("client_id"),
                        rs.getString("x509_cert"),
                        rs.getBoolean("is_active"),
                        rs.getTimestamp("created_at").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID createTenant(UUID createdBy, String name, String slug, boolean autoJoin) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                UUID tenantId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO tenants(name, slug, created_by, auto_join)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """)) {
                    ps.setString(1, name);
                    ps.setString(2, slug);
                    ps.setObject(3, createdBy);
                    ps.setBoolean(4, autoJoin);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        tenantId = rs.getObject("id", UUID.class);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO memberships(user_id, tenant_id, role)
                        VALUES (?, ?, 'OWNER')
                        ON CONFLICT(user_id, tenant_id) DO UPDATE SET role='OWNER', is_active=true
                        """)) {
                    ps.setObject(1, createdBy);
                    ps.setObject(2, tenantId);
                    ps.executeUpdate();
                }
                conn.commit();
                return tenantId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<TenantAdminRecord> listTenants(int offset, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT t.id, t.name, t.slug, t.plan, t.is_active, t.created_at,
                            COUNT(m.id) FILTER (WHERE m.is_active = true) AS member_count
                     FROM tenants t
                     LEFT JOIN memberships m ON m.tenant_id = t.id
                     GROUP BY t.id
                     ORDER BY t.created_at DESC
                     OFFSET ? LIMIT ?
                     """)) {
            ps.setInt(1, offset);
            ps.setInt(2, limit);
            List<TenantAdminRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TenantAdminRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getString("slug"),
                            rs.getString("plan"),
                            rs.getBoolean("is_active"),
                            rs.getInt("member_count"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int setTenantActive(UUID tenantId, boolean active) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE tenants
                     SET is_active = ?
                     WHERE id = ?
                     """)) {
            ps.setBoolean(1, active);
            ps.setObject(2, tenantId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<BasicUserRecord> listUsers(int offset, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, email, display_name, created_at, is_active, locale
                     FROM users
                     ORDER BY created_at DESC
                     OFFSET ? LIMIT ?
                     """)) {
            ps.setInt(1, offset);
            ps.setInt(2, limit);
            List<BasicUserRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BasicUserRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getBoolean("is_active"),
                            rs.getString("locale"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> listAuditLogs(UUID tenantId, int offset, int limit) {
        String sql = """
                SELECT id, timestamp, event_type, actor_id, tenant_id, target_type, target_id, detail, request_id
                FROM audit_logs
                WHERE (?::uuid IS NULL OR tenant_id = ?)
                ORDER BY timestamp DESC
                OFFSET ? LIMIT ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, tenantId);
            ps.setInt(3, offset);
            ps.setInt(4, limit);
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                    row.put("eventType", rs.getString("event_type"));
                    row.put("actorId", rs.getObject("actor_id"));
                    row.put("tenantId", rs.getObject("tenant_id"));
                    row.put("targetType", rs.getString("target_type"));
                    row.put("targetId", rs.getString("target_id"));
                    row.put("detail", rs.getString("detail"));
                    row.put("requestId", rs.getObject("request_id"));
                    out.add(row);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID createPolicy(UUID tenantId, String resource, String action, String conditionJson, String effect, int priority) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO policies(tenant_id, resource, action, condition, effect, priority)
                     VALUES (?, ?, ?, ?::jsonb, ?, ?)
                     RETURNING id
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, resource);
            ps.setString(3, action);
            ps.setString(4, conditionJson == null ? "{}" : conditionJson);
            ps.setString(5, effect);
            ps.setInt(6, priority);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PolicyRecord> listPolicies(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, resource, action, condition, effect, priority, is_active
                     FROM policies
                     WHERE tenant_id = ? AND is_active = true
                     ORDER BY priority DESC, created_at DESC
                     """)) {
            ps.setObject(1, tenantId);
            List<PolicyRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PolicyRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("resource"),
                            rs.getString("action"),
                            rs.getString("condition"),
                            rs.getString("effect"),
                            rs.getInt("priority"),
                            rs.getBoolean("is_active")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<PolicyRecord> findMatchingPolicy(UUID tenantId, String resource, String action) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, resource, action, condition, effect, priority, is_active
                     FROM policies
                     WHERE tenant_id = ? AND resource = ? AND action = ? AND is_active = true
                     ORDER BY priority DESC, created_at DESC
                     LIMIT 1
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, resource);
            ps.setString(3, action);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PolicyRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("resource"),
                        rs.getString("action"),
                        rs.getString("condition"),
                        rs.getString("effect"),
                        rs.getInt("priority"),
                        rs.getBoolean("is_active")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PlanRecord> listPlans() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, name, max_members, max_apps, features
                     FROM plans
                     ORDER BY id
                     """)) {
            List<PlanRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PlanRecord(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getInt("max_members"),
                            rs.getInt("max_apps"),
                            rs.getString("features")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<SubscriptionRecord> findSubscription(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, tenant_id, plan_id, status, stripe_sub_id, started_at, expires_at
                     FROM subscriptions
                     WHERE tenant_id = ?
                     ORDER BY started_at DESC
                     LIMIT 1
                     """)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SubscriptionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("plan_id"),
                        rs.getString("status"),
                        rs.getString("stripe_sub_id"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── SAAS-008: usage metering ───────────────────────────────────────────

    /**
     * Record a billable usage event. Small payloads; callers should avoid
     * stuffing large objects into {@code meta}. Quantity defaults to 1 for
     * "happened once" events; set higher for batched reports.
     */
    public void recordUsage(UUID tenantId, String metric, long quantity, String metaJson) {
        if (metric == null || metric.isBlank()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO billing_usage(tenant_id, metric, quantity, meta)
                     VALUES (?, ?, ?, ?::jsonb)
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, metric);
            ps.setLong(3, quantity);
            ps.setString(4, metaJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Usage metering failures must never break the auth hot path.
            System.getLogger("volta.billing").log(System.Logger.Level.WARNING,
                    "recordUsage failed: " + e.getMessage());
        }
    }

    /**
     * Aggregate usage per metric over a time window. Returns
     * {@code metric → total quantity}. Use this for per-tenant billing
     * dashboards and quota enforcement checks.
     *
     * @param from inclusive lower bound; null = epoch
     * @param to   exclusive upper bound; null = now
     */
    public Map<String, Long> aggregateUsage(UUID tenantId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT metric, COALESCE(SUM(quantity), 0) AS total "
                + "FROM billing_usage WHERE tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (from != null) { sql.append(" AND recorded_at >= ?"); params.add(Timestamp.from(from)); }
        if (to   != null) { sql.append(" AND recorded_at <  ?"); params.add(Timestamp.from(to)); }
        sql.append(" GROUP BY metric ORDER BY metric");

        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("metric"), rs.getLong("total"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Latest per-metric timestamp — useful to detect staleness. */
    public Map<String, Instant> lastUsageByMetric(UUID tenantId) {
        Map<String, Instant> out = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT metric, MAX(recorded_at) AS last
                     FROM billing_usage
                     WHERE tenant_id = ?
                     GROUP BY metric
                     """)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("last");
                    if (ts != null) out.put(rs.getString("metric"), ts.toInstant());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public UUID upsertSubscription(UUID tenantId, String planId, String status, Instant expiresAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO subscriptions(tenant_id, plan_id, status, started_at, expires_at)
                     VALUES (?, ?, ?, now(), ?)
                     RETURNING id
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, planId);
            ps.setString(3, status);
            ps.setTimestamp(4, expiresAt == null ? null : Timestamp.from(expiresAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Look up a user's preferred locale. Returns empty when unset so the
     * caller can fall back to Accept-Language / default.
     */
    public Optional<String> getUserLocale(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT locale FROM users WHERE id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String loc = rs.getString("locale");
                return (loc == null || loc.isBlank()) ? Optional.empty() : Optional.of(loc);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Same as {@link #getUserLocale(UUID)} but keyed by email. */
    public Optional<String> getUserLocaleByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT locale FROM users WHERE lower(email) = lower(?) LIMIT 1")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String loc = rs.getString("locale");
                return (loc == null || loc.isBlank()) ? Optional.empty() : Optional.of(loc);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int setUserLocale(UUID userId, String locale) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE users
                     SET locale = ?
                     WHERE id = ?
                     """)) {
            ps.setString(1, locale);
            ps.setObject(2, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<BasicUserRecord> listScimUsers(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT u.id, u.email, u.display_name, u.is_active, u.locale, u.created_at
                     FROM memberships m
                     JOIN users u ON u.id = m.user_id
                     WHERE m.tenant_id = ? AND m.is_active = true
                     ORDER BY u.created_at DESC
                     """)) {
            ps.setObject(1, tenantId);
            List<BasicUserRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BasicUserRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getBoolean("is_active"),
                            rs.getString("locale"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<BasicUserRecord> findScimUser(UUID tenantId, UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT u.id, u.email, u.display_name, u.is_active, u.locale, u.created_at
                     FROM memberships m
                     JOIN users u ON u.id = m.user_id
                     WHERE m.tenant_id = ? AND u.id = ?
                     LIMIT 1
                     """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new BasicUserRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getBoolean("is_active"),
                        rs.getString("locale"),
                        rs.getTimestamp("created_at").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID createScimUser(UUID tenantId, String email, String displayName) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                UUID userId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO users(email, display_name, google_sub, is_active)
                        VALUES (?, ?, ?, true)
                        ON CONFLICT(email) DO UPDATE SET display_name = EXCLUDED.display_name
                        RETURNING id
                        """)) {
                    ps.setString(1, email);
                    ps.setString(2, displayName);
                    ps.setString(3, "scim:" + SecurityUtils.randomUrlSafe(16));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        userId = rs.getObject("id", UUID.class);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO memberships(user_id, tenant_id, role, is_active)
                        VALUES (?, ?, 'MEMBER', true)
                        ON CONFLICT(user_id, tenant_id) DO UPDATE SET is_active=true
                        """)) {
                    ps.setObject(1, userId);
                    ps.setObject(2, tenantId);
                    ps.executeUpdate();
                }
                conn.commit();
                return userId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int updateScimUser(UUID userId, String email, String displayName, boolean active) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE users
                     SET email = ?, display_name = ?, is_active = ?
                     WHERE id = ?
                     """)) {
            ps.setString(1, email);
            ps.setString(2, displayName);
            ps.setBoolean(3, active);
            ps.setObject(4, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int deactivateScimMembership(UUID tenantId, UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE memberships
                     SET is_active = false
                     WHERE tenant_id = ? AND user_id = ?
                     """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int deleteUserData(UUID userId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE sessions SET invalidated_at = now() WHERE user_id = ?")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM memberships WHERE user_id = ?")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE users
                        SET email = concat('deleted+', id::text, '@example.invalid'),
                            display_name = 'Deleted User',
                            is_active = false
                        WHERE id = ?
                        """)) {
                    ps.setObject(1, userId);
                    int updated = ps.executeUpdate();
                    conn.commit();
                    return updated;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void upsertUserMfa(UUID userId, String type, String secret, boolean active) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO user_mfa(user_id, type, secret, is_active)
                     VALUES (?, ?, ?, ?)
                     ON CONFLICT (user_id, type) DO UPDATE SET secret = EXCLUDED.secret, is_active = EXCLUDED.is_active
                     """)) {
            ps.setObject(1, userId);
            ps.setString(2, type);
            ps.setString(3, secret);
            ps.setBoolean(4, active);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<UserMfaRecord> findUserMfa(UUID userId, String type) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, type, secret, is_active, created_at
                     FROM user_mfa
                     WHERE user_id = ? AND type = ?
                     LIMIT 1
                     """)) {
            ps.setObject(1, userId);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new UserMfaRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("secret"),
                        rs.getBoolean("is_active"),
                        rs.getTimestamp("created_at").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasActiveMfa(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT 1
                     FROM user_mfa
                     WHERE user_id = ? AND is_active = true
                     LIMIT 1
                     """)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markSessionMfaVerified(UUID sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sessions
                     SET mfa_verified_at = now()
                     WHERE id = ?
                     """)) {
            ps.setObject(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void replaceRecoveryCodes(UUID userId, List<String> codeHashes) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM mfa_recovery_codes WHERE user_id = ?")) {
                    del.setObject(1, userId);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement("""
                        INSERT INTO mfa_recovery_codes(user_id, code_hash)
                        VALUES (?, ?)
                        """)) {
                    for (String hash : codeHashes) {
                        ins.setObject(1, userId);
                        ins.setString(2, hash);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean consumeRecoveryCode(UUID userId, String codeHash) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE mfa_recovery_codes
                     SET used_at = now()
                     WHERE user_id = ? AND code_hash = ? AND used_at IS NULL
                     """)) {
            ps.setObject(1, userId);
            ps.setString(2, codeHash);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countUnusedRecoveryCodes(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void deactivateUserMfa(UUID userId, String type) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE user_mfa SET is_active = false WHERE user_id = ? AND type = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, type);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteRecoveryCodes(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM mfa_recovery_codes WHERE user_id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateTenantMfaPolicy(UUID tenantId, boolean mfaRequired, int graceDays) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE tenants
                     SET mfa_required = ?,
                         mfa_grace_until = CASE WHEN ? THEN now() + (? || ' days')::interval ELSE NULL END
                     WHERE id = ?
                     """)) {
            ps.setBoolean(1, mfaRequired);
            ps.setBoolean(2, mfaRequired);
            ps.setInt(3, graceDays);
            ps.setObject(4, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Upsert a known device. Returns true if the device is new (INSERT), false if existing (UPDATE).
     */
    public boolean upsertKnownDevice(UUID userId, String fingerprint, String label, String lastIp) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO known_devices (user_id, fingerprint, label, last_ip)
                     VALUES (?, ?, ?, ?)
                     ON CONFLICT (user_id, fingerprint)
                     DO UPDATE SET last_seen_at = now(), last_ip = EXCLUDED.last_ip
                     RETURNING (xmax = 0) AS is_new
                     """)) {
            ps.setObject(1, userId);
            ps.setString(2, fingerprint);
            ps.setString(3, label);
            ps.setString(4, lastIp);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean("is_new");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countKnownDevices(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM known_devices WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * AUTH-004-v2: list all known devices for a user. Returned entries are
     * sorted by most-recently-seen first so the UI can show active devices
     * at the top.
     */
    public List<Map<String, Object>> listKnownDevices(UUID userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT fingerprint, label, last_ip, first_seen_at, last_seen_at
                     FROM known_devices
                     WHERE user_id = ?
                     ORDER BY last_seen_at DESC
                     """)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("fingerprint", rs.getString("fingerprint"));
                    row.put("label", rs.getString("label"));
                    row.put("lastIp", rs.getString("last_ip"));
                    java.sql.Timestamp first = rs.getTimestamp("first_seen_at");
                    java.sql.Timestamp last  = rs.getTimestamp("last_seen_at");
                    if (first != null) row.put("firstSeenAt", first.toInstant().toString());
                    if (last  != null) row.put("lastSeenAt",  last.toInstant().toString());
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** AUTH-004-v2: delete one device by fingerprint. Returns rows affected. */
    public int deleteKnownDevice(UUID userId, String fingerprint) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM known_devices WHERE user_id = ? AND fingerprint = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, fingerprint);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** AUTH-004-v2: reset all known devices for a user (admin use). Returns rows affected. */
    public int deleteAllKnownDevices(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM known_devices WHERE user_id = ?")) {
            ps.setObject(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Passkey CRUD ---

    public record PasskeyRecord(UUID id, UUID userId, byte[] credentialId, byte[] publicKey,
                                long signCount, String transports, String name, UUID aaguid,
                                boolean backupEligible, boolean backupState,
                                Instant createdAt, Instant lastUsedAt) {}

    public UUID createPasskey(UUID userId, byte[] credentialId, byte[] publicKey, long signCount,
                              String transports, String name, UUID aaguid, boolean backupEligible, boolean backupState) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO user_passkeys (user_id, credential_id, public_key, sign_count, transports, name, aaguid, backup_eligible, backup_state)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     RETURNING id
                     """)) {
            ps.setObject(1, userId);
            ps.setBytes(2, credentialId);
            ps.setBytes(3, publicKey);
            ps.setLong(4, signCount);
            ps.setString(5, transports);
            ps.setString(6, name);
            ps.setObject(7, aaguid);
            ps.setBoolean(8, backupEligible);
            ps.setBoolean(9, backupState);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PasskeyRecord> listPasskeys(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, credential_id, public_key, sign_count, transports, name, aaguid,
                            backup_eligible, backup_state, created_at, last_used_at
                     FROM user_passkeys WHERE user_id = ? ORDER BY created_at DESC
                     """)) {
            ps.setObject(1, userId);
            List<PasskeyRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PasskeyRecord(
                            rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getBytes("credential_id"), rs.getBytes("public_key"),
                            rs.getLong("sign_count"), rs.getString("transports"), rs.getString("name"),
                            rs.getObject("aaguid", UUID.class),
                            rs.getBoolean("backup_eligible"), rs.getBoolean("backup_state"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toInstant() : null
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<PasskeyRecord> findPasskeyByCredentialId(byte[] credentialId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, credential_id, public_key, sign_count, transports, name, aaguid,
                            backup_eligible, backup_state, created_at, last_used_at
                     FROM user_passkeys WHERE credential_id = ?
                     """)) {
            ps.setBytes(1, credentialId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PasskeyRecord(
                            rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getBytes("credential_id"), rs.getBytes("public_key"),
                            rs.getLong("sign_count"), rs.getString("transports"), rs.getString("name"),
                            rs.getObject("aaguid", UUID.class),
                            rs.getBoolean("backup_eligible"), rs.getBoolean("backup_state"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toInstant() : null
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updatePasskeyCounter(UUID passkeyId, long signCount) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE user_passkeys SET sign_count = ?, last_used_at = now() WHERE id = ? AND sign_count < ?")) {
            ps.setLong(1, signCount);
            ps.setObject(2, passkeyId);
            ps.setLong(3, signCount);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException("Passkey counter replay detected: id=" + passkeyId + " newCount=" + signCount);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int deletePasskey(UUID userId, UUID passkeyId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM user_passkeys WHERE id = ? AND user_id = ?")) {
            ps.setObject(1, passkeyId);
            ps.setObject(2, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countPasskeys(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM user_passkeys WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Magic Links ---

    public record MagicLinkRecord(UUID id, String email, String token, Instant expiresAt, Instant usedAt) {}

    public String createMagicLink(String email, int ttlMinutes) {
        String token = SecurityUtils.randomUrlSafe(48);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO magic_links (email, token, expires_at) VALUES (?, ?, now() + interval '" + ttlMinutes + " minutes')")) {
            ps.setString(1, email);
            ps.setString(2, token);
            ps.executeUpdate();
            return token;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<MagicLinkRecord> consumeMagicLink(String token) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE magic_links SET used_at = now()
                     WHERE token = ? AND expires_at > now() AND used_at IS NULL
                     RETURNING id, email, token, expires_at, used_at
                     """)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new MagicLinkRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("token"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("used_at") != null ? rs.getTimestamp("used_at").toInstant() : null
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID enqueueOutboxEvent(UUID tenantId, String eventType, String payloadJson) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO outbox_events(tenant_id, event_type, payload)
                     VALUES (?, ?, ?::jsonb)
                     RETURNING id
                     """)) {
            ps.setObject(1, tenantId);
            ps.setString(2, eventType);
            ps.setString(3, payloadJson);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<OutboxRecord> claimPendingOutboxEvents(int limit, String workerId, int lockSeconds) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     WITH cte AS (
                       SELECT id
                       FROM outbox_events
                       WHERE published_at IS NULL
                         AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                         AND (processing_until IS NULL OR processing_until < now())
                       ORDER BY created_at
                       LIMIT ?
                       FOR UPDATE SKIP LOCKED
                     )
                     UPDATE outbox_events o
                     SET processing_by = ?, processing_until = now() + (? || ' seconds')::interval
                     FROM cte
                     WHERE o.id = cte.id
                     RETURNING o.id, o.tenant_id, o.event_type, o.payload, o.created_at, o.published_at, o.attempt_count, o.next_attempt_at, o.last_error
                     """)) {
            ps.setInt(1, limit);
            ps.setString(2, workerId);
            ps.setInt(3, lockSeconds);
            List<OutboxRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new OutboxRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                            rs.getInt("attempt_count"),
                            rs.getTimestamp("next_attempt_at") == null ? null : rs.getTimestamp("next_attempt_at").toInstant(),
                            rs.getString("last_error")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markOutboxPublished(UUID outboxId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE outbox_events
                     SET published_at = now(), last_error = NULL, processing_by = NULL, processing_until = NULL
                     WHERE id = ?
                     """)) {
            ps.setObject(1, outboxId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markOutboxRetry(UUID outboxId, int attemptCount, Instant nextAttemptAt, String lastError) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE outbox_events
                     SET attempt_count = ?, next_attempt_at = ?, last_error = ?, processing_by = NULL, processing_until = NULL
                     WHERE id = ?
                     """)) {
            ps.setInt(1, attemptCount);
            ps.setTimestamp(2, nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt));
            ps.setString(3, lastError);
            ps.setObject(4, outboxId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void clearOutboxLock(UUID outboxId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE outbox_events
                     SET processing_by = NULL, processing_until = NULL
                     WHERE id = ?
                     """)) {
            ps.setObject(1, outboxId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markWebhookSuccess(UUID webhookId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE webhook_subscriptions
                     SET last_success_at = now()
                     WHERE id = ?
                     """)) {
            ps.setObject(1, webhookId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markWebhookFailure(UUID webhookId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE webhook_subscriptions
                     SET last_failure_at = now()
                     WHERE id = ?
                     """)) {
            ps.setObject(1, webhookId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public record WebhookDeliveryRecord(UUID id, UUID outboxEventId, UUID webhookId, String eventType,
                                          String status, Integer statusCode, String responseBody, Instant createdAt) {}

    public List<WebhookDeliveryRecord> listWebhookDeliveries(UUID webhookId, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, outbox_event_id, webhook_id, event_type, status, status_code, response_body, created_at
                     FROM webhook_deliveries
                     WHERE webhook_id = ?
                     ORDER BY created_at DESC
                     LIMIT ?
                     """)) {
            ps.setObject(1, webhookId);
            ps.setInt(2, limit);
            List<WebhookDeliveryRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WebhookDeliveryRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("outbox_event_id", UUID.class),
                            rs.getObject("webhook_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("status"),
                            rs.getObject("status_code", Integer.class),
                            rs.getString("response_body"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void insertWebhookDelivery(UUID outboxEventId, UUID webhookId, String eventType, String status, Integer statusCode, String responseBody) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO webhook_deliveries(outbox_event_id, webhook_id, event_type, status, status_code, response_body)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, outboxEventId);
            ps.setObject(2, webhookId);
            ps.setString(3, eventType);
            ps.setString(4, status);
            if (statusCode == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, statusCode);
            }
            ps.setString(6, responseBody);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public record M2mClientRecord(
            UUID id,
            UUID tenantId,
            String clientId,
            String clientSecretHash,
            String scopes,
            boolean active
    ) {
    }

    public record WebhookRecord(
            UUID id,
            UUID tenantId,
            String endpointUrl,
            String secret,
            String events,
            boolean active,
            Instant createdAt
    ) {
    }

    public record OutboxRecord(
            UUID id,
            UUID tenantId,
            String eventType,
            String payload,
            Instant createdAt,
            Instant publishedAt,
            int attemptCount,
            Instant nextAttemptAt,
            String lastError
    ) {
    }

    public record IdpConfigRecord(
            UUID id,
            UUID tenantId,
            String providerType,
            String metadataUrl,
            String issuer,
            String clientId,
            String x509Cert,
            boolean active,
            Instant createdAt
    ) {
    }

    public record TenantAdminRecord(
            UUID id,
            String name,
            String slug,
            String plan,
            boolean active,
            int memberCount,
            Instant createdAt
    ) {
    }

    public record BasicUserRecord(
            UUID id,
            String email,
            String displayName,
            boolean active,
            String locale,
            Instant createdAt
    ) {
    }

    public record PolicyRecord(
            UUID id,
            UUID tenantId,
            String resource,
            String action,
            String condition,
            String effect,
            int priority,
            boolean active
    ) {
    }

    public record PlanRecord(
            String id,
            String name,
            int maxMembers,
            int maxApps,
            String features
    ) {
    }

    public record SubscriptionRecord(
            UUID id,
            UUID tenantId,
            String planId,
            String status,
            String stripeSubId,
            Instant startedAt,
            Instant expiresAt
    ) {
    }

    public record UserMfaRecord(
            UUID id,
            UUID userId,
            String type,
            String secret,
            boolean active,
            Instant createdAt
    ) {
    }

    // ─── Trusted Devices ────────────────────────────────────

    public Optional<DeviceTrustService.TrustedDevice> findTrustedDevice(UUID userId, UUID deviceId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, user_id, device_id, device_name, user_agent, ip_address, created_at, last_seen_at " +
                     "FROM trusted_devices WHERE user_id = ? AND device_id = ?")) {
            ps.setObject(1, userId);
            ps.setObject(2, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new DeviceTrustService.TrustedDevice(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("device_id", UUID.class), rs.getString("device_name"),
                        rs.getString("user_agent"), rs.getString("ip_address"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant()));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void createTrustedDevice(UUID userId, UUID deviceId, String name, String ua, String ip) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO trusted_devices (user_id, device_id, device_name, user_agent, ip_address) " +
                     "VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id, device_id) DO UPDATE SET last_seen_at = now()")) {
            ps.setObject(1, userId);
            ps.setObject(2, deviceId);
            ps.setString(3, name);
            ps.setString(4, ua);
            ps.setString(5, ip);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void touchTrustedDevice(UUID userId, UUID deviceId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE trusted_devices SET last_seen_at = now() WHERE user_id = ? AND device_id = ?")) {
            ps.setObject(1, userId);
            ps.setObject(2, deviceId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void deleteTrustedDevice(UUID userId, UUID deviceId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM trusted_devices WHERE user_id = ? AND device_id = ?")) {
            ps.setObject(1, userId);
            ps.setObject(2, deviceId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void deleteAllTrustedDevices(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM trusted_devices WHERE user_id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<DeviceTrustService.TrustedDevice> listTrustedDevices(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, user_id, device_id, device_name, user_agent, ip_address, created_at, last_seen_at " +
                     "FROM trusted_devices WHERE user_id = ? ORDER BY last_seen_at DESC")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeviceTrustService.TrustedDevice> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(new DeviceTrustService.TrustedDevice(
                            rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getObject("device_id", UUID.class), rs.getString("device_name"),
                            rs.getString("user_agent"), rs.getString("ip_address"),
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant()));
                }
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void evictOldestDevices(UUID userId, int keepCount) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM trusted_devices WHERE id IN (" +
                     "  SELECT id FROM trusted_devices WHERE user_id = ? " +
                     "  ORDER BY last_seen_at ASC OFFSET ? )")) {
            ps.setObject(1, userId);
            ps.setInt(2, keepCount);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ─── Tenant Security Policies ───────────────────────────

    public record TenantSecurityPolicy(
            UUID tenantId, String newDeviceAction, int riskActionThreshold, int riskBlockThreshold,
            boolean notifyUser, boolean notifyAdmin, boolean autoTrustPasskey, int maxTrustedDevices
    ) {}

    public Optional<TenantSecurityPolicy> findSecurityPolicy(UUID tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM tenant_security_policies WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new TenantSecurityPolicy(
                        rs.getObject("tenant_id", UUID.class), rs.getString("new_device_action"),
                        rs.getInt("risk_action_threshold"), rs.getInt("risk_block_threshold"),
                        rs.getBoolean("notify_user"), rs.getBoolean("notify_admin"),
                        rs.getBoolean("auto_trust_passkey"), rs.getInt("max_trusted_devices")));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void upsertSecurityPolicy(UUID tenantId, String newDeviceAction, int riskActionThreshold,
                                     int riskBlockThreshold, boolean notifyUser, boolean notifyAdmin,
                                     boolean autoTrustPasskey, int maxTrustedDevices, UUID updatedBy) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tenant_security_policies (tenant_id, new_device_action, risk_action_threshold, " +
                     "risk_block_threshold, notify_user, notify_admin, auto_trust_passkey, max_trusted_devices, updated_by) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (tenant_id) DO UPDATE SET new_device_action = EXCLUDED.new_device_action, " +
                     "risk_action_threshold = EXCLUDED.risk_action_threshold, risk_block_threshold = EXCLUDED.risk_block_threshold, " +
                     "notify_user = EXCLUDED.notify_user, notify_admin = EXCLUDED.notify_admin, " +
                     "auto_trust_passkey = EXCLUDED.auto_trust_passkey, max_trusted_devices = EXCLUDED.max_trusted_devices, " +
                     "updated_by = EXCLUDED.updated_by, updated_at = now()")) {
            ps.setObject(1, tenantId);
            ps.setString(2, newDeviceAction);
            ps.setInt(3, riskActionThreshold);
            ps.setInt(4, riskBlockThreshold);
            ps.setBoolean(5, notifyUser);
            ps.setBoolean(6, notifyAdmin);
            ps.setBoolean(7, autoTrustPasskey);
            ps.setInt(8, maxTrustedDevices);
            ps.setObject(9, updatedBy);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ─── GDPR ───────────────────────────────────────────────

    public void softDeleteUser(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void cancelUserDeletion(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void anonymizeAuditLogs(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE audit_logs SET actor_id = NULL, actor_ip = NULL, " +
                     "detail = detail - 'email' - 'ip' - 'user_agent' " +
                     "WHERE actor_id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void hardDeleteUser(UUID userId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Anonymize audit logs
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE audit_logs SET actor_id = NULL, actor_ip = NULL, " +
                        "detail = detail - 'email' - 'ip' - 'user_agent' WHERE actor_id = ?")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
                // 2. Delete outbox_events referencing user's tenant data
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM outbox_events WHERE payload::text LIKE '%' || ? || '%'")) {
                    ps.setString(1, userId.toString());
                    ps.executeUpdate();
                }
                // 3. Nullify memberships
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM memberships WHERE user_id = ?")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
                // 4. Delete auth_flows and transitions for user's sessions
                //    (auth_flow_transitions CASCADE from auth_flows)
                try (PreparedStatement ps = conn.prepareStatement("""
                        DELETE FROM auth_flows WHERE session_id IN (
                            SELECT id FROM sessions WHERE user_id = ?
                        )""")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
                // 5. Delete user (CASCADE: sessions, trusted_devices, user_mfa, passkeys)
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<UUID> findUsersToHardDelete(int graceDays) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE deleted_at IS NOT NULL AND deleted_at < now() - make_interval(days => ?)")) {
            ps.setInt(1, graceDays);
            try (ResultSet rs = ps.executeQuery()) {
                List<UUID> ids = new java.util.ArrayList<>();
                while (rs.next()) ids.add(rs.getObject("id", UUID.class));
                return ids;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ── Paginated admin queries ─────────────────────────────────────────

    private static final Set<String> USERS_SORT_COLUMNS = Set.of("created_at", "email", "display_name");

    public Pagination.PageResponse<BasicUserRecord> findUsersPaginated(Pagination.PageRequest req) {
        String sortCol = USERS_SORT_COLUMNS.contains(req.sort()) ? req.sort() : "created_at";
        String sql = """
                SELECT id, email, display_name, created_at, is_active, locale,
                       COUNT(*) OVER() AS total_count
                FROM users
                WHERE (? IS NULL OR email ILIKE '%' || ? || '%' OR display_name ILIKE '%' || ? || '%')
                ORDER BY """ + sortCol + """
                 DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, req.query());
            ps.setString(2, req.query());
            ps.setString(3, req.query());
            ps.setInt(4, req.size());
            ps.setInt(5, req.offset());
            List<BasicUserRecord> out = new ArrayList<>();
            long total = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total = rs.getLong("total_count");
                    out.add(new BasicUserRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getBoolean("is_active"),
                            rs.getString("locale"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return new Pagination.PageResponse<>(out, total, req.page(), req.size());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Pagination.PageResponse<AdminSessionView> findSessionsPaginated(Pagination.PageRequest req, UUID userIdFilter) {
        String sql = """
                SELECT s.id, s.user_id, u.email, u.display_name, s.ip_address, s.user_agent,
                       s.created_at, s.last_active_at, s.expires_at, s.invalidated_at, s.tenant_id,
                       COUNT(*) OVER() AS total_count
                FROM sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.invalidated_at IS NULL AND s.expires_at > now()
                  AND (?::uuid IS NULL OR s.user_id = ?)
                ORDER BY s.last_active_at DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userIdFilter);
            ps.setObject(2, userIdFilter);
            ps.setInt(3, req.size());
            ps.setInt(4, req.offset());
            List<AdminSessionView> result = new ArrayList<>();
            long total = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total = rs.getLong("total_count");
                    result.add(new AdminSessionView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getString("ip_address"),
                            rs.getString("user_agent"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("last_active_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant(),
                            rs.getObject("tenant_id", UUID.class)
                    ));
                }
            }
            return new Pagination.PageResponse<>(result, total, req.page(), req.size());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Pagination.PageResponse<Map<String, Object>> findAuditLogsPaginated(
            Pagination.PageRequest req, UUID tenantFilter, String fromDate, String toDate, String eventType) {
        String sql = """
                SELECT id, timestamp, event_type, actor_id, tenant_id, target_type, target_id, detail, request_id,
                       COUNT(*) OVER() AS total_count
                FROM audit_logs
                WHERE (?::uuid IS NULL OR tenant_id = ?)
                  AND (? IS NULL OR timestamp >= ?::timestamptz)
                  AND (? IS NULL OR timestamp <= ?::timestamptz)
                  AND (? IS NULL OR event_type = ?)
                ORDER BY timestamp DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantFilter);
            ps.setObject(2, tenantFilter);
            ps.setString(3, fromDate);
            ps.setString(4, fromDate);
            ps.setString(5, toDate);
            ps.setString(6, toDate);
            ps.setString(7, eventType);
            ps.setString(8, eventType);
            ps.setInt(9, req.size());
            ps.setInt(10, req.offset());
            List<Map<String, Object>> out = new ArrayList<>();
            long total = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total = rs.getLong("total_count");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                    row.put("eventType", rs.getString("event_type"));
                    row.put("actorId", rs.getObject("actor_id"));
                    row.put("tenantId", rs.getObject("tenant_id"));
                    row.put("targetType", rs.getString("target_type"));
                    row.put("targetId", rs.getString("target_id"));
                    row.put("detail", rs.getString("detail"));
                    row.put("requestId", rs.getObject("request_id"));
                    out.add(row);
                }
            }
            return new Pagination.PageResponse<>(out, total, req.page(), req.size());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Pagination.PageResponse<MembershipRecord> findMembersPaginated(UUID tenantId, Pagination.PageRequest req) {
        String sql = """
                SELECT id, user_id, tenant_id, role, is_active,
                       COUNT(*) OVER() AS total_count
                FROM memberships
                WHERE tenant_id = ?
                ORDER BY joined_at
                LIMIT ? OFFSET ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setInt(2, req.size());
            ps.setInt(3, req.offset());
            List<MembershipRecord> result = new ArrayList<>();
            long total = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total = rs.getLong("total_count");
                    result.add(new MembershipRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("role"),
                            rs.getBoolean("is_active")
                    ));
                }
            }
            return new Pagination.PageResponse<>(result, total, req.page(), req.size());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Pagination.PageResponse<InvitationRecord> findInvitationsPaginated(
            UUID tenantId, Pagination.PageRequest req, String statusFilter) {
        // statusFilter: "pending" = not expired & not used, "used" = used_count > 0, "expired" = past expires_at
        String statusCondition = switch (statusFilter == null ? "" : statusFilter.toLowerCase()) {
            case "pending" -> " AND used_count = 0 AND expires_at > now()";
            case "used" -> " AND used_count > 0";
            case "expired" -> " AND expires_at <= now()";
            default -> "";
        };
        String sql = """
                SELECT id, tenant_id, code, email, role, max_uses, used_count, created_by, expires_at,
                       COUNT(*) OVER() AS total_count
                FROM invitations
                WHERE tenant_id = ?""" + statusCondition + """

                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setInt(2, req.size());
            ps.setInt(3, req.offset());
            List<InvitationRecord> result = new ArrayList<>();
            long total = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total = rs.getLong("total_count");
                    result.add(readInvitation(rs));
                }
            }
            return new Pagination.PageResponse<>(result, total, req.page(), req.size());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
