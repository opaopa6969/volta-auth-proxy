package org.unlaxer.infra.volta;

import io.javalin.http.Context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AuthService {
    public static final String SESSION_COOKIE = "__volta_session";
    private static final int MAX_CONCURRENT_SESSIONS = 5;

    private final AppConfig config;
    private final SqlStore store;
    private final JwtService jwtService;
    private final SessionStore sessionStore;

    public AuthService(AppConfig config, SqlStore store, JwtService jwtService, SessionStore sessionStore) {
        this.config = config;
        this.store = store;
        this.jwtService = jwtService;
        this.sessionStore = sessionStore;
    }

    public Optional<AuthPrincipal> authenticate(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring("Bearer ".length()).trim();
            if (token.startsWith("volta-service:")) {
                String provided = token.substring("volta-service:".length());
                if (!config.serviceToken().isBlank() && config.serviceToken().equals(provided)) {
                    return Optional.of(new AuthPrincipal(
                            new UUID(0L, 0L),
                            "service@volta.local",
                            "service-token",
                            new UUID(0L, 0L),
                            "service",
                            "service",
                            List.of("SERVICE"),
                            true
                    ));
                }
                return Optional.empty();
            }
            try {
                Map<String, Object> claims = jwtService.verify(token);
                Object isClient = claims.get("volta_client");
                if (Boolean.TRUE.equals(isClient)) {
                    UUID tenantId = UUID.fromString((String) claims.get("volta_tid"));
                    String clientId = (String) claims.getOrDefault("volta_client_id", "m2m-client");
                    @SuppressWarnings("unchecked")
                    List<String> roles = (List<String>) claims.get("volta_roles");
                    return Optional.of(new AuthPrincipal(
                            new UUID(0L, 0L),
                            "m2m@" + clientId,
                            clientId,
                            tenantId,
                            "machine",
                            "machine",
                            roles == null ? List.of() : roles,
                            true
                    ));
                }
                UUID userId = UUID.fromString((String) claims.get("sub"));
                UUID tenantId = UUID.fromString((String) claims.get("volta_tid"));
                UserRecord user = store.findUserById(userId).orElse(null);
                TenantRecord tenant = store.findTenantById(tenantId).orElse(null);
                MembershipRecord membership = store.findMembership(userId, tenantId).orElse(null);
                if (user == null || tenant == null || membership == null || !membership.active()) {
                    return Optional.empty();
                }
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) claims.get("volta_roles");
                return Optional.of(new AuthPrincipal(
                        user.id(),
                        user.email(),
                        user.displayName(),
                        tenant.id(),
                        tenant.name(),
                        tenant.slug(),
                        roles,
                        false
                ));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        String sessionCookie = ctx.cookie(SESSION_COOKIE);
        if (sessionCookie == null || sessionCookie.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID sessionId = UUID.fromString(sessionCookie);
            SessionRecord session = sessionStore.findSession(sessionId).orElse(null);
            if (session == null || !session.isValidAt(Instant.now())) {
                return Optional.empty();
            }
            UserRecord user = store.findUserById(session.userId()).orElse(null);
            TenantRecord tenant = store.findTenantById(session.tenantId()).orElse(null);
            MembershipRecord membership = store.findMembership(session.userId(), session.tenantId()).orElse(null);
            if (user == null || tenant == null) {
                return Optional.empty();
            }
            if (membership != null && membership.active()) {
                sessionStore.touchSession(session.id(), Instant.now().plusSeconds(config.sessionTtlSeconds()));
                return Optional.of(new AuthPrincipal(
                        user.id(),
                        user.email(),
                        user.displayName(),
                        tenant.id(),
                        tenant.name(),
                        tenant.slug(),
                        List.of(membership.role()),
                        false
                ));
            }
            if (session.returnTo() != null && session.returnTo().startsWith("invite:")) {
                String code = session.returnTo().substring("invite:".length());
                InvitationRecord invitation = store.findInvitationByCode(code).orElse(null);
                if (invitation != null
                        && invitation.tenantId().equals(tenant.id())
                        && invitation.isUsableAt(Instant.now())
                        && (invitation.email() == null || invitation.email().equalsIgnoreCase(user.email()))) {
                    sessionStore.touchSession(session.id(), Instant.now().plusSeconds(config.sessionTtlSeconds()));
                    return Optional.of(new AuthPrincipal(
                            user.id(),
                            user.email(),
                            user.displayName(),
                            tenant.id(),
                            tenant.name(),
                            tenant.slug(),
                            List.of("INVITED"),
                            false
                    ));
                }
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public UUID issueSession(AuthPrincipal principal, String returnTo, String ip, String userAgent) {
        int current = sessionStore.countActiveSessions(principal.userId());
        if (current >= MAX_CONCURRENT_SESSIONS) {
            int revokeCount = (current - MAX_CONCURRENT_SESSIONS) + 1;
            sessionStore.revokeOldestActiveSessions(principal.userId(), revokeCount);
        }
        UUID sessionId = SecurityUtils.newUuid();
        String csrfToken = SecurityUtils.randomUrlSafe(32);
        Instant mfaVerifiedAt = store.hasActiveMfa(principal.userId()) ? null : Instant.now();
        sessionStore.createSession(
                sessionId,
                principal.userId(),
                principal.tenantId(),
                returnTo,
                Instant.now().plusSeconds(config.sessionTtlSeconds()),
                mfaVerifiedAt,
                ip,
                userAgent,
                csrfToken
        );
        return sessionId;
    }

    public String issueJwt(AuthPrincipal principal) {
        return jwtService.issueToken(principal);
    }

    public void clearSessionCookie(Context ctx) {
        ctx.removeCookie(SESSION_COOKIE);
    }

    public boolean isMfaPending(Context ctx) {
        String sessionCookie = ctx.cookie(SESSION_COOKIE);
        if (sessionCookie == null || sessionCookie.isBlank()) {
            return false;
        }
        try {
            UUID sessionId = UUID.fromString(sessionCookie);
            SessionRecord session = sessionStore.findSession(sessionId).orElse(null);
            if (session == null) {
                return false;
            }
            return store.hasActiveMfa(session.userId()) && session.mfaVerifiedAt() == null;
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<SessionRecord> currentSession(Context ctx) {
        String sessionCookie = ctx.cookie(SESSION_COOKIE);
        if (sessionCookie == null || sessionCookie.isBlank()) {
            return Optional.empty();
        }
        try {
            return sessionStore.findSession(UUID.fromString(sessionCookie));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void markMfaVerified(UUID sessionId) {
        sessionStore.markSessionMfaVerified(sessionId);
    }

    public SessionStore sessionStore() {
        return sessionStore;
    }
}
