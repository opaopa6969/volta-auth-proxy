# Backlog 011: State Machine — Phase 2 (OIDC Flow Migration)

## Phase: 2
## Priority: High
## Status: Done (2026-04-06, commit d518494)
## Spec: docs/AUTH-STATE-MACHINE-SPEC.md §7.1

---

## Goal

OIDC login フローを State Machine に移行。Processor は既存 Main.java のメソッドを呼ぶ薄ラッパー。

## Risk: Low (OIDC の 2 ルートのみ影響)

## Deliverables

1. OidcInitProcessor, OidcCallbackGuard, OidcTokenExchangeProcessor, UserResolveProcessor, MfaCheckBranch, SessionIssueProcessor
2. @FlowData records: OidcRequest, OidcRedirect, OidcCallback, OidcTokens, ResolvedUser, IssuedSession
3. OidcFlowRouter (Javalin): /login, /callback を FlowEngine 経由に
4. auth_flows テーブル作成 (migration)
5. auth_flow_transitions テーブル作成
6. OIDC state parameter に flow_id 埋め込み (HMAC signed)
7. ReturnToValidator (open redirect 防止)
8. FlowTestHarness で OIDC happy path + error path テスト
