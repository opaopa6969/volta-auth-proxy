package org.unlaxer.infra.volta.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * クロステナント flow 参照の防御検証。
 *
 * <p>{@code /api/v1/admin/flows/{flowId}/transitions} は flowId を取るだけの
 * クエリだったため、テナントAの ADMIN がテナントB の flowId を指定して
 * context_snapshot(メール/IP/displayName 等を含む)を読み出せた。
 * 修正後は {@link VizRouter#flowBelongsToTenant} が transitions の
 * context_snapshot に含まれる tenant_id と principal.tenantId() の
 * 一致を検証し、不一致なら 404 を返す。
 *
 * <p>DB 不要: 純粋関数のみ検証する。ルーティング登録の検証は
 * VizRouter が null 依存で構築できないため別途統合テストに委ねる。
 */
class VizRouterTenantScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void flowBelongsToTenant_returnsTrueWhenAllTransitionsMatchTenantId() {
        UUID tenant = UUID.randomUUID();
        ObjectNode ctx1 = MAPPER.createObjectNode();
        ctx1.put("tenant_id", tenant.toString());
        ObjectNode ctx2 = MAPPER.createObjectNode();
        ctx2.put("tenant_id", tenant.toString());
        List<Map<String, Object>> transitions = List.of(
                Map.of("contextSnapshot", ctx1),
                Map.of("contextSnapshot", ctx2));

        assertTrue(VizRouter.flowBelongsToTenant(transitions, tenant),
                "全行の tenant_id が一致するなら true");
    }

    @Test
    void flowBelongsToTenant_returnsFalseWhenAnyTransitionHasDifferentTenantId() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        ObjectNode ctx1 = MAPPER.createObjectNode();
        ctx1.put("tenant_id", tenantA.toString());
        ObjectNode ctx2 = MAPPER.createObjectNode();
        ctx2.put("tenant_id", tenantB.toString()); // 別テナント
        List<Map<String, Object>> transitions = List.of(
                Map.of("contextSnapshot", ctx1),
                Map.of("contextSnapshot", ctx2));

        assertFalse(VizRouter.flowBelongsToTenant(transitions, tenantA),
                "1行でも tenant_id が不一致なら false (クロステナント参照を拒否)");
    }

    @Test
    void flowBelongsToTenant_returnsTrueForEmptyTransitions() {
        // transitions が空(存在しない flowId 等)は true を返す。
        // 呼び出し側で別途 404 判定を想定。空リストを「自分のもの」と
        // 誤認する恐れがあるが、存在しない flowId は transitions が空に
        // なるため、呼び出し側で「空リスト=見つからない」と扱う設計。
        UUID tenant = UUID.randomUUID();
        assertTrue(VizRouter.flowBelongsToTenant(List.of(), tenant),
                "空リストは true (呼び出し側で別途存在確認)");
    }

    @Test
    void flowBelongsToTenant_returnsFalseWhenTenantIdIsNull() {
        // principal.tenantId() == null (service token 以外で異常状態) は
        // 安全側に倒して拒否。null tenant で全テナント参照を許さない。
        ObjectNode ctx = MAPPER.createObjectNode();
        ctx.put("tenant_id", UUID.randomUUID().toString());
        List<Map<String, Object>> transitions = List.of(Map.of("contextSnapshot", ctx));

        assertFalse(VizRouter.flowBelongsToTenant(transitions, null),
                "tenantId=null は false (service token は呼び出し側で別経路)");
    }

    @Test
    void flowBelongsToTenant_treatsMissingTenantIdAsSkip() {
        // context_snapshot に tenant_id が無い古い行は skip される。
        // 全行が tenant_id 欠落なら true を返す(呼び出し側で別途
        // flow 存在確認をすることを前提とした保守的な挙動)。
        // このテストは現在の挙動を固定する回帰テスト。
        UUID tenant = UUID.randomUUID();
        ObjectNode ctxNoTid = MAPPER.createObjectNode();
        ctxNoTid.put("email", "user@example.com"); // tenant_id 無し
        List<Map<String, Object>> transitions = List.of(
                Map.of("contextSnapshot", ctxNoTid));

        assertTrue(VizRouter.flowBelongsToTenant(transitions, tenant),
                "tenant_id 無し行は skip され、全体としては true (呼び出し側で別途存在確認)");
    }

    @Test
    void flowBelongsToTenant_mixedRowsRejectIfAnyMismatch() {
        // 一部行が一致、一部行が不一致 → false (安全側)。
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        ObjectNode ctxMatch = MAPPER.createObjectNode();
        ctxMatch.put("tenant_id", tenantA.toString());
        ObjectNode ctxMismatch = MAPPER.createObjectNode();
        ctxMismatch.put("tenant_id", tenantB.toString());
        List<Map<String, Object>> transitions = List.of(
                Map.of("contextSnapshot", ctxMatch),
                Map.of("contextSnapshot", ctxMismatch));

        assertFalse(VizRouter.flowBelongsToTenant(transitions, tenantA),
                "一部行が不一致なら false (安全側に倒して拒否)");
    }
}

