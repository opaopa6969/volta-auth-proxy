# Backlog 010: State Machine Framework — Phase 1 (Skeleton + Diagrams)

## Phase: 1
## Priority: High
## Status: Done (2026-04-06, commit fba1626)
## Spec: docs/AUTH-STATE-MACHINE-SPEC.md
## DGE: dge/sessions/2026-04-06-auth-state-machine*.md (7 Rounds, 84 Gaps)

---

## Goal

State Machine のフレームワーク骨格を構築。本番コードに一切触れない。テスト + Mermaid 図の生成のみ。

## Risk: Zero (振る舞い変更なし)

## Deliverables

1. SessionState enum (4 states) + 遷移テーブル
2. OidcFlowState / PasskeyFlowState / MfaFlowState / InviteFlowState enums + 遷移テーブル
3. FlowContext + @FlowData + FlowDataRegistry（起動時 alias 検証）
4. StateProcessor / TransitionGuard / BranchProcessor interfaces
5. FlowDefinition builder DSL + build() 時 8 項目自動検証
6. FlowEngine skeleton（startFlow, resumeAndExecute, auto chain, DAG 検証）
7. FlowStore interface（JSONB, FOR UPDATE locking）
8. MermaidGenerator → docs/diagrams/*.mmd + CI テスト
9. FlowTestHarness + SessionTestHarness
10. Invalid transition 自動テスト生成（遷移テーブル complement）
