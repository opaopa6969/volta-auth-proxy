# DGE Session: 条件付きアクセス — Round 3 (Pluggable 検知 + 残 High)

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / 🎩千石 / 😈Red Team / 🏥ハウス / 🎨深澤
> Theme: 検知レイヤーの pluggable 化 + G2/G4/G5/G15 解決
> Round: 3
> New Gaps: 7 (G20-G26)

## Scene 1: G9 — Pluggable 検知/対応アーキテクチャ

```java
interface RiskSignalProvider {
    String name();
    RiskSignal evaluate(LoginContext ctx);
}

record RiskSignal(String provider, String level, String reason, Map<String, Object> metadata) {}
// level: "none" | "low" | "medium" | "high" | "critical"

interface RiskResponseHandler {
    void handle(AuthPrincipal principal, List<RiskSignal> signals, ResponseContext ctx);
}
```

Built-in providers (Phase 1):
  - NewDeviceSignalProvider (同期)
  - RateLimitSignalProvider (同期)

Phase N providers:
  - ImpossibleTravelProvider (非同期, GeoIP)
  - ThreatIntelProvider (非同期, 外部API)
  - ExternalApiSignalProvider (pluggable, HTTP)

Built-in handlers:
  - NotifyHandler (メール通知)
  - StepUpHandler (MFA 再要求)
  - SessionRevokeHandler (即時 revoke)
  - AuditOnlyHandler (ログのみ)

同期 vs 非同期:
  - 同期: ログインフロー内で即座に評価。ログイン体験を止めない軽量なもの。
  - 非同期: queue に投入 → worker が評価 → critical なら session revoke + 通知。

SM統合: SM の外。Router レベルで session 発行後に RiskEvaluator を呼ぶ。

→ G9 RESOLVED
→ Gap G20: RiskSignal集約ロジック [High]
→ Gap G21: 非同期worker設計 [Medium]
→ Gap G22: 外部API fail-open/closed [High]

## Scene 2: G15 + G4 — テーブル設計 + テナント設定

```sql
CREATE TABLE trusted_devices (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id    UUID NOT NULL,
    device_name  VARCHAR(100),
    user_agent   VARCHAR(500),
    ip_address   VARCHAR(45),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_trusted_devices_user_device ON trusted_devices(user_id, device_id);

CREATE TABLE tenant_security_policies (
    tenant_id           UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    new_device_action   VARCHAR(20) NOT NULL DEFAULT 'notify',  -- 'notify' | 'step_up'
    notify_user         BOOLEAN NOT NULL DEFAULT true,
    notify_admin        BOOLEAN NOT NULL DEFAULT false,
    max_trusted_devices INT NOT NULL DEFAULT 10,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users(id)
);
```

block は YAGNI で除外。notify / step_up の2択。

→ G4 RESOLVED
→ G15 RESOLVED
→ Gap G23: 設定UI配置 [Low]
→ Gap G24: デバイス手動管理API [Medium]

## Scene 3: G2 + G5 — 誤検知UX + IP扱い

step_up フロー:
  1. ログイン成功 → 新デバイス検知
  2. 「セキュリティ確認」画面 → TOTP入力
  3. 成功 → 「このデバイスを記憶しますか？」[はい/今回だけ]
  4. ダッシュボードへ

MFA未設定ユーザー: step_up 適用しない。
前提条件: new_device_action=step_up には tenants.mfa_required=true が必要。

IP: 検知に使わない。通知メール + ログに記録のみ。Impossible Travel は Phase N。

→ G2 RESOLVED
→ G5 RESOLVED
→ Gap G25: step_up/MFA整合性バリデーション [High]
→ Gap G26: 通知メールテンプレート [Medium]

## Cumulative Gap Status (Round 1-3)

| # | Severity | Status | Summary |
|---|----------|--------|---------|
| G1 | Critical | RESOLVED | スコープ → 新デバイス検知に限定 |
| G2 | High | RESOLVED | 誤検知UX → step_up + 記憶オプション |
| G3 | Critical | RESOLVED | 評価タイミング → 2層(ログイン時+ForwardAuth UA) |
| G4 | High | RESOLVED | 条件の主体 → tenant_security_policies |
| G5 | High | RESOLVED | IP → 検知に使わない、記録のみ |
| G6 | Medium | Open | amr更新タイミング |
| G7 | Medium | Open | auth_state/scopes/amr関係文書化 |
| G8 | Critical | RESOLVED | デバイス識別 → Persistent Cookie |
| G9 | High | RESOLVED | 検知/対応分離 → RiskSignalProvider + RiskResponseHandler |
| G10 | Medium | Open | 通知メッセージトーン |
| G11 | Low | Open | amr値セット |
| G12 | Medium | Open | ADR記録 |
| G13 | Medium | Open | UA変更時アクション |
| G14 | Low | Open | ForwardAuth UA比較 |
| G15 | High | RESOLVED | trusted_devicesテーブル |
| G16 | Medium | Open | 「記憶する」UIタイミング |
| G17 | Low | Open | Cookie削除時の頻度制限 |
| G18 | Medium | Open | 通知送信先と頻度 |
| G19 | Medium | Open | SM統合ポイント |
| G20 | High | Open | RiskSignal集約 |
| G21 | Medium | Open | 非同期worker |
| G22 | High | Open | 外部API fail-open/closed |
| G23 | Low | Open | 設定UI配置 |
| G24 | Medium | Open | デバイス手動管理API |
| G25 | High | Open | step_up/MFA整合性 |
| G26 | Medium | Open | 通知メールテンプレート |

Critical: 3/3 RESOLVED / High: 8 (5 RESOLVED, 3 Open) / Medium: 10 Open / Low: 4 Open
Total: 26 Gap (8 RESOLVED, 18 Open)
