# DGE Session Round 3: Flow SM 実装パターンと移行戦略

- **日付**: 2026-04-06
- **テーマ**: Flow SM の processor 実装、Mermaid 自動生成、Main.java からの段階的移行
- **キャラクター**: 今泉、ヤン、リヴァイ、千石、ハウス
- **入力**: Round 1-2 の確定事項

---

## 確定事項

### Transition 3 分類
- **Auto**: サーバー内完結。前の state 完了 → 自動的に次の processor 実行
- **External**: HTTP request がトリガー。Guard で検証 → processor 実行
- **Branch**: ビジネス判断で遷移先を決定。processor が次の state を返す

### Error state 2 分類
- **RETRIABLE_ERROR**: 一時障害 → INIT に戻す（フロー最初からやり直し）
- **TERMINAL_ERROR**: セキュリティ違反 → flow 破棄、/login にリダイレクト

### Mermaid 自動生成
- `MermaidGenerator.generate(FlowDefinition)` → Mermaid stateDiagram-v2
- CI テストで生成結果と docs/diagrams/*.mmd を比較
- 図とコードの乖離を構造的に防止

### Strangler Fig 移行
```
Phase 1: enum + 遷移テーブル + MermaidGenerator + テスト戦略（Main.java 変更なし）
Phase 2: OIDC Flow だけ SM 化（Processor は Main.java のメソッドを呼ぶ薄ラッパー）
Phase 3: Processor にロジック移動（Main.java の該当メソッド削除）
Phase 4: Passkey, MFA, Invite も同様
Phase 5: Main.java の認証ロジック完全移行 → 削除
```

### Version 管理
- 短命 Flow（OIDC, Passkey, MFA; TTL ≤ 5min）: backward-compatible evolution + deploy drain
- 長命 Flow（Invite; TTL 数日）: version-tagged（invitations.flow_version カラム）
- 判定ルール: flow TTL > deploy interval → version-tagged

### テスト 3 層
1. Processor 単体テスト（input → output, coverage 100%）
2. Flow SM 遷移テスト（valid/invalid 遷移列, 全パス coverage）
3. Integration テスト（HTTP → Engine → DB, 主要 happy path）

### PII Redaction
- `@Sensitive` annotation on Context record fields
- JSON serializer が自動 mask
- CI テスト: String フィールドに @Sensitive/@NotSensitive のいずれかが必須

### アーキテクチャ概観
```
┌─────────────────────────────────────────────┐
│  HTTP Layer (Javalin routes)                │
│  AuthFlowRouter: /login, /callback, etc.    │
├─────────────────────────────────────────────┤
│  AuthFlowEngine                             │
│  ├── Routing Table (YAML + Predicate)       │
│  ├── MermaidGenerator                       │
│  └── TransitionLogger (audit)               │
├──────────────┬──────────────────────────────┤
│ Session SM   │  Flow SM (per-flow)          │
│ 4 states     │  OidcFlow (5 states)         │
│ + scopes     │  PasskeyFlow (4 states)      │
│              │  MfaSubFlow (2 states)        │
│              │  InviteFlow (4 states)        │
├──────────────┴──────────────────────────────┤
│  Processors / Guards / BranchProcessors     │
│  (1 class per transition arrow)             │
├─────────────────────────────────────────────┤
│  FlowContext (accumulator + @Sensitive)      │
│  requires() / produces() → boot validation  │
├─────────────────────────────────────────────┤
│  PostgreSQL                                 │
│  sessions (auth_state) + auth_flows         │
│  + auth_flow_transitions (audit)            │
│  + session_scopes (step-up)                 │
└─────────────────────────────────────────────┘
```

---

## Gap 一覧（Round 3）

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 32 | Transition 3 分類の境界基準 | Missing logic | Medium |
| 33 | Error state 2 分類とリカバリーパス | Missing logic | High |
| 34 | OIDC state パラメータへの flow_id 埋め込み方式 | Integration gap | High |
| 35 | SM レベルテスト戦略（3 層 + FlowTestHarness） | Test coverage | High |
| 36 | Runtime state 可視化（admin UI） | Missing logic | Medium |
| 37 | Flow 遷移ログテーブル設計 | Ops gap | High |
| 38 | FlowContext PII redaction（@Sensitive） | Safety gap | High |
| 39 | @Sensitive 漏れ防止 CI テスト | Test coverage | Medium |
| 40 | Flow version 管理方針 | Missing logic | High |
| 41 | Long-lived flow の version 管理 | Missing logic | High |
| 42 | Flow TTL 基準と version-tagged 判定ルール | Ops gap | Medium |
| 43 | State 廃止時のマイグレーション戦略 | Ops gap | High |

---

## 累計 Gap サマリー (Round 1-3)

- Critical: 9 件
- High: 21 件  
- Medium: 13 件
- 合計: 43 件
