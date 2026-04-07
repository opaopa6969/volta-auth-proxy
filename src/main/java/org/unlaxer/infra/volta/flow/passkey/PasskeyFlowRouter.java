package org.unlaxer.infra.volta.flow.passkey;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;
import java.util.UUID;

import static org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.*;

/**
 * Javalin routes for Passkey login flow via FlowEngine.
 */
public final class PasskeyFlowRouter {
    private final FlowEngine engine;
    private final FlowDefinition<PasskeyFlowState> definition;
    private final AppConfig config;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PasskeyFlowRouter(FlowEngine engine,
                             FlowDefinition<PasskeyFlowState> definition,
                             AppConfig config,
                             AuditService auditService,
                             ObjectMapper objectMapper) {
        this.engine = engine;
        this.definition = definition;
        this.config = config;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public void register(Javalin app) {
        app.post("/auth/passkey/start", this::handleStart);
        app.post("/auth/passkey/finish", this::handleFinish);
    }

    /**
     * Start passkey login: generate challenge.
     * POST /auth/passkey/sm/start
     */
    private void handleStart(Context ctx) {
        PasskeyRequest request = new PasskeyRequest(
                clientIp(ctx), ctx.userAgent(), ctx.queryParam("return_to"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> initialData = Map.of((Class) PasskeyRequest.class, request);

        FlowInstance<PasskeyFlowState> flow = engine.startFlow(definition, null, initialData);

        PasskeyChallenge challenge = flow.context().get(PasskeyChallenge.class);
        ctx.json(Map.of(
                "flow_id", flow.id(),
                "challenge", challenge.challengeB64(),
                "rpId", challenge.rpId(),
                "timeout", challenge.timeout(),
                "userVerification", "preferred"
        ));
    }

    /**
     * Finish passkey login: verify assertion, issue session.
     * POST /auth/passkey/sm/finish {flow_id, id, response: {clientDataJSON, authenticatorData, signature, userHandle}}
     */
    private void handleFinish(Context ctx) {
        try {
            var body = objectMapper.readTree(ctx.body());
            String flowId = body.path("flow_id").asText();
            if (flowId.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "flow_id is required");
            }

            PasskeyAssertion assertion = new PasskeyAssertion(
                    body.path("id").asText(),
                    body.path("response").path("clientDataJSON").asText(),
                    body.path("response").path("authenticatorData").asText(),
                    body.path("response").path("signature").asText(),
                    body.path("response").has("userHandle")
                            ? body.path("response").path("userHandle").asText() : null
            );

            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<Class<?>, Object> externalData = Map.of((Class) PasskeyAssertion.class, assertion);

            FlowInstance<PasskeyFlowState> flow = engine.resumeAndExecute(flowId, definition, externalData);

            if (!flow.isCompleted() || "TERMINAL_ERROR".equals(flow.exitState())) {
                throw new ApiException(401, "PASSKEY_FAILED", "Passkey authentication failed");
            }

            PasskeyIssuedSession session = flow.context().get(PasskeyIssuedSession.class);
            setSessionCookie(ctx, session.sessionId(), config.sessionTtlSeconds());

            // Audit
            PasskeyVerifiedUser user = flow.context().get(PasskeyVerifiedUser.class);
            AuthPrincipal principal = new AuthPrincipal(
                    user.userId(), user.email(), user.displayName(),
                    user.tenantId(), user.tenantName(), user.tenantSlug(),
                    user.roles(), false
            );
            auditService.log(ctx, "LOGIN_SUCCESS", principal, "SESSION",
                    session.sessionId().toString(), Map.of("via", "passkey", "flow_id", flow.id()));

            ctx.json(Map.of("redirect_to", session.redirectTo()));
        } catch (ApiException | FlowException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "Invalid request: " + e.getMessage());
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

    private static String clientIp(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return ctx.ip();
    }
}
