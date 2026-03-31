# DGE Final — volta-auth-proxy Phase 1 実装設計書
- **Date**: 2026-03-31
- **Rounds**: 4 DGE + 1 auto-iteration (各回 DGE + 素 LLM マージ)
- **Total Gaps identified**: 67 → 全解決
- **Architecture**: Gateway (Javalin) + Postgres + nimbus-jose-jwt

---

## アーキテクチャ

```
Browser
  ↓
Traefik (reverse proxy + rate limit)
  ↓
volta-auth-proxy (Javalin)
  - Google OIDC 直接連携
  - セッション: 署名付き Cookie + Postgres
  - JWT: nimbus-jose-jwt (RS256)
  - JWKS: GET /.well-known/jwks.json
  - テナント解決 / role / 招待 / 管理 API
  ↓
App A / App B / App C (Helidon SE / Javalin)
  - volta-sdk で JWT 検証

docker-compose: Traefik + Gateway + Postgres
Keycloak: 不要 (Phase 1)
oauth2-proxy: 不要
```

---

## DB スキーマ

```sql
-- ユーザー
CREATE TABLE users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email         VARCHAR(255) NOT NULL UNIQUE,
  display_name  VARCHAR(100),
  google_sub    VARCHAR(255) NOT NULL UNIQUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  is_active     BOOLEAN NOT NULL DEFAULT true
);

-- テナント
CREATE TABLE tenants (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name          VARCHAR(100) NOT NULL,
  slug          VARCHAR(50) NOT NULL UNIQUE,
  email_domain  VARCHAR(255),
  auto_join     BOOLEAN NOT NULL DEFAULT false,
  created_by    UUID NOT NULL REFERENCES users(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  plan          VARCHAR(20) NOT NULL DEFAULT 'FREE',
  max_members   INT NOT NULL DEFAULT 50,
  is_active     BOOLEAN NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX idx_tenants_slug ON tenants(slug);
CREATE UNIQUE INDEX idx_tenants_domain ON tenants(email_domain) WHERE email_domain IS NOT NULL;

-- テナントドメイン（複数ドメイン対応）
CREATE TABLE tenant_domains (
  tenant_id   UUID REFERENCES tenants(id),
  domain      VARCHAR(255) NOT NULL,
  verified    BOOLEAN DEFAULT false,
  PRIMARY KEY (tenant_id, domain)
);

-- メンバーシップ（1ユーザー = 複数テナント可）
CREATE TABLE memberships (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id),
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  role        VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
  joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  invited_by  UUID REFERENCES users(id),
  is_active   BOOLEAN NOT NULL DEFAULT true,
  UNIQUE(user_id, tenant_id)
);
CREATE INDEX idx_membership_user ON memberships(user_id);
CREATE INDEX idx_membership_tenant ON memberships(tenant_id);

-- セッション
CREATE TABLE sessions (
  id             UUID PRIMARY KEY,
  user_id        UUID NOT NULL REFERENCES users(id),
  tenant_id      UUID NOT NULL REFERENCES tenants(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at     TIMESTAMPTZ NOT NULL,
  invalidated_at TIMESTAMPTZ,
  ip_address     INET,
  user_agent     TEXT
);
CREATE INDEX idx_sessions_user ON sessions(user_id);

-- 署名鍵
CREATE TABLE signing_keys (
  kid         VARCHAR(64) PRIMARY KEY,
  public_key  TEXT NOT NULL,
  private_key TEXT NOT NULL,       -- AES-256-GCM 暗号化
  status      VARCHAR(16) NOT NULL DEFAULT 'active',  -- active / rotated / revoked
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  rotated_at  TIMESTAMPTZ,
  expires_at  TIMESTAMPTZ
);

-- 招待
CREATE TABLE invitations (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenants(id),
  code         VARCHAR(64) NOT NULL UNIQUE,
  email        VARCHAR(255),
  role         VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
  max_uses     INT NOT NULL DEFAULT 1,
  used_count   INT NOT NULL DEFAULT 0,
  created_by   UUID NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE invitation_usages (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  invitation_id UUID NOT NULL REFERENCES invitations(id),
  used_by       UUID NOT NULL REFERENCES users(id),
  used_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 監査ログ
CREATE TABLE audit_logs (
  id          BIGSERIAL PRIMARY KEY,
  timestamp   TIMESTAMPTZ NOT NULL DEFAULT now(),
  event_type  VARCHAR(50) NOT NULL,
  actor_id    UUID,
  actor_ip    INET,
  tenant_id   UUID,
  target_type VARCHAR(30),
  target_id   VARCHAR(255),
  detail      JSONB,
  request_id  UUID NOT NULL
);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);
CREATE INDEX idx_audit_actor ON audit_logs(actor_id);
CREATE INDEX idx_audit_tenant ON audit_logs(tenant_id);
```

## ER サマリ

```
users ──┐
        ├── memberships ──── tenants ──── tenant_domains
        ├── sessions
        └── invitations (created_by)
             └── invitation_usages

signing_keys (独立)
audit_logs (独立)
```

---

## JWT 仕様

```json
{
  "alg": "RS256",
  "kid": "key-2026-03",
  "typ": "JWT"
}
{
  "iss": "volta-auth",
  "aud": "volta-apps",
  "sub": "user-uuid",
  "exp": 1711900000,
  "iat": 1711899700,
  "jti": "uuid",
  "volta_v": 1,
  "volta_tid": "tenant-uuid",
  "volta_roles": ["ADMIN"],
  "volta_display": "Taro Yamada"
}
```

- **exp**: 5 分
- **alg**: RS256 固定（HS256/none 拒否）
- **claims**: additive only, `volta_v` でバージョン管理
- **PII**: email は載せない（`GET /api/users/{sub}` で取得）

---

## セッション

- Cookie: `__volta_session`, Secure; HttpOnly; SameSite=Lax
- 有効期限: 8 時間（sliding）
- 同時ログイン上限: 5
- 固定攻撃対策: ログイン成功時に ID 再生成
- 即時無効化: `sessions.invalidated_at` 更新（最大 5 分ラグ）

---

## Google OIDC セキュリティ

- state (CSRF, 32 bytes random)
- nonce (リプレイ防止)
- PKCE (S256)
- id_token: iss/aud/exp/nonce/email_verified 全検証
- redirect_uri 厳密一致

---

## テナント解決優先順位

1. Cookie/JWT に tenant_id → それを使う
2. URL サブドメイン → tenant_domains 検索
3. email domain → tenant_domains 検索（フリーメール除外）
4. 該当なし → 招待コード要求 or テナント選択画面

フリーメール除外リスト: gmail.com, outlook.com, yahoo.com, yahoo.co.jp, hotmail.com, icloud.com, protonmail.com

---

## ロール階層

```
OWNER > ADMIN > MEMBER > VIEWER
```

- OWNER: テナント削除、OWNER 譲渡
- ADMIN: メンバー招待/削除、ロール変更
- MEMBER: 通常利用
- VIEWER: 読み取り専用（App 側制御）

---

## エラーコード体系

```json
{ "error": { "code": "AUTH_TOKEN_EXPIRED", "message": "...", "status": 401, "request_id": "uuid" } }
```

| HTTP | code | 状況 |
|------|------|------|
| 401 | AUTHENTICATION_REQUIRED | 未認証 |
| 401 | SESSION_EXPIRED | セッション期限切れ |
| 401 | SESSION_REVOKED | セッション失効済み |
| 403 | FORBIDDEN | 権限不足 |
| 403 | TENANT_ACCESS_DENIED | テナントアクセス権なし |
| 403 | TENANT_SUSPENDED | テナント停止中 |
| 403 | ROLE_INSUFFICIENT | ロール不足 |
| 410 | INVITATION_EXPIRED | 招待期限切れ |
| 410 | INVITATION_EXHAUSTED | 招待使用回数超過 |
| 429 | RATE_LIMITED | レート超過 |

Content negotiation: Accept: application/json → JSON / text/html → リダイレクト

---

## App SDK (volta-sdk)

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .jwksCacheDuration(Duration.ofHours(1))
    .build();

// Javalin middleware
app.before("/api/*", volta.middleware());

// リクエストから
app.get("/api/data", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    String tenantId = user.getTenantId();
    if (!user.hasRole("ADMIN")) throw new ForbiddenResponse();
});
```

---

## 拡張性（Interface 境界）

| 領域 | Phase 1 | Interface | Phase 2+ |
|------|---------|-----------|----------|
| IdP | GoogleOidcProvider | `OidcProvider` | GitHub, Microsoft, SAML |
| セッション | PostgresSessionStore | `SessionStore` | Redis |
| 鍵保管 | PostgresKeyStore | `KeyStore` | Vault, KMS |
| レート制限 | CaffeineRateLimiter | `RateLimiter` | Redis |
| 監査 | PostgresAuditSink | `AuditSink` | Kafka, Elasticsearch |
| 通知 | なし | `NotificationChannel` | メール, Slack |

---

## 環境変数

```env
# Google OIDC
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GATEWAY_BASE_URL=https://auth.example.com

# JWT
JWT_EXPIRY_MINUTES=5
JWT_KEY_ROTATION_DAYS=90
JWT_CLAIMS_VERSION=1
JWT_INCLUDE_DISPLAY_NAME=true

# Session
SESSION_SECRET=                # min 32 bytes, base64
SESSION_MAX_AGE_HOURS=8
MAX_CONCURRENT_SESSIONS=5

# Security
KEY_ENCRYPTION_SECRET=         # AES-256 key, base64
JWKS_EMERGENCY_NO_CACHE=false
FREE_EMAIL_DOMAINS=gmail.com,googlemail.com,outlook.com,hotmail.com,yahoo.com,yahoo.co.jp

# Tenant
ALLOW_SELF_SERVICE_TENANT=false
MAX_TENANTS_PER_USER=10
INVITATION_EXPIRY_HOURS=72

# Database
DATABASE_URL=jdbc:postgresql://postgres:5432/volta
DATABASE_USER=volta
DATABASE_PASSWORD=

# Rate Limit
RATE_LIMIT_LOGIN_PER_MIN=10
RATE_LIMIT_GLOBAL_PER_MIN=100

# Audit
AUDIT_RETENTION_DAYS=365
```

---

## ビルドツール

**Maven**（Gradle ではない）
- 理由: Java バージョンアップ時の互換性が安定
- pom.xml で依存管理

```xml
<!-- 主要依存 -->
<dependencies>
  <!-- Web -->
  <dependency>
    <groupId>io.javalin</groupId>
    <artifactId>javalin</artifactId>
    <version>6.x</version>
  </dependency>
  <dependency>
    <groupId>io.javalin</groupId>
    <artifactId>javalin-rendering</artifactId>
    <version>6.x</version>
  </dependency>

  <!-- Template -->
  <dependency>
    <groupId>gg.jte</groupId>
    <artifactId>jte</artifactId>
    <version>3.x</version>
  </dependency>

  <!-- JWT -->
  <dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>9.x</version>
  </dependency>

  <!-- DB -->
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
  </dependency>
  <dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
  </dependency>

  <!-- Cache -->
  <dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
  </dependency>
</dependencies>
```

---

## Phase 1 で意図的にやらないこと（技術的負債として認識）

1. 水平スケーリング（単一インスタンス前提）
2. SAML / 他 IdP 対応
3. 細粒度パーミッション（RBAC 4 ロールで十分）
4. JWT 暗号化 (JWE)
5. Webhook / イベント通知
6. テナント間 DB レベル分離（App 側 WHERE tenant_id で対応）
7. メール送信（Phase 3）
