# DGE Session Round 7: 開発者体験・テナントポリシー・障害シナリオ

- **日付**: 2026-04-06
- **テーマ**: FlowDefinition DSL、マルチテナント MFA ポリシー、カスケード障害、今泉メソッド横断チェック
- **キャラクター**: 今泉、ヤン、リヴァイ、千石、ハウス

---

## 確定事項

### FlowDefinition Builder DSL
```java
FlowDefinition.builder("oidc", OidcFlowState.class)
    .from(INIT).auto(REDIRECTED).processor(OidcInitProcessor.class)
    .from(REDIRECTED).external(CALLBACK_RECEIVED).guard(OidcCallbackGuard.class)
    .from(CALLBACK_RECEIVED).auto(TOKEN_EXCHANGED).processor(OidcTokenExchangeProcessor.class)
    .from(TOKEN_EXCHANGED).auto(USER_RESOLVED).processor(UserResolveProcessor.class)
    .from(USER_RESOLVED).branch(MfaCheckBranch.class)
        .to(COMPLETE).on("no_mfa").processor(SessionIssueProcessor.class)
        .to(COMPLETE_MFA_PENDING).on("mfa_required").processor(SessionIssueProcessor.class)
    .errorHandler().onAnyError(TERMINAL_ERROR).retriable(RETRIABLE_ERROR).backTo(INIT)
    .build();  // 8 項目自動検証
```

### build() 時の検証項目
1. 全 state が到達可能か
2. initial → terminal の path 存在
3. Auto/Branch の DAG（cycle なし）
4. External transition は各 state に最大 1 つ
5. Branch の全分岐先が定義済み
6. 全 path の requires/produces chain 整合
7. @FlowData alias 重複なし
8. Terminal state からの遷移なし

### テナント MFA ポリシー
- Branch processor が DB から動的に判断
- ポリシー変更は既存 session に遡及しない（次回ログインから）
- admin に "テナント全セッション revoke" ボタン

### DB SPOF 対策
- ForwardAuth に session cache (Caffeine, 5min TTL)
- revoke 時に cache.invalidate() で即時反映
- スケールアウト時は Redis pub/sub で cache invalidation 通知

### SM 正当化条件
- "複数リクエストに跨る state" + "不正遷移を構造的に防ぐ必要" → SM 適切
- 1 リクエスト完結なら関数分割で十分

### 構造的摩擦 = 品質
- state 追加は enum + 遷移 + processor + test の 4 点セット

---

## Gap 一覧 (Round 7)

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 73 | FlowDefinition builder DSL 最終 API | Missing logic | High |
| 74 | 全 path requires/produces 検証アルゴリズム | Missing logic | High |
| 75 | テナント MFA ポリシー変更の遡及問題 | Missing logic | Medium |
| 76 | テナント単位セッション一括 revoke | Missing logic | Medium |
| 77 | Passkey MFA equivalent テナント設定 (将来) | Missing logic | Low |
| 78 | DB SPOF — ForwardAuth session cache | Safety gap | High |
| 79 | Session cache revocation 不整合 | Safety gap | High |
| 80 | HMAC key 喪失影響と復旧 | Ops gap | Medium |
| 81 | 行数増 vs 認知的複雑度減のトレードオフ | Missing logic | Low |
| 82 | SM 正当化条件の明文化 | Missing logic | Low |
| 83 | 新メンバーオンボーディングドキュメント | Ops gap | Medium |
| 84 | "state 追加 4 点セット" ルール | Ops gap | Medium |

---

## 累計 (Round 1-7)

- Critical: 13
- High: 32
- Medium: 29
- Low: 10
- **合計: 84 件**
