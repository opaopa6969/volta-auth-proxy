# Spec: 条件付きアクセス + デバイストラスト + GDPR

> Generated: 2026-04-07
> Source: DGE Sessions — 7 Rounds, 54 Gaps
>   003-2 条件付きアクセス (5 rounds, 31 gaps)
>   004-3 デバイストラスト (1 round, 8 gaps)
>   004-2 GDPR (1 round, 15 gaps)

---

## 1. Overview

volta-auth-proxy に以下の3機能を段階的に追加する:

1. **条件付きアクセス** — 新デバイスからのログインを検知し、通知 or step-up 認証を要求
2. **デバイストラスト** — 信頼済みデバイスの管理（登録・一覧・失効）
3. **GDPR 対応** — データエクスポート + Right to be Forgotten (削除権)

### Scope（やること / やらないこと）

| やること | やらないこと (ADR) |
|---------|-------------------|
| 新デバイスからのログイン検知 | Cookie 窃取対策 (HttpOnly/SameSite/Secure で十分) |
| fraud-alert API 連携 | volta 内でのスコアリングロジック実装 |
| テナント別の対応設定 (notify/step_up) | 地理制限・時間帯制限 (GeoIP 精度問題 + VPN 無力化) |
| Persistent Cookie によるデバイス識別 | Device Fingerprinting (GDPR リスク + 精度低下) |
| JSON データエクスポート | PDF エクスポート |
| 30日猶予付き論理削除 | 即時物理削除 |

---

## 2. Architecture

### 2.1 検知/対応の責務分離

```
┌──────────────────────────────────────────────────────┐
│ volta-auth-proxy                                     │
│                                                      │
│  ┌──────────────────┐    ┌───────────────────────┐   │
│  │ LocalRiskCheck   │    │ ExternalRiskService   │   │
│  │ (同期, ms単位)    │    │ (同期, timeout 3s)    │   │
│  │                  │    │                       │   │
│  │ NewDeviceCheck   │    │ fraud-alert /c/check  │   │
│  │ (trusted_devices │    │ Only                  │   │
│  │  + cookie)       │    │ → relativeSuspicious  │   │
│  └────────┬─────────┘    │   Value (1-5)         │   │
│           │              └───────────┬───────────┘   │
│           │                          │               │
│           └──────────┬───────────────┘               │
│                      ▼                               │
│           ┌──────────────────────┐                   │
│           │ RiskAndMfaBranch     │                   │
│           │ (SM Branch判定)       │                   │
│           │                      │                   │
│           │ risk 1-3 + !mfa      │                   │
│           │   → COMPLETE         │                   │
│           │ risk 1-3 + mfa       │                   │
│           │   → MFA_PENDING      │                   │
│           │ risk 4+              │                   │
│           │   → MFA_PENDING      │                   │
│           │ risk 5               │                   │
│           │   → BLOCKED          │                   │
│           └──────────────────────┘                   │
│                                                      │
│  Policy: tenant_security_policies                    │
│    risk_action_threshold (default: 4)                │
│    risk_block_threshold (default: 5)                 │
│    new_device_action: notify | step_up               │
└──────────────────────────────────────────────────────┘
         │
         │ HTTP API
         ▼
┌──────────────────────────────────────┐
│ fraud-alert (外部サービス)            │
│                                      │
│ /c/checkOnly   → relativeSuspicious  │
│ /c/loginSucceed → デバイス学習        │
│ /c/loginFailed  → 失敗パターン学習    │
│                                      │
│ 50+ Checker 並列実行                  │
│ Custom Functions (TinyExpression DSL) │
│ Blacklist/Whitelist 強制オーバーライド │
└──────────────────────────────────────┘
```

### 2.2 SM フロー変更

```
既存:
  TOKEN_EXCHANGED → USER_RESOLVED → Branch(mfa) → COMPLETE / MFA_PENDING

変更後:
  TOKEN_EXCHANGED → USER_RESOLVED → RISK_CHECKED → Branch(risk+mfa) → COMPLETE / MFA_PENDING / BLOCKED
```

`RISK_CHECKED` state を追加。`RiskCheckProcessor` が fraud-alert + ローカルチェックを実行。

### 2.3 評価タイミング

| タイミング | チェック内容 | コスト |
|-----------|------------|--------|
| ログイン成功時 | 新デバイス判定 + fraud-alert checkOnly | ms + ~3s (API) |
| ForwardAuth (毎リクエスト) | User-Agent 変更検知のみ | ~0 (文字列比較) |

ForwardAuth の UA 変更検知は warning flag のみ。即ブロックはしない。

---

## 3. DB Schema

### 3.1 trusted_devices (新規)

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

CREATE UNIQUE INDEX idx_trusted_devices_user_device
    ON trusted_devices(user_id, device_id);
CREATE INDEX idx_trusted_devices_user
    ON trusted_devices(user_id);
```

制約: 1ユーザー最大10台。INSERT 前に COUNT → 超過時は LRU (last_seen_at ASC) で削除。

### 3.2 tenant_security_policies (新規)

```sql
CREATE TABLE tenant_security_policies (
    tenant_id              UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    new_device_action      VARCHAR(20) NOT NULL DEFAULT 'notify',
    risk_action_threshold  INT NOT NULL DEFAULT 4,
    risk_block_threshold   INT NOT NULL DEFAULT 5,
    notify_user            BOOLEAN NOT NULL DEFAULT true,
    notify_admin           BOOLEAN NOT NULL DEFAULT false,
    auto_trust_passkey     BOOLEAN NOT NULL DEFAULT true,
    max_trusted_devices    INT NOT NULL DEFAULT 10,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             UUID REFERENCES users(id)
);
```

バリデーション:
- `new_device_action = 'step_up'` → `tenants.mfa_required = true` が前提
- `risk_action_threshold < 5` → `tenants.mfa_required = true` が前提
- `mfa_required` を OFF にする際 → 上記が先に OFF であること

### 3.3 users テーブル変更 (GDPR)

```sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
CREATE INDEX idx_users_deleted ON users(deleted_at) WHERE deleted_at IS NOT NULL;
```

### 3.4 FK 制約変更 (GDPR)

```sql
-- audit_logs: CASCADE → SET NULL
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_user_id_fkey;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_user_id_fkey
    FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL;
```

---

## 4. Cookie

### __volta_device_trust

```
名前:     __volta_device_trust
値:       UUID (デバイスID)
Max-Age:  7776000 (90日)
HttpOnly: true
SameSite: Lax
Secure:   true
Path:     /login
```

スライディング有効期限: ログインのたびに Max-Age をリセット。
Cookie が消された場合: 「新デバイス」扱い（通知が来るだけ、ブロックではない）。

---

## 5. fraud-alert 連携

### 5.1 API 呼び出し

```java
interface ExternalRiskService {
    RiskResult check(RiskRequest request, Duration timeout);
    void reportSuccess(LoginContext ctx);
    void reportFailure(LoginContext ctx);
}

record RiskRequest(
    String siteId,          // volta 全体で 1 siteId
    String userHash,        // SHA256(tenantId + ":" + userId)
    String sessionId,       // SM の flowId
    String ipAddress,
    String userAgent
) {}

record RiskResult(
    int relativeSuspiciousValue,    // 1-5
    int totalSuspiciousValue,
    Map<String, Integer> byKind,
    boolean blocked
) {}
```

### 5.2 Timeout / Failure

- API timeout: 3s
- Failure 時: fail-open (relativeSuspiciousValue = 1 として扱う)
- audit_log に `risk_check_timeout` or `risk_check_error` を記録

### 5.3 siteId / userHash

- siteId: volta 全体で 1 siteId（fraud-alert 側の変更不要）
- userHash: `SHA256(tenantId + ":" + userId)` — テナント間分離
- スコアリング: fraud-alert が算出（共通）
- 閾値: volta のテナント設定 (`risk_action_threshold`) で対応を分ける

### 5.4 フィードバックループ

```
ログイン成功 → Router が /c/loginSucceed を非同期呼出 (fire-and-forget)
ログイン失敗 → Router が /c/loginFailed を非同期呼出
```

OutboxWorker 経由 or 直接非同期 HTTP。Phase 1 は直接非同期。

---

## 6. amr (Authentication Methods References)

### 6.1 JWT に追加

```json
{
  "volta_amr": ["oidc:google", "otp"],
  ...existing claims...
}
```

### 6.2 値セット (RFC 8176 ベース)

| 値 | 認証方法 |
|----|---------|
| `pwd` | パスワード (OIDC 経由の IdP パスワード) |
| `otp` | TOTP (MFA) |
| `hwk` | Hardware Key (Passkey / WebAuthn) |
| `fed` | Federated (OIDC SSO) |
| `fed:google` | Google OIDC (provider 付き) |
| `fed:github` | GitHub OIDC |
| `fed:microsoft` | Microsoft OIDC |
| `fed:saml` | SAML SSO |

### 6.3 更新タイミング

- ログイン時: 初期 amr を設定
- step-up 後: JWT 再発行時に amr を追加（例: `["fed:google"]` → `["fed:google", "otp"]`）
- `/auth/refresh` エンドポイントで最新の JWT を発行

---

## 7. デバイストラスト ライフサイクル

### 7.1 信頼の開始

```
条件: ユーザーが「このデバイスを記憶する」を選択
  or  Passkey ログイン + auto_trust_passkey = true

処理:
  1. UUID を生成 (deviceId)
  2. trusted_devices INSERT (user_id, device_id, device_name, user_agent, ip_address)
  3. __volta_device_trust cookie 発行
```

### 7.2 信頼の継続

```
ログイン時:
  1. cookie から device_id 取得
  2. trusted_devices で (user_id, device_id) を検索
  3. 見つかった → last_seen_at 更新 + cookie Max-Age リフレッシュ
  4. 見つからない → 「新デバイス」扱い
```

### 7.3 信頼の失効

| トリガー | 処理 |
|---------|------|
| Cookie 期限切れ (90日未使用) | 次回ログインで「新デバイス」扱い |
| ユーザーが手動削除 | trusted_devices DELETE |
| 管理者が全削除 | trusted_devices DELETE WHERE user_id = ? |
| パスワード/MFA リセット | trusted_devices DELETE WHERE user_id = ? (自動) |
| DB GC (180日) | last_seen_at < now() - 180d のレコードを定期削除 |

### 7.4 デバイス名の自動生成

```java
static String deviceName(String userAgent) {
    String browser = inferBrowser(userAgent);  // Chrome, Safari, Edge, Firefox, Other
    String os = inferOS(userAgent);            // macOS, Windows, iOS, Android, Linux
    return browser + " on " + os;
}
```

### 7.5 API

```
ユーザー:
  GET    /api/v1/users/me/devices          → 一覧 (IP 部分マスク)
  DELETE /api/v1/users/me/devices/{id}     → 個別削除
  DELETE /api/v1/users/me/devices          → 全削除

管理者:
  GET    /api/v1/tenants/{tid}/users/{uid}/devices  → 閲覧
  DELETE /api/v1/tenants/{tid}/users/{uid}/devices   → 全削除
```

---

## 8. GDPR 対応

### 8.1 個人データインベントリ

| テーブル | PII カラム | エクスポート | 削除時の処理 |
|---------|-----------|------------|-------------|
| users | email, display_name, google_sub | Yes | 物理削除 |
| sessions | ip_address, user_agent | Yes | CASCADE 削除 |
| trusted_devices | user_agent, ip_address | Yes | CASCADE 削除 |
| auth_flows | context (JSONB) | No (一時データ) | CASCADE 削除 |
| auth_flow_transitions | context_snapshot | No | CASCADE 削除 |
| audit_logs | actor_id, actor_ip, detail | Yes (匿名化後) | 匿名化 |
| user_mfa | secret | No (秘密鍵) | 物理削除 |
| passkeys | credential_id, public_key | メタのみ | 物理削除 |
| memberships | user_id | Yes | SET NULL |
| invitations | email | 該当分のみ | 匿名化 |

### 8.2 データエクスポート API

```
POST /api/v1/users/me/data-export
  → 202 { "request_id": "uuid", "status": "processing" }

GET /api/v1/users/me/data-export/{request_id}
  → 200 { "status": "ready", "download_url": "..." }

GET /api/v1/users/me/data-export/{request_id}/download
  → 200 application/json (Content-Disposition: attachment)
```

エクスポート内容: JSON。user, memberships, sessions, trusted_devices, passkeys (メタ), mfa (状態), audit_log。
除外: TOTP secret, passkey 秘密鍵, recovery code hash。
ファイル有効期限: 24時間後に自動削除。
完了通知: メール。

### 8.3 データ削除 (Right to be Forgotten)

```
DELETE /api/v1/users/me
  → 200 { "status": "scheduled", "delete_at": "2026-05-07T..." }
```

**30日猶予:**
1. `users.deleted_at = now()` — 論理削除
2. 全セッション無効化
3. 全信頼済みデバイス失効
4. ログイン不可
5. 30日以内にログイン試行 → 「アカウント削除を撤回しますか？」→ `deleted_at = null`
6. 30日後 → GC ジョブが物理削除を実行

**物理削除の処理:**
```sql
-- 1. audit_logs 匿名化
UPDATE audit_logs SET actor_id = NULL, actor_ip = NULL,
    detail = detail - 'email' - 'ip' - 'user_agent'
WHERE actor_id = ?;

-- 2. invitations 匿名化
UPDATE invitations SET email = 'deleted@redacted' WHERE email = ?;

-- 3. memberships SET NULL
UPDATE memberships SET user_id = NULL WHERE user_id = ?;

-- 4. 物理削除 (CASCADE: sessions, trusted_devices, auth_flows, user_mfa, passkeys)
DELETE FROM users WHERE id = ?;
```

**App 連携 Webhook:**
```
user.deletion_requested  → 猶予期間開始
user.deletion_cancelled  → ユーザーが撤回
user.deletion_completed  → 物理削除実行
```

### 8.4 再登録

同一メールで再登録 → 完全な新規ユーザー。以前のデータは一切復元しない。以前のアカウントの存在を暗示しない。

---

## 9. 通知メール

### 9.1 新デバイスログイン通知

```
件名: [volta] 新しいデバイスからのログイン

{display_name} さん

新しいデバイスからアカウントにログインがありました。

  デバイス: Chrome on macOS
  IP アドレス: 203.0.113.***
  日時: 2026-04-07 14:30 (JST)

心当たりがない場合は、以下のリンクから全てのセッションを無効化できます:
  {base_url}/settings/security

※ このメールに心当たりがある場合は、何もする必要はありません。
```

トーン: 「不審なアクセス」ではなく「新しいデバイスからのログイン」。パニックを避ける。

### 9.2 アカウント削除確認メール

```
件名: [volta] アカウント削除のご確認

{display_name} さん

アカウントの削除リクエストを受け付けました。

  削除予定日: 2026-05-07
  
30日以内にログインすると、削除をキャンセルできます。
削除予定日を過ぎると、全てのデータが完全に削除されます。

この操作に心当たりがない場合は、すぐにログインしてパスワードを変更してください。
```

---

## 10. 段階的実装計画

### Phase 1 (最小)

```
DB:
  - trusted_devices テーブル作成
  - tenant_security_policies テーブル作成
  - users.deleted_at カラム追加
  - audit_logs FK 変更 (ON DELETE SET NULL)

機能:
  - __volta_device_trust cookie 発行/検証
  - 新デバイス検知 → メール通知
  - tenant_security_policies の CRUD API
  - amr を JWT に追加
  - ForwardAuth の UA 変更検知 (warning flag)
  - データエクスポート API (同期、Phase 1)
  - アカウント論理削除 + 30日 GC ジョブ
  - audit_logs 匿名化処理

SM 変更:
  - OidcFlowState に RISK_CHECKED 追加
  - RiskCheckProcessor 作成
  - RiskAndMfaBranch で 3分岐 (no_mfa / mfa_required / blocked)
```

### Phase 2

```
  - fraud-alert API 連携 (ExternalRiskService)
  - テナント設定で step_up 有効化
  - 非同期エクスポート (大規模テナント)
  - App 削除連携 Webhook
  - デバイス管理 UI + API
  - 「記憶する」UI
```

### Phase 3

```
  - fraud-alert loginSucceed/loginFailed フィードバック
  - デバイス × リスクレベル マトリクス設定
  - DPA テンプレート生成
  - テナント別データ保持ポリシー
  - 削除レポート (監査用)
```

---

## 11. 認証状態の3つの表現

```
auth_state (Session SM):
  → ユーザーの認証ライフサイクル
  → AUTHENTICATING → FULLY_AUTHENTICATED → EXPIRED/REVOKED
  → sessions テーブルの auth_state カラム

session_scopes (時限スコープ):
  → 特定操作の一時的な許可
  → step-up 認証で付与、5分で期限切れ
  → session_scopes テーブル

amr (JWT claim):
  → 認証方法の記録（App が参照）
  → ["fed:google", "otp"]
  → JWT に埋め込み、/auth/refresh で更新
```

3つは**別の責務**を持ち、共存する:
- auth_state = 「今どの段階にいるか」
- session_scopes = 「今何ができるか」
- amr = 「どうやって認証したか」

---

## Appendix: DGE Session Files

```
dge/sessions/
  2026-04-07-conditional-access.md      Round 1: スコープ + 評価タイミング (11 gaps)
  2026-04-07-conditional-access-r2.md   Round 2: Critical 解決 (8 gaps)
  2026-04-07-conditional-access-r3.md   Round 3: Pluggable 検知 (7 gaps)
  2026-04-07-conditional-access-r4.md   Round 4: fraud-alert 統合 (5 gaps)
  2026-04-07-conditional-access-r5.md   Round 5: Final (0 gaps, triage)
  2026-04-07-device-trust.md            デバイストラスト (8 gaps)
  2026-04-07-gdpr.md                    GDPR (15 gaps)
```

Total: 7 sessions, 54 gaps, 11 RESOLVED at design level.
