package org.unlaxer.infra.volta.flow.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;
import java.util.UUID;

import static org.unlaxer.infra.volta.flow.oidc.OidcFlowData.*;

/**
 * Javalin routes for OIDC flow via FlowEngine.
 * Replaces the existing /login?start=1, /callback, /auth/callback/complete routes.
 */
public final class OidcFlowRouter {
    private final FlowEngine engine;
    private final FlowDefinition<OidcFlowState> definition;
    private final OidcStateCodec stateCodec;
    private final AppConfig config;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final SqlStore store;
    private final OidcService oidcService;
    private final FraudAlertClient fraudAlert;

    public OidcFlowRouter(FlowEngine engine,
                          FlowDefinition<OidcFlowState> definition,
                          OidcStateCodec stateCodec,
                          AppConfig config,
                          AuditService auditService,
                          ObjectMapper objectMapper,
                          SqlStore store,
                          OidcService oidcService,
                          FraudAlertClient fraudAlert) {
        this.engine = engine;
        this.definition = definition;
        this.stateCodec = stateCodec;
        this.config = config;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.store = store;
        this.oidcService = oidcService;
        this.fraudAlert = fraudAlert;
    }

    public void register(Javalin app) {
        app.get("/login", this::handleLoginPage);
        app.get("/callback", this::handleCallback);
        app.post("/auth/callback/complete", this::handleCallbackPost);
    }

    /**
     * Login page: ?start=1 → redirect to IdP, otherwise show login page.
     */
    private void handleLoginPage(Context ctx) {
        if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
            HttpSupport.jsonError(ctx, 401, "AUTHENTICATION_REQUIRED", "Login required");
            return;
        }
        if ("1".equals(ctx.queryParam("start"))) {
            handleStart(ctx);
            return;
        }
        // Show login page with provider list
        String returnTo = ctx.queryParam("return_to");
        String inviteCode = ctx.queryParam("invite");
        java.util.Map<String, String> inviteContext = null;
        if (inviteCode != null && !inviteCode.isBlank()) {
            var invitation = store.findInvitationByCode(inviteCode).orElse(null);
            if (invitation != null) {
                String tenantName = store.findTenantById(invitation.tenantId())
                        .map(TenantRecord::name).orElse("ワークスペース");
                String inviterName = store.findUserById(invitation.createdBy())
                        .map(UserRecord::displayName).orElse("メンバー");
                inviteContext = java.util.Map.of(
                        "tenantName", tenantName, "inviterName", inviterName, "role", invitation.role());
            }
        }
        String baseParams = (returnTo != null ? "&return_to=" + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8) : "")
                + (inviteCode != null ? "&invite=" + java.net.URLEncoder.encode(inviteCode, java.nio.charset.StandardCharsets.UTF_8) : "");
        boolean isSwitchAccount = returnTo != null && returnTo.startsWith("/invite/");
        ctx.render("auth/login.jte", io.javalin.rendering.template.TemplateUtil.model(
                "title", "ログイン",
                "inviteContext", inviteContext,
                "providers", oidcService.enabledProviders(),
                "baseParams", baseParams,
                "isSwitchAccount", isSwitchAccount
        ));
    }

    /**
     * Start OIDC flow: creates flow instance and redirects to IdP.
     */
    private void handleStart(Context ctx) {
        String returnTo = ctx.queryParam("return_to");
        String inviteCode = ctx.queryParam("invite");
        String provider = ctx.queryParam("provider");

        OidcRequest request = new OidcRequest(
                provider, returnTo, inviteCode,
                HttpSupport.clientIp(ctx), ctx.userAgent()
        );

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> initialData = Map.of((Class) OidcRequest.class, request);

        FlowInstance<OidcFlowState> flow = engine.startFlow(definition, null, initialData);

        // Flow should be in REDIRECTED state with OidcRedirect in context
        OidcRedirect redirect = flow.context().get(OidcRedirect.class);
        ctx.redirect(redirect.authorizationUrl());
    }

    /**
     * OIDC callback (GET): IdP redirects here. Renders callback page or processes directly.
     * GET /auth/oidc/callback?code=xxx&state=yyy
     */
    private void handleCallback(Context ctx) {
        setNoStore(ctx);

        String error = ctx.queryParam("error");
        if (error != null) {
            throw new ApiException(400, "OIDC_FAILED", "OIDC failed: " + error);
        }

        String code = ctx.queryParam("code");
        String state = ctx.queryParam("state");
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "code/state is required");
        }

        if (Boolean.TRUE.equals(ctx.attribute("wantsJson"))) {
            String redirectTo = completeCallback(ctx, code, state);
            ctx.json(Map.of("redirect_to", redirectTo));
        } else {
            // Render callback page that POSTs to complete
            ctx.render("auth/callback.jte", io.javalin.rendering.template.TemplateUtil.model(
                    "title", "ログイン処理中",
                    "code", code,
                    "state", state
            ));
        }
    }

    /**
     * OIDC callback (POST): Frontend sends code/state as JSON.
     * POST /auth/oidc/callback {code, state}
     */
    private void handleCallbackPost(Context ctx) {
        setNoStore(ctx);

        try {
            var body = objectMapper.readTree(ctx.body());
            String code = body.path("code").asText();
            String state = body.path("state").asText();
            if (code.isBlank() || state.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "code/state is required");
            }
            String redirectTo = completeCallback(ctx, code, state);
            ctx.json(Map.of("redirect_to", redirectTo));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "Invalid request body");
        }
    }

    /**
     * Common callback completion: decode state → flow_id, resume flow, set cookie, return redirect.
     */
    private String completeCallback(Context ctx, String code, String state) {
        // Decode HMAC-signed state → flow_id
        String flowId = stateCodec.decode(state)
                .orElseThrow(() -> new ApiException(400, "INVALID_STATE", "Invalid or tampered state parameter"));

        // Resume flow with callback data
        OidcCallback callback = new OidcCallback(code, state);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Class<?>, Object> externalData = Map.of((Class) OidcCallback.class, callback);

        FlowInstance<OidcFlowState> flow = engine.resumeAndExecute(flowId, definition, externalData);

        if (!flow.isCompleted()) {
            throw new ApiException(500, "FLOW_INCOMPLETE", "OIDC flow did not complete");
        }

        if ("TERMINAL_ERROR".equals(flow.exitState()) || "EXPIRED".equals(flow.exitState())) {
            throw new ApiException(400, "OIDC_FAILED", "Authentication failed: " + flow.exitState());
        }

        // Extract result and set session cookie
        IssuedSession session = flow.context().get(IssuedSession.class);
        setSessionCookie(ctx, session.sessionId(), config.sessionTtlSeconds());

        // Audit log
        ResolvedUser user = flow.context().get(ResolvedUser.class);
        OidcTokens tokens = flow.context().get(OidcTokens.class);
        AuthPrincipal principal = new AuthPrincipal(
                user.userId(), user.email(), user.displayName(),
                user.tenantId(), user.tenantName(), user.tenantSlug(),
                user.roles(), false
        );
        auditService.log(ctx, "LOGIN_SUCCESS", principal, "SESSION",
                session.sessionId().toString(), Map.of(
                        "via", tokens.provider().toLowerCase() + "_oidc",
                        "invite", tokens.inviteCode() != null,
                        "flow_id", flow.id()
                ));

        // Device check
        checkDeviceAndNotify(principal, ctx);

        // fraud-alert feedback (fire-and-forget)
        fraudAlert.reportLoginSucceed(user.userId(), user.tenantId(),
                flow.id(), tokens.email(), ctx.userAgent());

        // MFA redirect override
        if (session.mfaPending()) {
            return "/mfa/challenge";
        }
        return session.redirectTo();
    }

    // ─── HTTP helpers (same as Main.java) ────────────────────

    private static void setSessionCookie(Context ctx, UUID sessionId, int sessionTtlSeconds) {
        HttpSupport.setSessionCookie(ctx, AuthService.SESSION_COOKIE, sessionId.toString(), sessionTtlSeconds);
    }

    private static void setNoStore(Context ctx) {
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
        ctx.header("Pragma", "no-cache");
    }

    private void checkDeviceAndNotify(AuthPrincipal principal, Context ctx) {
        // Delegate to existing Main.java logic in Phase 3
        // For now, no-op — device check is not critical for flow correctness
    }
}
