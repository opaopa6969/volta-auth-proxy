package org.unlaxer.infra.volta.flow.mfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;
import java.util.UUID;

import static org.unlaxer.infra.volta.flow.mfa.MfaFlowData.*;
import static io.javalin.rendering.template.TemplateUtil.model;

/**
 * Javalin routes for MFA verification flow via FlowEngine.
 * Sequential pattern: OIDC completes with MFA_PENDING → MFA flow starts automatically.
 */
public final class MfaFlowRouter {
    private static final String MFA_FLOW_COOKIE = "__volta_mfa_flow";

    private final FlowEngine engine;
    private final FlowDefinition<MfaFlowState> definition;
    private final AppConfig config;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public MfaFlowRouter(FlowEngine engine,
                         FlowDefinition<MfaFlowState> definition,
                         AppConfig config,
                         AuthService authService,
                         ObjectMapper objectMapper) {
        this.engine = engine;
        this.definition = definition;
        this.config = config;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    public void register(Javalin app) {
        app.get("/mfa/challenge", this::handleChallenge);
        app.post("/auth/mfa/verify", this::handleVerify);
    }

    /**
     * Start MFA flow or show existing challenge.
     * GET /mfa/sm/challenge?return_to=...
     */
    private void handleChallenge(Context ctx) {
        // Must have a session with pending MFA
        if (!authService.isMfaPending(ctx)) {
            ctx.redirect("/select-tenant");
            return;
        }

        // Always create a fresh MFA flow (clears any stale/expired flow cookie)
        clearMfaFlowCookie(ctx);

        // Start new MFA flow
        SessionRecord session = resolveSession(ctx);
        String returnTo = session.returnTo();

        MfaSessionContext mfaCtx = new MfaSessionContext(
                session.id(), session.userId(), returnTo);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> initialData = Map.of((Class) MfaSessionContext.class, mfaCtx);

        FlowInstance<MfaFlowState> flow = engine.startFlow(definition, session.id().toString(), initialData);

        // Set MFA flow cookie (HttpOnly, short TTL)
        setMfaFlowCookie(ctx, flow.id());
        renderChallengePage(ctx);
    }

    /**
     * Verify MFA code.
     * POST /auth/mfa/sm/verify {code: 123456} or {recovery_code: "XXXX-XXXX"}
     */
    private void handleVerify(Context ctx) {
        String flowId = ctx.cookie(MFA_FLOW_COOKIE);
        if (flowId == null || flowId.isBlank()) {
            // No flow cookie — redirect to challenge to start a new flow
            ctx.json(Map.of("ok", false, "redirect_to", "/mfa/challenge"));
            return;
        }

        try {
            var body = objectMapper.readTree(ctx.body());
            int code = body.path("code").asInt(0);
            String recoveryCode = body.has("recovery_code") ? body.path("recovery_code").asText() : null;

            MfaCodeSubmission submission = new MfaCodeSubmission(code, recoveryCode);

            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<Class<?>, Object> externalData = Map.of((Class) MfaCodeSubmission.class, submission);

            FlowInstance<MfaFlowState> flow = engine.resumeAndExecute(flowId, definition, externalData);

            if (flow.isCompleted() && "VERIFIED".equals(flow.exitState())) {
                MfaVerified verified = flow.context().get(MfaVerified.class);
                clearMfaFlowCookie(ctx);
                ctx.json(Map.of("ok", true, "redirect_to", verified.redirectTo()));
            } else if (flow.isCompleted()) {
                clearMfaFlowCookie(ctx);
                ctx.json(Map.of("ok", false, "error", Map.of("code", "MFA_FAILED", "message", "MFA verification failed. Please try again."),
                        "redirect_to", "/mfa/challenge"));
            } else {
                // Guard rejected but retries remain
                ctx.json(Map.of("ok", false, "error", Map.of("code", "MFA_INVALID_CODE", "message", "Invalid code, please try again")));
            }
        } catch (FlowException fe) {
            // Distinguish error types (tramli 1.16.0)
            clearMfaFlowCookie(ctx);
            String code = fe.code();
            String msg = switch (code) {
                case "FLOW_ALREADY_COMPLETED" -> "MFA session already used. Starting new challenge.";
                case "FLOW_EXPIRED" -> "MFA session expired. Please try again.";
                default -> "MFA session not found. Please try again.";
            };
            ctx.json(Map.of("ok", false, "redirect_to", "/mfa/challenge",
                    "error", Map.of("code", code, "message", msg)));
        } catch (Exception e) {
            ctx.json(Map.of("ok", false, "error", Map.of("code", "BAD_REQUEST", "message", "Invalid request: " + e.getMessage())));
        }
    }

    private void renderChallengePage(Context ctx) {
        ctx.render("auth/mfa-challenge.jte", model(
                "title", "MFA 認証",
                "return_to", ctx.queryParam("return_to")
        ));
    }

    private SessionRecord resolveSession(Context ctx) {
        return authService.currentSession(ctx)
                .orElseThrow(() -> new ApiException(401, "INVALID_SESSION", "No valid session"));
    }

    private static void setMfaFlowCookie(Context ctx, String flowId) {
        HttpSupport.setSessionCookie(ctx, MFA_FLOW_COOKIE, flowId, 300);
    }

    private static void clearMfaFlowCookie(Context ctx) {
        ctx.res().addHeader("Set-Cookie", MFA_FLOW_COOKIE + "=; Path=/; Max-Age=0; HttpOnly");
    }
}
