package com.volta.authproxy;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    public void createSession(UUID sessionId, UUID userId, UUID tenantId, String returnTo, Instant expiresAt, String ip, String userAgent, String csrfToken) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO sessions(id, user_id, tenant_id, return_to, expires_at, ip_address, user_agent, csrf_token)
                     VALUES (?, ?, ?, ?, ?, ?::inet, ?, ?)
                     """)) {
            ps.setObject(1, sessionId);
            ps.setObject(2, userId);
            ps.setObject(3, tenantId);
            ps.setString(4, returnTo);
            ps.setTimestamp(5, Timestamp.from(expiresAt));
            ps.setString(6, ip);
            ps.setString(7, userAgent);
            ps.setString(8, csrfToken);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<SessionRecord> findSession(UUID sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, user_id, tenant_id, return_to, created_at, last_active_at, expires_at, invalidated_at, ip_address, user_agent, csrf_token
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
                     SELECT id, user_id, tenant_id, return_to, created_at, last_active_at, expires_at, invalidated_at, ip_address, user_agent, csrf_token
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
}
