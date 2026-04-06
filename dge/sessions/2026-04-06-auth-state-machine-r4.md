# DGE Session Round 4: DB スキーマと End-to-End Walkthrough

- **日付**: 2026-04-06
- **テーマ**: auth_flows テーブル設計、FlowContext の永続化、OIDC login の完全追跡
- **キャラクター**: 今泉、ヤン、リヴァイ、千石、ハウス

---

## 確定事項

### DB スキーマ
```sql
-- sessions: auth_state カラム追加
-- auth_flows: flow 状態保持 (context_encrypted BYTEA, session_id nullable)
-- auth_flow_transitions: 遷移ログ (context_snapshot JSONB, PII redacted)
-- session_scopes: step-up 時限スコープ
```

### FlowContext パイプライン
```
FlowContext → serialize(@FlowData alias) → encrypt(AES-GCM, key_id prefix) → DB BYTEA
DB BYTEA → decrypt(key_id lookup) → deserialize(alias registry) → FlowContext
```

### 設計判断
- Per-request save（1 HTTP req = 1 DB save、途中失敗は前の state のまま）
- SessionIssueProcessor = 特権 Processor（唯一 Session SM に触る）
- Routing Table = SessionState 遷移先の決定に使用
- Auto chain: 起動時 DAG 検証 + runtime maxChainDepth=10
- HMAC key と encryption key は分離
- Phase 1 は平文 JSONB、暗号化は decorator で後付け

### OIDC Walkthrough
```
Req 1: GET /login?start=1&provider=GOOGLE
  → Flow 作成 (session_id=NULL) → OidcInitProcessor → REDIRECTED → 302 Google

Req 2: GET /callback?code=...&state=HMAC(flow_id:csrf)
  → Guard (CSRF+TTL) → OidcCallbackProcessor → UserResolveProcessor → Branch(MFA?)
  → SessionIssueProcessor (1 transaction: session create + flow complete)
  → Set-Cookie → 302 console
```

---

## Gap 一覧 (Round 4)

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 44 | auth_flows orphan flow GC | Ops gap | Medium |
| 45 | 暗号化キー rotation (dual-read, key_id) | Safety gap | High |
| 46 | 遷移ログ PII 保証レベル | Safety gap | High |
| 47 | Step-up trigger (X-Volta-Required-Scope) | Integration gap | High |
| 48 | 未知 @FlowData alias 扱い (flow type 別) | Missing logic | Medium |
| 49 | JSON serialization 規約 | Missing logic | Medium |
| 50 | HMAC key と encryption key 分離 | Safety gap | High |
| 51 | Per-request save に決定 | Missing logic | High |
| 52 | 遷移ログ書き込みタイミング | Ops gap | Medium |
| 53 | Auto chain 循環防止 | Safety gap | High |
| 54 | SessionIssueProcessor 特権明文化 | Missing logic | Medium |
| 55 | Routing Table の最終的な役割 | Missing logic | High |
