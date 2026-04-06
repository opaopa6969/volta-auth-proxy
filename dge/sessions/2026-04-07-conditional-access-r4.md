# DGE Session: 条件付きアクセス — Round 4 (fraud-alert 統合)

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / 🎩千石 / 😈Red Team / 🏥ハウス / 🎨深澤
> Theme: fraud-alert (既存の不正検知プロダクト) との統合設計
> Round: 4
> New Gaps: 5 (G27-G31)
> Context: fraud-alert はユーザー自身が設計・実装した現職のプロダクト

## Key Decision: 検知は fraud-alert、対応は volta

責務分離:
  fraud-alert = 検知 (50+ Checker → relativeSuspiciousValue 1-5)
  volta = 対応 (ポリシー適用: 通知 / step-up / ブロック)

volta 側でスコア集約ロジックを持つ必要なし。
Custom Functions (TinyExpression DSL) でサイト別チューニング可能。

## Scene 1: API 統合ポイント

```
SM フロー:
  TOKEN_EXCHANGED → USER_RESOLVED → RISK_CHECKED → Branch → COMPLETE / MFA_PENDING / BLOCKED

fraud-alert 呼出:
  1. RiskCheckProcessor → /c/checkOnly (timeout 3s, fail-open)
  2. SessionIssueProcessor 後 → /c/loginSucceed (非同期)
  3. Guard 拒否時 → /c/loginFailed (非同期)
```

ExternalRiskService interface:
```java
interface ExternalRiskService {
    RiskResult check(RiskRequest request, Duration timeout);
    void reportSuccess(LoginContext ctx);
    void reportFailure(LoginContext ctx);
}

record RiskResult(
    int relativeSuspiciousValue,
    int totalSuspiciousValue,
    Map<String, Integer> byKind,
    boolean blocked
) {}
```

→ G9 RESOLVED (再設計)
→ G20 RESOLVED (集約不要、fraud-alert に委譲)
→ G22 RESOLVED (fail-open, timeout 3s)
→ G19 RESOLVED (RISK_CHECKED state + 3分岐)

## Scene 2: Branch 判定ロジック

```
relativeSuspicious 1-3 && !mfaRequired → "no_mfa" → COMPLETE
relativeSuspicious 1-3 && mfaRequired  → "mfa_required" → COMPLETE_MFA_PENDING
relativeSuspicious 4+                  → "mfa_required" → COMPLETE_MFA_PENDING
relativeSuspicious 5                   → "blocked" → TERMINAL_ERROR
```

テナント設定:
  risk_action_threshold: 4 (このレベル以上で step-up)
  risk_block_threshold: 5 (このレベルでブロック)

## Scene 3: volta ローカルチェック vs fraud-alert

```
volta (LocalRiskCheck, ms 単位):
  - trusted_devices で既知デバイス判定
  - 結果: known / unknown
  - 用途: 通知メール、「記憶する」UI

fraud-alert (ExternalRiskService, 100ms-3s):
  - 50+ Checker 総合スコア
  - 結果: relativeSuspiciousValue 1-5
  - 用途: step-up / ブロック判定
```

対応マトリクス:
  新デバイス + risk 1-2 → 通知のみ
  新デバイス + risk 3   → 通知 + (テナント設定次第で step-up)
  新デバイス + risk 4   → 通知 + MFA 再要求
  新デバイス + risk 5   → ブロック
  既知デバイス + risk 1-3 → 何もしない
  既知デバイス + risk 4   → MFA 再要求
  既知デバイス + risk 5   → ブロック

## New Gaps

| # | Gap | Severity |
|---|-----|----------|
| G27 | テナント ↔ siteId マッピング | High |
| G28 | userHash 生成方法 | Medium |
| G29 | BLOCKED 時 UI | Medium |
| G30 | loginSucceed/Failed タイミング | Low |
| G31 | デバイス×リスク マトリクス vs 閾値 | Medium |

## Cumulative: 31 Gaps (11 RESOLVED, 20 Open)
Critical 3/3 RESOLVED / High 2 Open (G25, G27) / Medium 13 Open / Low 5 Open
