# Backlog 014: SM Cutover + Hardening

## Phase: 5
## Priority: Medium
## Status: Open
## Spec: docs/AUTH-STATE-MACHINE-SPEC.md §5, §6, §9, §10, §11

---

## Goal

SM ルートへの完全切替と運用に必要な周辺機能を追加。

## Remaining from Phase 4

以下は Phase 4 (013) で構造は入ったが、運用レベルの実装が残っている項目:

### Cutover (旧→新ルート切替)

1. フロントエンド (volta.js / callback.jte) を新ルート `/auth/oidc/*` に切替
2. Passkey フロントを `/auth/passkey/sm/*` に切替
3. MFA フロントを `/mfa/sm/*` に切替
4. Invite フロントを `/invite/sm/*` に切替
5. 動作確認後、Main.java の旧 OIDC/Passkey/MFA/Invite ルートを削除
6. SM ルートの path を `/sm/` なしの正規パスに変更

### Hardening

7. Step-up Router (/step-up, /step-up/verify) + RequireScope middleware
8. Session cache (Caffeine, 5min TTL, revoke 時即 invalidation)
9. ForwardAuth auth_state 分岐 (AUTHENTICATED_MFA_PENDING → /mfa/* のみ許可)
10. journey_id による認証ジャーニー追跡
11. @Sensitive フィールドの PII redaction (auth_flow_transitions.context_snapshot)
12. Mermaid CI テスト (生成結果 vs docs/diagrams/*.mmd 比較)

### Monitoring (Phase N)

13. SQL-based 監視クエリ (flow success rate, guard failure trend, orphan flows)
14. auth_flows / auth_flow_transitions の GC (scheduled DELETE)
15. Micrometer → Prometheus メトリクス (auth_flow_duration 等)
