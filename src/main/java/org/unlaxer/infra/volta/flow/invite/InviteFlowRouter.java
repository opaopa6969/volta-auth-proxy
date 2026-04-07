package org.unlaxer.infra.volta.flow.invite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.unlaxer.infra.volta.flow.invite.InviteFlowData.*;
import static io.javalin.rendering.template.TemplateUtil.model;

/**
 * Javalin routes for Invite flow via FlowEngine.
 */
public final class InviteFlowRouter {
    private final FlowEngine engine;
    private final FlowDefinition<InviteFlowState> definition;
    private final AppConfig config;
    private final AuthService authService;
    private final AuditService auditService;
    private final SqlStore store;
    private final ObjectMapper objectMapper;

    public InviteFlowRouter(FlowEngine engine,
                            FlowDefinition<InviteFlowState> definition,
                            AppConfig config,
                            AuthService authService,
                            AuditService auditService,
                            SqlStore store,
                            ObjectMapper objectMapper) {
        this.engine = engine;
        this.definition = definition;
        this.config = config;
        this.authService = authService;
        this.auditService = auditService;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public void register(Javalin app) {
        app.get("/invite/{code}", this::handleConsent);
        app.post("/invite/{code}/accept", this::handleAccept);
    }

    /**
     * Show invite consent page. Starts a flow if user is authenticated.
     */
    private void handleConsent(Context ctx) {
        String code = ctx.pathParam("code");
        InvitationRecord invitation = store.findInvitationByCode(code)
                .orElseThrow(() -> new ApiException(404, "INVITATION_NOT_FOUND", "招待リンクが見つかりません。"));

        if (!invitation.isUsableAt(Instant.now())) {
            ctx.status(410);
            ctx.render("auth/invite-expired.jte", model("title", "招待期限切れ"));
            return;
        }

        TenantRecord tenant = store.findTenantById(invitation.tenantId())
                .orElseThrow(() -> new ApiException(404, "TENANT_NOT_FOUND", "テナントが見つかりません。"));

        Optional<AuthPrincipal> principalOpt = authService.authenticate(ctx);

        if (principalOpt.isEmpty()) {
            // Not logged in — show login prompt
            ctx.render("auth/invite-consent.jte", model(
                    "title", "招待",
                    "tenantName", tenant.name(),
                    "inviteCode", code,
                    "isLoggedIn", false
            ));
            return;
        }

        AuthPrincipal principal = principalOpt.get();
        SessionRecord session = authService.currentSession(ctx).orElse(null);

        // Start invite flow
        InviteContext inviteCtx = new InviteContext(
                invitation.id(), code,
                tenant.id(), tenant.name(),
                invitation.email(), invitation.role(),
                principal.userId(), principal.email(),
                session != null ? session.id() : null
        );

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> initialData = Map.of((Class) InviteContext.class, inviteCtx);

        FlowInstance<InviteFlowState> flow = engine.startFlow(
                definition, session != null ? session.id().toString() : null, initialData);

        if (flow.isCompleted() && "COMPLETE".equals(flow.exitState())) {
            // Email matched + auto-accepted
            InviteCompleted completed = flow.context().get(InviteCompleted.class);
            setSessionCookie(ctx, completed.sessionId(), config.sessionTtlSeconds());
            ctx.render("auth/invite-done.jte", model(
                    "title", "招待承認完了",
                    "tenantName", tenant.name(),
                    "redirectTo", completed.redirectTo()
            ));
        } else if (flow.currentState() == InviteFlowState.ACCOUNT_SWITCHING) {
            // Email mismatch — show account switch prompt
            ctx.render("auth/invite-consent.jte", model(
                    "title", "招待",
                    "tenantName", tenant.name(),
                    "inviteCode", code,
                    "isLoggedIn", true,
                    "isEmailMismatch", true,
                    "currentEmail", principal.email(),
                    "expectedEmail", invitation.email(),
                    "flowId", flow.id()
            ));
        }
    }

    /**
     * Accept invitation (after account switch or direct).
     */
    private void handleAccept(Context ctx) {
        String code = ctx.pathParam("code");
        String flowId = ctx.formParam("flow_id");
        if (flowId == null || flowId.isBlank()) {
            // Try JSON body
            try {
                var body = objectMapper.readTree(ctx.body());
                flowId = body.path("flow_id").asText();
            } catch (Exception ignored) {}
        }

        if (flowId == null || flowId.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "flow_id is required");
        }

        InviteAcceptSubmission submission = new InviteAcceptSubmission(true);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> externalData = Map.of((Class) InviteAcceptSubmission.class, submission);

        FlowInstance<InviteFlowState> flow = engine.resumeAndExecute(flowId, definition, externalData);

        if (flow.isCompleted() && "COMPLETE".equals(flow.exitState())) {
            InviteCompleted completed = flow.context().get(InviteCompleted.class);
            setSessionCookie(ctx, completed.sessionId(), config.sessionTtlSeconds());

            if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
                ctx.json(Map.of("ok", true, "redirect_to", completed.redirectTo()));
            } else {
                InviteContext inviteCtx = flow.context().get(InviteContext.class);
                ctx.render("auth/invite-done.jte", model(
                        "title", "招待承認完了",
                        "tenantName", inviteCtx.tenantName(),
                        "redirectTo", completed.redirectTo()
                ));
            }
        } else {
            throw new ApiException(400, "INVITE_FAILED", "Invitation acceptance failed");
        }
    }

    private static void setSessionCookie(Context ctx, UUID sessionId, int sessionTtlSeconds) {
        String cookieDomain = System.getenv("COOKIE_DOMAIN");
        String cookie = AuthService.SESSION_COOKIE + "=" + sessionId
                + "; Path=/; Max-Age=" + sessionTtlSeconds
                + "; HttpOnly; SameSite=Lax";
        if (cookieDomain != null && !cookieDomain.isEmpty()) cookie += "; Domain=" + cookieDomain;
        if (ctx.req().isSecure()) cookie += "; Secure";
        ctx.header("Set-Cookie", cookie);
    }
}
