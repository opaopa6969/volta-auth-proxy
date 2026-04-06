package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.SqlStore;
import com.volta.authproxy.flow.*;

import java.util.Set;

import static com.volta.authproxy.flow.oidc.OidcFlowData.*;

/**
 * USER_RESOLVED → RISK_CHECKED: Evaluates risk level.
 * Phase 1: local device check only (fraud-alert integration in Phase 2).
 */
public final class RiskCheckProcessor implements StateProcessor {
    private final SqlStore store;

    public RiskCheckProcessor(SqlStore store) {
        this.store = store;
    }

    @Override public String name() { return "RiskCheckProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(ResolvedUser.class); }
    @Override public Set<Class<?>> produces() { return Set.of(RiskCheckResult.class); }

    @Override
    public void process(FlowContext ctx) {
        ResolvedUser user = ctx.get(ResolvedUser.class);

        // Phase 1: default risk level = 1 (safe)
        // Phase 2: call fraud-alert /c/checkOnly here
        int riskLevel = 1;

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
