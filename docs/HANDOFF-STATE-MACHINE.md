# Handoff: Auth State Machine Implementation

> Date: 2026-04-06
> From: volta-platform DGE session (7 rounds, 84 gaps)
> To: volta-auth-proxy implementation session

## Context

認証フローを State Machine アーキテクチャで再設計する DGE が完了した。7 Round で 84 の設計 Gap を発見・解決し、設計 spec が確定している。

## Key Files (all in this repo)

| File | Content |
|------|---------|
| `docs/AUTH-STATE-MACHINE-SPEC.md` | **読むべき最初のファイル** — 全設計の統合 spec |
| `dge/sessions/2026-04-06-auth-state-machine.md` | DGE Round 1: 2 層構造の発見 |
| `dge/sessions/2026-04-06-auth-state-machine-r2.md` | Round 2: Session SM + Flow SM 詳細 |
| `dge/sessions/2026-04-06-auth-state-machine-r3.md` | Round 3: 実装パターン + Mermaid + 移行戦略 |
| `dge/sessions/2026-04-06-auth-state-machine-r4.md` | Round 4: DB スキーマ + OIDC walkthrough |
| `dge/sessions/2026-04-06-auth-state-machine-r5.md` | Round 5: MFA sequential + Invite 継続 |
| `dge/sessions/2026-04-06-auth-state-machine-r6.md` | Round 6: 並行性 + Step-up + 監視 |
| `dge/sessions/2026-04-06-auth-state-machine-r7.md` | Round 7: DX + テナント + 障害 + 横断チェック |
| `backlog/010-state-machine-phase1.md` | Phase 1 タスク (初手) |
| `backlog/011-state-machine-phase2.md` | Phase 2 タスク |
| `backlog/012-state-machine-phase3.md` | Phase 3 タスク |
| `backlog/013-state-machine-phase4.md` | Phase 4 タスク |

## Architecture Summary

```
┌─────────────────────────────────────────────┐
│  HTTP Layer (Javalin routes)                │
│  OidcFlowRouter / PasskeyFlowRouter / ...   │
├─────────────────────────────────────────────┤
│  FlowEngine (generic, ~120 lines)           │
│  ├── FlowDefinition builder DSL             │
│  ├── MermaidGenerator                       │
│  └── TransitionLogger (audit)               │
├──────────────┬──────────────────────────────┤
│ Session SM   │  Flow SM (per-flow)          │
│ 4 states     │  OIDC (8), Passkey (6),     │
│ + scopes     │  MFA (4), Invite (7)        │
├──────────────┴──────────────────────────────┤
│  Processors / Guards / BranchProcessors     │
│  (1 class per transition arrow)             │
├─────────────────────────────────────────────┤
│  FlowContext (accumulator + @FlowData)      │
│  requires() / produces() → boot validation  │
├─────────────────────────────────────────────┤
│  PostgreSQL                                 │
│  sessions (auth_state) + auth_flows         │
│  + auth_flow_transitions + session_scopes   │
└─────────────────────────────────────────────┘
```

## Next Step: Phase 1 (backlog/010)

**やること:** SM フレームワーク骨格を作る。本番コードに一切触れない。

**具体的な初手:**
1. `src/main/java/com/volta/authproxy/flow/` パッケージを作成
2. `SessionState` enum + 遷移テーブルを定義
3. `OidcFlowState` enum + 遷移テーブルを定義
4. `FlowContext` class を実装
5. `StateProcessor` / `TransitionGuard` / `BranchProcessor` interfaces を定義
6. `FlowDefinition` builder を実装（build() で 8 項目検証）
7. `FlowEngine` skeleton を実装
8. `MermaidGenerator` を実装
9. テスト: FlowTestHarness, SessionTestHarness, Mermaid CI, invalid transition 自動生成

**重要な設計判断（確定済み）:**
- 2 層分離: Session SM（常駐）+ Flow SM（一時的）
- MFA は sequential flow（call/return 不要）— session がバトン
- Step-up は state ではなく scope（session_scopes テーブル）
- 1 遷移 = 1 Processor（原則）
- Per-request save（1 HTTP req = 1 DB save）
- Event 細分化（guard condition は使わない — Session SM のみ）
- Phase 1 は平文 JSONB（暗号化は decorator で後付け）
- Strangler Fig 移行（Big bang rewrite 禁止）

## Existing Code to Know

| File | Lines | Content |
|------|-------|---------|
| `src/main/java/.../Main.java` | ~1800 | 全ルート（これを段階的に分解する対象） |
| `src/main/java/.../SqlStore.java` | ~1900 | 全 DB クエリ |
| `src/main/jte/` | | サーバーレンダリングテンプレート |
| `pom.xml` | | Java deps（Javalin, webauthn4j, etc.） |
