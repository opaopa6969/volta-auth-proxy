package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.FraudAlertClient;
import com.volta.authproxy.SqlStore;
import com.volta.authproxy.flow.*;

import java.util.Set;

import static com.volta.authproxy.flow.oidc.OidcFlowData.*;

/**
 * USER_RESOLVED → RISK_CHECKED: Evaluates risk via fraud-alert API + local checks.
 * Fail-open: if fraud-alert is down or not configured, riskLevel = 1 (safe).
 */
public final class RiskCheckProcessor implements StateProcessor {
    private final SqlStore store;
    private final FraudAlertClient fraudAlert;

    public RiskCheckProcessor(SqlStore store, FraudAlertClient fraudAlert) {
        this.store = store;
        this.fraudAlert = fraudAlert;
    }

    @Override public String name() { return "RiskCheckProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class, OidcRequest.class); }
    @Override public Set<Class<?>> produces() { return Set.of(RiskCheckResult.class); }

    @Override
    public void process(FlowContext ctx) {
        ResolvedUser user = ctx.get(ResolvedUser.class);
        OidcRequest request = ctx.get(OidcRequest.class);

        // Call fraud-alert (fail-open if disabled or timeout)
        var riskResult = fraudAlert.checkOnly(
                user.userId(), user.tenantId(),
                ctx.flowId(),
                request.clientIp(), request.userAgent());

        int riskLevel = riskResult.relativeSuspiciousValue();

        // Load tenant security policy
        var policyOpt = store.findSecurityPolicy(user.tenantId());
        int actionThreshold = policyOpt.map(SqlStore.TenantSecurityPolicy::riskActionThreshold).orElse(4);
        int blockThreshold = policyOpt.map(SqlStore.TenantSecurityPolicy::riskBlockThreshold).orElse(5);
        String deviceAction = policyOpt.map(SqlStore.TenantSecurityPolicy::newDeviceAction).orElse("notify");

        ctx.put(RiskCheckResult.class, new RiskCheckResult(
                riskLevel, actionThreshold, blockThreshold, deviceAction,
                false // isNewDevice — set by router after cookie check
        ));
    }
}
