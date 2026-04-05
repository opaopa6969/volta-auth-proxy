# SSO Implementation Spec
# DGEセッション設計成果物

**Source:** dge/sessions/2026-04-02-sso-usecase-mapping.md
**Date:** 2026-04-02
**Status:** Ready for implementation

## ギャップ一覧と実装フェーズ

```
Phase 1（小さく始める）:
  G1-c: Google hd パラメータ + コールバック hd クレーム検証
  G1-d: tenant_domains CRUD API
  G2:   SPメタデータエンドポイント

Phase 2（認可レイヤー）:
  G3:   SAML Attribute → Role マッピング
  G4:   IdP-initiated SAML

Phase 3（ガバナンス）:
  G6:   SSO強制
  G5:   動作確認・ログ可視化

後で設計:
  G1-a: テナント発見ロジック（メールドメイン→テナントルーティング）
  G1-b: ログインフローにメールアドレス入力
```

---

## DB スキーマ変更

### Migration V13: SSO Phase 1 基盤

```sql
-- G1-c: OIDC フローにテナント情報を付与
ALTER TABLE oidc_flows
    ADD COLUMN tenant_id UUID REFERENCES tenants(id),
    ADD COLUMN hd_param  VARCHAR(255);  -- Google hd= で送った値を保存（コールバック検証用）

-- G1-d: tenant_domains に verify token 追加（将来の所有権確認用）
ALTER TABLE tenant_domains
    ADD COLUMN verify_token VARCHAR(64),
    ADD COLUMN verified_at  TIMESTAMPTZ;

-- G2: signing_keys に SAML SP 鍵種別を追加
-- kid 規則: 'saml-sp-{tenantId}'
-- 既存 status フィールドを流用、type カラムで用途を区別
ALTER TABLE signing_keys
    ADD COLUMN key_type VARCHAR(20) NOT NULL DEFAULT 'jwt';
-- 既存行は jwt として扱う
UPDATE signing_keys SET key_type = 'jwt' WHERE key_type IS NULL OR key_type = 'jwt';

-- G3/G4: idp_configs に SAML 拡張カラム追加
ALTER TABLE idp_configs
    ADD COLUMN role_mappings      JSONB,
    ADD COLUMN default_role       VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
    ADD COLUMN allow_idp_initiated BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN client_secret      TEXT;  -- OIDC 用（未保存だった場合）

-- G3: issuer の一意性確保（SP メタデータ生成時に必要）
CREATE UNIQUE INDEX IF NOT EXISTS idx_idp_configs_issuer
    ON idp_configs(issuer) WHERE issuer IS NOT NULL;

-- G6: テナント単位の SSO 強制フラグ
ALTER TABLE tenants
    ADD COLUMN sso_enforced BOOLEAN NOT NULL DEFAULT false;

-- G6: セッションにログイン方式を記録
ALTER TABLE sessions
    ADD COLUMN login_method VARCHAR(30);
-- 値の例: 'google', 'github', 'microsoft', 'saml:{configId}'

-- G5: SSO 操作ログテーブル
CREATE TABLE sso_audit_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    idp_config_id UUID REFERENCES idp_configs(id),
    event_type  VARCHAR(40) NOT NULL,  -- 'login_success', 'login_failure', 'test_success', 'test_failure'
    user_id     UUID REFERENCES users(id),
    email       VARCHAR(255),
    error_code  VARCHAR(40),
    error_detail TEXT,
    ip_address  INET,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sso_audit_tenant_created ON sso_audit_logs(tenant_id, created_at DESC);
```

---

## G1-c: Google `hd` パラメータ

### 変更対象
- `OidcService.java` — Google OAuth 開始時に `hd=` パラメータを付与
- `OidcFlowRecord.java` — `tenantId`, `hdParam` フィールド追加
- `SqlStore.java` — `oidc_flows` INSERT/SELECT を更新
- `CallbackHandler.java` — Google コールバック時に `id_token.hd` クレームを検証

### 実装ポイント

```java
// OAuth 開始時（/login?provider=google&tenant_id=xxx）
String tenantId = ctx.queryParam("tenant_id");
if (tenantId != null) {
    String hdDomain = store.getPrimaryDomainForTenant(tenantId);  // tenant_domains から verified=true のもの
    if (hdDomain != null) {
        authUrl = authUrl + "&hd=" + URLEncoder.encode(hdDomain, UTF_8);
        store.saveOidcFlow(state, nonce, codeVerifier, returnTo, tenantId, hdDomain);
    }
}

// コールバック時の検証
OidcFlowRecord flow = store.getOidcFlow(state);
if (flow.hdParam() != null) {
    String claimedHd = idToken.getStringClaimValue("hd");
    if (!flow.hdParam().equalsIgnoreCase(claimedHd)) {
        throw new VoltaException("G1C_HD_MISMATCH",
            "Google account domain does not match tenant domain");
    }
}
```

### エラーコード
| コード | 説明 |
|--------|------|
| `G1C_HD_MISMATCH` | Google アカウントのドメインがテナントのドメインと不一致 |

---

## G1-d: `tenant_domains` CRUD API

### エンドポイント

| Method | Path | 説明 |
|--------|------|------|
| `GET` | `/api/v1/tenants/{tenantId}/domains` | ドメイン一覧 |
| `POST` | `/api/v1/tenants/{tenantId}/domains` | ドメイン追加 |
| `DELETE` | `/api/v1/tenants/{tenantId}/domains/{domain}` | ドメイン削除 |
| `PATCH` | `/api/v1/tenants/{tenantId}/domains/{domain}/verify` | 所有権確認（手動）|

### リクエスト/レスポンス

```jsonc
// POST /api/v1/tenants/{tenantId}/domains
{ "domain": "corp.example.com" }

// 201 Created
{
  "domain": "corp.example.com",
  "verified": false,
  "verifyToken": "volta_verify_abc123",  // DNS TXT レコード確認用（Phase 3 で自動化）
  "addedAt": "2026-04-02T00:00:00Z"
}

// GET /api/v1/tenants/{tenantId}/domains
{
  "domains": [
    { "domain": "corp.example.com", "verified": true, "verifiedAt": "..." },
    { "domain": "partner.example.com", "verified": false }
  ]
}
```

### 制約
- 同じドメインを複数テナントに登録できない（`tenant_domains` PK で保証）
- 削除は `verified: false` のみ許可（verified 済みは PATCH で unverify してから）
- ADMIN ロール必須

---

## G2: SP メタデータエンドポイント

### エンドポイント

```
GET /auth/saml/metadata/{tenantId}
```

レスポンス: `Content-Type: application/xml`

### 生成する XML の構造

```xml
<EntityDescriptor entityID="https://{BASE_URL}/auth/saml/metadata/{tenantId}"
                  xmlns="urn:oasis:names:tc:SAML:2.0:metadata">
  <SPSSODescriptor AuthnRequestsSigned="false"
                   WantAssertionsSigned="true"
                   protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">

    <KeyDescriptor use="signing">
      <ds:KeyInfo>
        <ds:X509Data>
          <ds:X509Certificate>{sp_cert_base64}</ds:X509Certificate>
        </ds:X509Data>
      </ds:KeyInfo>
    </KeyDescriptor>

    <SingleLogoutService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
                         Location="https://{BASE_URL}/auth/saml/slo/{tenantId}"/>

    <AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                              Location="https://{BASE_URL}/auth/saml/callback"
                              index="0" isDefault="true"/>

    <NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</NameIDFormat>
  </SPSSODescriptor>
</EntityDescriptor>
```

### SP 鍵の管理
- `signing_keys` テーブルに `key_type='saml-sp'`, `kid='saml-sp-{tenantId}'` で保存
- テナントに対して初回アクセス時に自動生成（RSA 2048）
- 公開鍵はメタデータに含める、秘密鍵は ACS での署名検証に使用

### 実装クラス
- `SamlMetadataHandler.java` — 新規
- `SamlKeyStore.java` — signing_keys の saml-sp 操作

---

## G3: SAML Attribute → Role マッピング

### `idp_configs.role_mappings` JSONB スキーマ

```jsonc
{
  "attribute": "http://schemas.microsoft.com/ws/2008/06/identity/claims/groups",
  "mappings": [
    { "value": "admin-group-id",  "role": "ADMIN" },
    { "value": "viewer-group-id", "role": "MEMBER" }
  ],
  "defaultRole": "MEMBER"  // mappings に一致しなかった場合
}
```

### マッピング処理

```java
// SamlCallbackHandler.java
String groupValue = samlAssertion.getAttribute(roleMapping.attribute());
String role = roleMapping.mappings().stream()
    .filter(m -> m.value().equals(groupValue))
    .map(RoleMapping::role)
    .findFirst()
    .orElse(idpConfig.defaultRole());

// membership を upsert
store.upsertMembership(userId, tenantId, role);
```

### 管理 API

```
PUT /api/v1/tenants/{tenantId}/idp-configs/{configId}/role-mappings
```

```jsonc
{
  "attribute": "groups",
  "mappings": [
    { "value": "sg-admins", "role": "ADMIN" }
  ],
  "defaultRole": "MEMBER"
}
```

---

## G4: IdP-initiated SAML フロー

### 前提
- IdP（Okta, Azure AD）からダッシュボードのアイコンをクリック
- `SAMLResponse` は POST されるが `InResponseTo` がない
- SP-initiated とは別コードパスで処理する

### 実装ポイント

```java
// SamlCallbackHandler.java — ACS エンドポイント /auth/saml/callback
String inResponseTo = samlResponse.getInResponseTo();

if (inResponseTo == null) {
    // IdP-initiated フロー
    IdpConfig idpConfig = resolveIdpByIssuer(samlResponse.getIssuer());

    if (!idpConfig.allowIdpInitiated()) {
        throw new VoltaException("SAML_IDP_INITIATED_DISABLED",
            "IdP-initiated SSO is not enabled for this configuration");
    }
    // oidc_flows の検索は不要。アサーション内の subject から直接ユーザー特定
    processIdpInitiatedAssertion(ctx, samlResponse, idpConfig);

} else {
    // SP-initiated フロー（従来通り state で flows テーブルを参照）
    OidcFlowRecord flow = store.getSamlFlow(inResponseTo);
    processSpInitiatedAssertion(ctx, samlResponse, flow);
}
```

### `idp_configs.issuer` の一意性が必要な理由
IdP-initiated では `InResponseTo` がないため、`SAMLResponse.Issuer` でどのテナントの IdP かを逆引きする必要がある。そのため `idx_idp_configs_issuer` UNIQUE INDEX が前提。

### エラーコード
| コード | 説明 |
|--------|------|
| `SAML_IDP_INITIATED_DISABLED` | テナントが IdP-initiated を許可していない |
| `SAML_UNKNOWN_ISSUER` | Issuer からテナントを特定できない |

---

## G5: SSO 動作確認・ログ可視化

### テスト実行エンドポイント

```
POST /api/v1/tenants/{tenantId}/idp-configs/{configId}/test
```

SP-initiated SAML の開始 URL（リダイレクト先）を返す。管理者がブラウザでアクセスして実際のフローを確認できる。

```jsonc
// 200 OK
{
  "testUrl": "https://idp.example.com/sso/saml?SAMLRequest=...",
  "expiresAt": "2026-04-02T00:05:00Z",
  "note": "Open this URL in your browser to test the SAML flow"
}
```

テスト完了後（コールバック時）に `sso_audit_logs` に `event_type='test_success'|'test_failure'` で記録。

### ログ参照エンドポイント

```
GET /api/v1/tenants/{tenantId}/sso-logs?limit=50&after={cursor}
```

```jsonc
{
  "logs": [
    {
      "id": "...",
      "eventType": "login_success",
      "email": "user@corp.example.com",
      "idpConfigId": "...",
      "ipAddress": "203.0.113.1",
      "createdAt": "2026-04-02T10:00:00Z"
    },
    {
      "id": "...",
      "eventType": "login_failure",
      "email": "unknown@other.com",
      "errorCode": "G1C_HD_MISMATCH",
      "createdAt": "2026-04-02T09:58:00Z"
    }
  ],
  "nextCursor": "..."
}
```

---

## G6: SSO 強制

### 仕様

- `tenants.sso_enforced = true` のとき、パスワードログイン・メール認証を禁止
- 既存セッション: `sessions.login_method` が `saml:*` 以外の場合、次のリクエストで追い出す
- 猶予期間オプション: 管理 API に `enforcedAt` タイムスタンプを持たせて段階移行できるようにする（Phase 3 で詳細設計）

### 管理 API

```
PATCH /api/v1/tenants/{tenantId}/sso-enforcement
```

```jsonc
{ "ssoEnforced": true }
```

### 認証フローへの影響

```java
// AuthHandler.java — ログイン開始時
Tenant tenant = store.getTenant(tenantId);
if (tenant.ssoEnforced()) {
    String provider = ctx.queryParam("provider");
    // SAML プロバイダー以外はブロック
    if (provider == null || !provider.startsWith("saml:")) {
        throw new VoltaException("SSO_ENFORCED",
            "This tenant requires SSO login");
    }
}

// セッション検証時
if (tenant.ssoEnforced() && session.loginMethod() != null
        && !session.loginMethod().startsWith("saml:")) {
    store.invalidateSession(session.id());
    ctx.redirect("/login?error=sso_required&tenant_id=" + tenantId);
    return;
}
```

### エラーコード
| コード | 説明 |
|--------|------|
| `SSO_ENFORCED` | テナントは SSO ログインのみ許可 |
| `SSO_SESSION_INVALIDATED` | SSO 強制前のセッションが無効化された |

---

## G1-a/G1-b: メールアドレス入力フロー（後で設計）

### なぜ後回しか

- ログインページの UX 変更が大きい（メールアドレス入力 → テナント発見 → IdP リダイレクト）
- G1-c/G1-d/G2 が揃ってから設計する方が具体的に議論できる
- `tenant_domains` テーブルは既に存在（V1）。コード参照がないだけ

### 設計検討ポイント（メモ）

```
POST /auth/sso/discover
{ "email": "user@corp.example.com" }

→ { "tenantId": "...", "tenantName": "Acme Corp", "idpType": "SAML" }
→ フロントがこの結果を見て /login?provider=saml:xxx&tenant_id=yyy にリダイレクト
```

- メールアドレスのドメイン部分を `tenant_domains` で検索
- 複数テナントにマッチした場合の UI（セレクター表示）
- テナント未発見時は通常ログインフォームへ fallback

---

## 実装チェックリスト

### Phase 1
- [ ] `V13__sso_phase1.sql` 作成（Migration）
- [ ] `OidcFlowRecord`: `tenantId`, `hdParam` フィールド追加
- [ ] `SqlStore`: `oidc_flows` INSERT/SELECT 更新
- [ ] `OidcService`: Google OAuth 開始時に `hd=` パラメータ付与
- [ ] `CallbackHandler`: Google コールバック `hd` クレーム検証
- [ ] `TenantDomainsHandler` 新規作成（CRUD API）
- [ ] `SamlMetadataHandler` 新規作成
- [ ] `SamlKeyStore` 新規作成（SP 鍵の自動生成・保存）

### Phase 2
- [ ] `idp_configs.role_mappings`, `default_role`, `allow_idp_initiated` 追加（Migration）
- [ ] `SamlCallbackHandler`: Role マッピング処理
- [ ] `SamlCallbackHandler`: IdP-initiated フロー分岐
- [ ] Role マッピング管理 API

### Phase 3
- [ ] `tenants.sso_enforced`, `sessions.login_method` 追加（Migration）
- [ ] `sso_audit_logs` テーブル作成（Migration）
- [ ] SSO 強制チェック（AuthHandler, セッション検証）
- [ ] SSO テスト実行 API
- [ ] SSO ログ参照 API
