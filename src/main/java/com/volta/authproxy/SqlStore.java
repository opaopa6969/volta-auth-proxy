package com.volta.authproxy;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                     SELECT t.id, t.name, t.slug
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
                            rs.getString("slug")
                    ));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<UserTenantInfo> findTenantInfosByUser(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT t.id, t.name, t.slug, m.role
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
                            rs.getString("role")
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
                                rs.getString("slug")
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
                     SELECT id, name, slug FROM tenants WHERE id = ? AND is_active = true
                     """)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TenantRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug")
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
                     INSERT INTO oidc_flows(state, nonce, code_verifier, return_to, invite_code, expires_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, flow.state());
            ps.setString(2, flow.nonce());
            ps.setString(3, flow.codeVerifier());
            ps.setString(4, flow.returnTo());
            ps.setString(5, flow.inviteCode());
            ps.setTimestamp(6, Timestamp.from(flow.expiresAt()));
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
                        SELECT state, nonce, code_verifier, return_to, invite_code, expires_at
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
                                    rs.getTimestamp("expires_at").toInstant()
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
                        ON CONFLICT(user_id, tenant_id) DO UPDATE SET is_active = true, role = EXCLUDED.role
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

    public record UserTenantInfo(UUID id, String name, String slug, String role) {
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
}
