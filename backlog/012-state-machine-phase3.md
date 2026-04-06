# Backlog 012: State Machine — Phase 3 (Logic Migration + Passkey/MFA)

## Phase: 3
## Priority: Medium
## Status: Done (2026-04-07, commit 5acf319)
## Spec: docs/AUTH-STATE-MACHINE-SPEC.md §7.2, §7.3

---

## Goal

Processor にロジックを移動（Main.java の該当メソッド削除）。Passkey Flow + MFA Flow も SM 化。

## Risk: Medium

## Deliverables

1. OIDC Processor にロジック移動、Main.java 該当メソッド削除
2. Passkey Flow SM 実装 (PasskeyChallengeProcessor, PasskeyAssertionGuard, PasskeyVerifyProcessor)
3. MFA Flow SM 実装 (MfaChallengeProcessor, MfaCodeGuard, MfaVerifyProcessor)
4. MFA sequential flow pattern: OIDC 完了 → MFA Flow 自動開始
5. ForwardAuth auth_state 分岐 (MFA_PENDING は /mfa/* のみ許可)
6. MFA 用一時 cookie (volta_mfa_flow, HttpOnly, 5min)
