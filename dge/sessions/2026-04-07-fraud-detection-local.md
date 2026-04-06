# DGE Session: 不正検知 — volta 内ローカルルール (003-1)

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / 😈Red Team / 🏥ハウス
> Theme: volta 単体でできる不正検知ルール
> Round: 1
> Gaps: 3 (all VOID)

## 前提

fraud-alert 連携済み（ExternalRiskService, /c/checkOnly）。
ここでは「fraud-alert が落ちてる間の volta 単体防御」を議論。

## 結論: YAGNI

既存の3層防御で十分カバーされている:

1. **RateLimiter** — IP ベース 10req/min on /login → 無差別攻撃をブロック
2. **Guard maxRetries** — Passkey 3回、MFA 5回で TERMINAL_ERROR → per-flow ブロック
3. **fraud-alert** — 50+ Checker → relativeSuspicious 5 で BLOCKED → 外部スコアリング

追加の LoginFailureTracker (per-user 失敗カウント) は:
- OIDC → IdP 側がブロックするので volta に失敗が来ない
- Passkey/MFA → Guard maxRetries で既にブロック
- credential stuffing → fraud-alert がカバー

**volta 内に不正検知ロジックを持つ必要はない。**

## Gaps (all VOID)

| # | Gap | Status | Reason |
|---|-----|--------|--------|
| FA-1 | ログイン失敗回数チェック | VOID | 既存3層で十分 |
| FA-2 | 失敗カウントのキー | VOID | FA-1 依存 |
| FA-3 | 一時ロック UX | VOID | FA-1 依存 |

## 将来的にやるなら

- パスワードログイン機能を追加した場合 → LoginFailureTracker が必要になる
- fraud-alert 側で volta 専用の Checker を追加する方が効果的
  (volta の audit_logs を fraud-alert に feed して学習させる等)
