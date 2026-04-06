# DGE Session Round 5: MFA SubFlow・Invite 継続・統合 Mermaid・テスト Harness

- **日付**: 2026-04-06
- **テーマ**: MFA の call/return 再定義、Invite のセッション跨ぎ、統合図、テスト DSL
- **キャラクター**: 今泉、ヤン、リヴァイ、千石、ハウス

---

## 確定事項

### MFA = Sequential Flows (not call/return)
- OIDC Flow 完了(SUCCESS_MFA_PENDING) → Session MFA_PENDING → MFA Flow 新規開始
- Session がバトン（FlowContext は引き継がない）
- ForwardAuth: MFA_PENDING は /mfa/* のみ許可、他は 302 /mfa/challenge

### Invite Flow — ACCOUNT_SWITCHING
```
CONSENT_SHOWN → email mismatch → ACCOUNT_SWITCHING
  → session revoke, flow.session_id = NULL (orphan)
  → 302 /login?return_to=/invite/{code}?flow_ref=HMAC(flow_id:invite_code)
  → bob OIDC login → session_B 作成
  → /invite/{code}?flow_ref=... → flow 復元 → session_B に紐づけ → ACCEPTED → COMPLETE
```

### 1 遷移 1 Processor 原則
- OIDC Flow に TOKEN_EXCHANGED state 追加 (8 states total)
- 各遷移の粒度が均一、障害時の位置特定が精密

### Test Harness
- FlowTestHarness: processor override, auto chain, guard failure
- SessionTestHarness: event-driven state verification
- Invalid transition auto-generation: 遷移テーブルの complement

### return_to Validation
- 全フロー共通 ReturnToValidator
- 外部 URL 拒否、path prefix ホワイトリスト

---

## 全確定アーキテクチャ

```
Session SM (4 states):
  AUTHENTICATING → AUTHENTICATED_MFA_PENDING → FULLY_AUTHENTICATED → EXPIRED/REVOKED
  + session_scopes (step-up)

Flow SMs:
  OIDC:    INIT → REDIRECTED → CALLBACK_RECEIVED → TOKEN_EXCHANGED → USER_RESOLVED → COMPLETE
  Passkey: INIT → CHALLENGE_ISSUED → ASSERTION_RECEIVED → USER_RESOLVED → COMPLETE
  MFA:     CHALLENGE_SHOWN → VERIFIED
  Invite:  CONSENT_SHOWN → ACCOUNT_SWITCHING? → ACCEPTED → COMPLETE

Engine: FlowEngine, FlowStore, MermaidGenerator, TransitionLogger,
        SessionTransitionRouter, ReturnToValidator, FlowDataRegistry

Migration: Phase 1-5 Strangler Fig
```

---

## Gap 一覧 (Round 5)

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 56 | MFA = sequential flows に再定義 | Missing logic | High |
| 57 | ForwardAuth auth_state 別ルーティング | Integration gap | Critical |
| 58 | Sequential flows 間コンテキスト引き継ぎ | Missing logic | Medium |
| 59 | Invite flow_id URL 露出 + HMAC 署名 | Safety gap | High |
| 60 | return_to open redirect 防止 | Safety gap | Critical |
| 61 | 1 遷移 1 processor 原則 (TOKEN_EXCHANGED) | Missing logic | Medium |
| 62 | Mermaid レイアウト方針 (自動のみ) | Ops gap | Low |
| 63 | Invalid transition 自動テスト生成 | Test coverage | High |
| 64 | Session/Flow テスト harness 別クラス | Test coverage | Medium |

---

## 累計サマリー (Round 1-5)

- Critical: 11
- High: 28
- Medium: 22
- Low: 3
- **合計: 64 件**
