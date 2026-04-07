package org.unlaxer.infra.volta;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.*;

import static io.javalin.rendering.template.TemplateUtil.model;

public final class AdminRouter {
    private final SqlStore store;
    private final AuthService authService;
    private final SessionStore sessionStore;
    private final PolicyEngine policy;
    private final AuditService auditService;

    public AdminRouter(SqlStore store, AuthService authService, SessionStore sessionStore,
                       PolicyEngine policy, AuditService auditService) {
        this.store = store;
        this.authService = authService;
        this.sessionStore = sessionStore;
        this.policy = policy;
        this.auditService = auditService;
    }

    public void register(Javalin app) {
        app.get("/admin/members", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            policy.enforceMinRole(principal, "ADMIN");
            List<MembershipRecord> members = store.listTenantMembers(principal.tenantId(), 0, 100);
            List<Map<String, String>> memberView = new ArrayList<>();
            for (MembershipRecord m : members) {
                UserRecord u = store.findUserById(m.userId()).orElse(null);
                if (u == null) {
                    continue;
                }
                memberView.add(Map.of(
                        "memberId", m.id().toString(),
                        "userId", m.userId().toString(),
                        "email", u.email(),
                        "displayName", u.displayName() == null ? "-" : u.displayName(),
                        "role", m.role()
                ));
            }
            ctx.render("admin/members.jte", model(
                    "title", "メンバー管理",
                    "tenantId", principal.tenantId().toString(),
                    "members", memberView,
                    "currentUserRole", principal.roles().getFirst(),
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/invitations", ctx -> {
            Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);
            if (principalOpt.isEmpty()) {
                ctx.redirect("/login?return_to=" + java.net.URLEncoder.encode(ctx.fullUrl(), java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            AuthPrincipal principal = principalOpt.get();
            policy.enforceMinRole(principal, "ADMIN");
            List<InvitationRecord> invitations = store.listInvitations(principal.tenantId(), 0, 100);
            List<Map<String, String>> invitationView = invitations.stream().map(i -> Map.of(
                    "id", i.id().toString(),
                    "code", i.code().substring(0, Math.min(8, i.code().length())) + "...",
                    "role", i.role(),
                    "status", i.expiresAt().isBefore(Instant.now()) ? "\u274C 期限切れ" : (i.usedCount() > 0 ? "\u2705 使用済み" : "\u23F3 未使用"),
                    "expiresAt", i.expiresAt().toString(),
                    "createdBy", i.createdBy().toString()
            )).toList();
            ctx.render("admin/invitations.jte", model(
                    "title", "招待管理",
                    "tenantId", principal.tenantId().toString(),
                    "invitations", invitationView,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/webhooks", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            policy.enforceMinRole(principal, "ADMIN");
            List<Map<String, String>> view = store.listWebhooks(principal.tenantId()).stream()
                    .map(w -> Map.of(
                            "id", w.id().toString(),
                            "url", w.endpointUrl(),
                            "events", w.events(),
                            "active", String.valueOf(w.active()),
                            "createdAt", w.createdAt().toString()
                    )).toList();
            ctx.render("admin/webhooks.jte", model(
                    "title", "Webhook 管理",
                    "tenantId", principal.tenantId().toString(),
                    "webhooks", view,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/idp", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            policy.enforceMinRole(principal, "ADMIN");
            List<Map<String, String>> view = store.listIdpConfigs(principal.tenantId()).stream()
                    .map(i -> Map.of(
                            "id", i.id().toString(),
                            "provider", i.providerType(),
                            "issuer", i.issuer() == null ? "" : i.issuer(),
                            "metadataUrl", i.metadataUrl() == null ? "" : i.metadataUrl(),
                            "clientId", i.clientId() == null ? "" : i.clientId(),
                            "x509Cert", i.x509Cert() == null ? "" : i.x509Cert()
                    )).toList();
            ctx.render("admin/idp.jte", model(
                    "title", "IdP 設定",
                    "tenantId", principal.tenantId().toString(),
                    "idps", view,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/tenants", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            requireOwner(principal);
            int offset = HttpSupport.parseOffset(ctx.queryParam("offset"));
            int limit = HttpSupport.parseLimit(ctx.queryParam("limit"));
            List<Map<String, String>> items = store.listTenants(offset, limit).stream().map(t -> Map.of(
                    "id", t.id().toString(),
                    "name", t.name(),
                    "slug", t.slug(),
                    "plan", t.plan(),
                    "active", String.valueOf(t.active()),
                    "memberCount", String.valueOf(t.memberCount()),
                    "createdAt", t.createdAt().toString()
            )).toList();
            ctx.render("admin/tenants.jte", model(
                    "title", "テナント管理",
                    "tenants", items,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.get("/admin/users", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            requireOwner(principal);
            int offset = HttpSupport.parseOffset(ctx.queryParam("offset"));
            int limit = HttpSupport.parseLimit(ctx.queryParam("limit"));
            List<Map<String, String>> items = store.listUsers(offset, limit).stream().map(u -> Map.of(
                    "id", u.id().toString(),
                    "email", u.email(),
                    "displayName", u.displayName() == null ? "" : u.displayName(),
                    "locale", u.locale() == null ? "ja" : u.locale(),
                    "active", String.valueOf(u.active()),
                    "createdAt", u.createdAt().toString()
            )).toList();
            ctx.render("admin/users.jte", model("title", "ユーザー管理", "users", items));
        });

        app.get("/admin/sessions", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            policy.enforceMinRole(principal, "ADMIN");
            int offset = HttpSupport.parseOffset(ctx.queryParam("offset"));
            int limit = HttpSupport.parseLimit(ctx.queryParam("limit"));
            List<SqlStore.AdminSessionView> sessions = store.listAllActiveSessions(offset, limit);
            int totalActive = store.countActiveSessions();
            List<Map<String, String>> sessionView = new ArrayList<>();
            for (SqlStore.AdminSessionView s : sessions) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("sessionId", s.sessionId().toString());
                row.put("userId", s.userId().toString());
                row.put("email", s.email());
                row.put("displayName", s.displayName() == null ? "-" : s.displayName());
                row.put("ip", s.ipAddress() == null ? "-" : s.ipAddress());
                row.put("device", AuthRouter.inferDevice(s.userAgent()));
                row.put("lastActive", s.lastActiveAt().toString());
                row.put("expiresAt", s.expiresAt().toString());
                sessionView.add(row);
            }
            ctx.render("admin/sessions.jte", model(
                    "title", "セッション管理",
                    "sessions", sessionView,
                    "totalActive", totalActive,
                    "csrfToken", AuthRouter.currentCsrfToken(ctx, sessionStore)
            ));
        });

        app.delete("/admin/sessions/{id}", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            policy.enforceMinRole(principal, "ADMIN");
            UUID sessionId = UUID.fromString(ctx.pathParam("id"));
            sessionStore.revokeSession(sessionId);
            auditService.log(ctx, "ADMIN_SESSION_REVOKED", principal, "SESSION", sessionId.toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });

        app.get("/admin/audit", ctx -> {
            AuthPrincipal principal = AuthRouter.requireAuth(ctx, authService);
            policy.enforceMinRole(principal, "ADMIN");
            int offset = HttpSupport.parseOffset(ctx.queryParam("offset"));
            int limit = HttpSupport.parseLimit(ctx.queryParam("limit"));
            List<Map<String, Object>> logs = store.listAuditLogs(principal.tenantId(), offset, limit);
            ctx.render("admin/audit.jte", model("title", "監査ログ", "logs", logs));
        });
    }

    private void requireOwner(AuthPrincipal principal) {
        if (principal.serviceToken()) {
            throw new ApiException(403, "FORBIDDEN", "Service token cannot access admin keys");
        }
        policy.enforceMinRole(principal, "OWNER");
    }
}
