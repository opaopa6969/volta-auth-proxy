# volta-auth-proxy 全 Phase 実装仕様書

> Source: DGE 106 Gap → 全解決。8 sessions + 3 auto-iterations.
> Build: Maven (pom.xml) — Gradle は使わない
> Date: 2026-03-31

---

## 全体アーキテクチャ

```
Browser
  ↓
Traefik (ForwardAuth middleware)
  ↓ (認証チェック: GET /auth/verify)
volta-auth-proxy (Javalin 6.x + jte 3.x)
  ↓ (X-Volta-* ヘッダで identity context 付与)
App A / App B / App C
  ↓ (CRUD 移譲: /api/v1/* を Proxy に問い合わせ)
volta-auth-proxy (Internal API)

docker-compose: Traefik + volta-auth-proxy + Postgres
外部依存: なし（Keycloak なし、oauth2-proxy なし）
```

### 設計原則
- **制御しやすいは正義** — 外部サーバーへの依存を最小化
- **理解できる地獄を選ぶ** — 認証を外部に任せても設定地獄が待っている。Keycloak の realm.json 500 行と格闘するのも、OIDC フローを自前で書くのも、どちらも地獄。ならスタックトレースが読める方を選ぶ。Auth だけは自分で持つ。何が起きているか分からないシステムにユーザーの認証を預けない
- **密結合上等** — 1 プロセスで完結する。マイクロサービス的な疎結合がもたらすのは「正しいアーキテクチャ」ではなく「設定と通信の複雑性」。認証系はレイテンシに敏感で障害が全体に波及する。ネットワークホップを減らし、デバッグを 1 箇所で完結させる方が正しい。ただし「密結合」はコード設計の話ではない。内部のソフトウェア設計は Interface で疎結合に保つ。密結合の意味は「設定の集約」— 3 箇所に散らばる設定を 1 ファイル（volta-config.yaml）にまとめること。設定が密になることで簡略化される。Zero Config 思想：ゼロにはできないが、限りなく少なく。App 追加 = YAML に 4 行追加。以上
- **設定の集約点であり、トラフィックの集約点ではない** — volta は全設定を volta-config.yaml に集約するが、全トラフィックを中継しない。Traefik の動的設定を volta-config.yaml から自動生成し、Traefik が直接 App にルーティングする。volta は ForwardAuth で認証チェックだけ
- **ForwardAuth パターン** — Proxy はリクエストボディを中継しない。認証チェックだけ
- **App のやることは 2 つだけ** — ヘッダを読む or API を叩く
- **Phase ごとの最小構成** — 今必要なものだけ作り、Interface で拡張点を残す。ただし App 固有のロジックを proxy に入れない規律を守る

---

## Phase 一覧

```
Phase 1: Core（Google OIDC + テナント + 招待）     ← 今回実装
Phase 2: Scale（M2M + 複数 IdP + Redis + Webhook）
Phase 3: Enterprise（SAML + メール + Admin UI 拡張）
Phase 4: Platform（SCIM + Policy Engine + Billing）
```

---

# ═══════════════════════════════════════
# Phase 1: Core
# ═══════════════════════════════════════

## 技術スタック

```
言語:       Java 21+
ビルド:     Maven (pom.xml)
Web:        Javalin 6.x
テンプレート: jte 3.x (型安全)
JWT:        nimbus-jose-jwt 9.x
DB:         Postgres 16 + Flyway
Pool:       HikariCP
Cache:      Caffeine
CSS:        単一ファイル volta.css (モバイルファースト)
JS:         volta-sdk-js (~150行、vanilla JS)
```

## Maven 依存 (pom.xml)

```xml
<project>
  <groupId>com.volta</groupId>
  <artifactId>volta-auth-proxy</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.source>${java.version}</maven.compiler.source>
    <maven.compiler.target>${java.version}</maven.compiler.target>
    <javalin.version>6.4.0</javalin.version>
    <jte.version>3.1.15</jte.version>
  </properties>

  <dependencies>
    <!-- Web -->
    <dependency>
      <groupId>io.javalin</groupId>
      <artifactId>javalin</artifactId>
      <version>${javalin.version}</version>
    </dependency>
    <dependency>
      <groupId>io.javalin</groupId>
      <artifactId>javalin-rendering</artifactId>
      <version>${javalin.version}</version>
    </dependency>

    <!-- Template -->
    <dependency>
      <groupId>gg.jte</groupId>
      <artifactId>jte</artifactId>
      <version>${jte.version}</version>
    </dependency>

    <!-- JWT -->
    <dependency>
      <groupId>com.nimbusds</groupId>
      <artifactId>nimbus-jose-jwt</artifactId>
      <version>9.47</version>
    </dependency>

    <!-- DB -->
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <version>42.7.4</version>
    </dependency>
    <dependency>
      <groupId>com.zaxxer</groupId>
      <artifactId>HikariCP</artifactId>
      <version>6.2.1</version>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
      <version>10.21.0</version>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
      <version>10.21.0</version>
    </dependency>

    <!-- Cache -->
    <dependency>
      <groupId>com.github.ben-manes.caffeine</groupId>
      <artifactId>caffeine</artifactId>
      <version>3.1.8</version>
    </dependency>

    <!-- Logging -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>2.0.16</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- jte precompile -->
      <plugin>
        <groupId>gg.jte</groupId>
        <artifactId>jte-maven-plugin</artifactId>
        <version>${jte.version}</version>
      </plugin>
      <!-- fat jar -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.volta.App</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

## ディレクトリ構造

```
volta-auth-proxy/
  pom.xml
  Dockerfile
  docker-compose.yml
  volta-config.yaml                          ← App 登録
  src/main/
    java/com/volta/
      App.java                               ← エントリポイント
      config/
        AppConfig.java                       ← 環境変数読み込み
        VoltaConfig.java                     ← volta-config.yaml 読み込み
      auth/
        GoogleOidcProvider.java              ← Google OIDC 直接連携
        OidcProvider.java                    ← Interface（拡張用）
        SessionManager.java                  ← セッション管理
        JwtIssuer.java                       ← JWT 発行 + JWKS
        KeyStore.java                        ← Interface（拡張用）
        PostgresKeyStore.java                ← 署名鍵管理
      tenant/
        TenantResolver.java                  ← テナント解決
        MembershipService.java               ← メンバーシップ CRUD
        InvitationService.java               ← 招待 CRUD
      handler/
        AuthHandler.java                     ← /login, /callback, /logout, /auth/*
        ForwardAuthHandler.java              ← GET /auth/verify
        InviteHandler.java                   ← /invite/{code}
        ApiHandler.java                      ← /api/v1/*
        AdminHandler.java                    ← /admin/*
        PageHandler.java                     ← HTML ページ（テナント選択、セッション管理）
        DevHandler.java                      ← /dev/token (DEV_MODE)
      middleware/
        ContentNegotiationFilter.java        ← Accept ヘッダ判定
        RateLimiter.java                     ← Caffeine ベース
        AuditLogger.java                     ← 監査ログ
      model/
        User.java, Tenant.java, Membership.java,
        Session.java, Invitation.java, SigningKey.java, AuditLog.java
      db/
        Database.java                        ← HikariCP + Flyway
        UserRepository.java
        TenantRepository.java
        MembershipRepository.java
        SessionRepository.java
        InvitationRepository.java
        SigningKeyRepository.java
        AuditLogRepository.java
      sdk/
        VoltaAuth.java                       ← App 向け SDK（別モジュール候補）
        VoltaUser.java
    jte/
      layout/base.jte
      auth/login.jte
      auth/tenant-select.jte
      invite/landing.jte
      invite/consent.jte
      invite/expired.jte
      settings/sessions.jte
      admin/members.jte
      admin/invitations.jte
      error/error.jte
    resources/
      public/
        css/volta.css
        js/volta.js                          ← volta-sdk-js
      db/migration/
        V1__create_users.sql
        V2__create_tenants.sql
        V3__create_tenant_domains.sql
        V4__create_memberships.sql
        V5__create_sessions.sql
        V6__create_signing_keys.sql
        V7__create_invitations.sql
        V8__create_invitation_usages.sql
        V9__create_audit_logs.sql
```

## DB スキーマ (9 テーブル)

```sql
-- V1__create_users.sql
CREATE TABLE users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email         VARCHAR(255) NOT NULL UNIQUE,
  display_name  VARCHAR(100),
  google_sub    VARCHAR(255) NOT NULL UNIQUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  is_active     BOOLEAN NOT NULL DEFAULT true
);

-- V2__create_tenants.sql
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

-- V3__create_tenant_domains.sql
CREATE TABLE tenant_domains (
  tenant_id   UUID REFERENCES tenants(id),
  domain      VARCHAR(255) NOT NULL,
  verified    BOOLEAN DEFAULT false,
  PRIMARY KEY (tenant_id, domain)
);

-- V4__create_memberships.sql
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

-- V5__create_sessions.sql
CREATE TABLE sessions (
  id             UUID PRIMARY KEY,
  user_id        UUID NOT NULL REFERENCES users(id),
  tenant_id      UUID NOT NULL REFERENCES tenants(id),
  return_to      VARCHAR(2048),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at     TIMESTAMPTZ NOT NULL,
  invalidated_at TIMESTAMPTZ,
  ip_address     INET,
  user_agent     TEXT
);
CREATE INDEX idx_sessions_user ON sessions(user_id);

-- V6__create_signing_keys.sql
CREATE TABLE signing_keys (
  kid         VARCHAR(64) PRIMARY KEY,
  public_key  TEXT NOT NULL,
  private_key TEXT NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  rotated_at  TIMESTAMPTZ,
  expires_at  TIMESTAMPTZ
);

-- V7__create_invitations.sql
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

-- V8__create_invitation_usages.sql
CREATE TABLE invitation_usages (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  invitation_id UUID NOT NULL REFERENCES invitations(id),
  used_by       UUID NOT NULL REFERENCES users(id),
  used_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V9__create_audit_logs.sql
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

## JWT 仕様

```
アルゴリズム: RS256 (RSA 2048bit)
有効期限: 5 分
鍵管理: signing_keys テーブル (active/rotated/revoked)
JWKS: GET /.well-known/jwks.json (active + rotated の公開鍵)

Claims:
  iss: "volta-auth"
  aud: ["volta-apps"]          ← 配列（Phase 2 で App 単位追加）
  sub: user UUID
  exp: 発行時 + 5分
  iat: 発行時
  jti: UUID
  volta_v: 1                   ← schema version
  volta_tid: tenant UUID
  volta_tname: tenant name
  volta_tslug: tenant slug
  volta_roles: ["ADMIN"]       ← 配列
  volta_display: display name  ← optional (JWT_INCLUDE_DISPLAY_NAME)

セキュリティ:
  - alg: RS256 固定。HS256/none 絶対拒否
  - kid 必須
  - JWKS キャッシュ: 1h TTL + stale-while-revalidate (最大 24h)
```

## セッション仕様

```
Cookie: __volta_session; Secure; HttpOnly; SameSite=Lax; Path=/
値: session UUID (HMAC-SHA256 署名付き)
有効期限: 8 時間 (sliding)
同時ログイン上限: 5
固定攻撃対策: ログイン成功時に ID 再生成
即時無効化: sessions.invalidated_at 更新 → 最大 5 分ラグ
```

## Google OIDC 直接連携

```
認可リクエスト:
  GET https://accounts.google.com/o/oauth2/v2/auth
    ?client_id={GOOGLE_CLIENT_ID}
    &redirect_uri={GATEWAY_BASE_URL}/callback
    &response_type=code
    &scope=openid email profile
    &state={32 bytes random, base64url}
    &nonce={32 bytes random, base64url}
    &code_challenge={SHA256(code_verifier), base64url}
    &code_challenge_method=S256
    &access_type=online
    &prompt=select_account

コールバック:
  GET /callback?code={code}&state={state}
  → state 検証 → code → token 交換 → id_token 検証
  → iss/aud/exp/nonce/email_verified 全検証
  → ユーザー検索/作成 → セッション作成 → return_to へリダイレクト
```

## ForwardAuth (Proxy → App)

```
Traefik 設定:
  middlewares:
    volta-auth:
      forwardAuth:
        address: http://volta-auth-proxy:7070/auth/verify
        authResponseHeaders:
          - X-Volta-User-Id
          - X-Volta-Email
          - X-Volta-Tenant-Id
          - X-Volta-Tenant-Slug
          - X-Volta-Roles
          - X-Volta-Display-Name
          - X-Volta-JWT

GET /auth/verify:
  1. Cookie → session 検証
  2. user + membership + tenant を取得（Caffeine キャッシュ 30秒）
  3. JWT 発行
  4. 200 + X-Volta-* ヘッダ（認証 OK）
  5. or 401（認証 NG）
  パフォーマンス: ~10ms (キャッシュ有 ~1ms)
```

## Internal API (App → Proxy)

```
認証: Authorization: Bearer <user-jwt>
  or: Authorization: Bearer volta-service:<VOLTA_SERVICE_TOKEN>

tenantId チェック: パスの {tid} == JWT の volta_tid を必ず強制

GET    /api/v1/users/me
GET    /api/v1/users/{id}
GET    /api/v1/tenants/{tid}
PATCH  /api/v1/tenants/{tid}                    (OWNER のみ)
GET    /api/v1/tenants/{tid}/members             (ページネーション: offset/limit, default 20, max 100)
GET    /api/v1/tenants/{tid}/members/{uid}
PATCH  /api/v1/tenants/{tid}/members/{uid}       (role 変更, ADMIN+)
DELETE /api/v1/tenants/{tid}/members/{uid}       (ADMIN+)
POST   /api/v1/tenants/{tid}/invitations         (ADMIN+)
GET    /api/v1/tenants/{tid}/invitations         (ADMIN+)
DELETE /api/v1/tenants/{tid}/invitations/{iid}   (ADMIN+)
POST   /api/v1/tenants/{tid}/transfer-ownership  (OWNER のみ)

レスポンス:
  成功: { "data": {...}, "meta": { "request_id": "uuid" } }
  一覧: { "data": [...], "meta": { "total": N, "limit": 20, "offset": 0, "request_id": "uuid" } }
  エラー: { "error": { "code": "...", "message": "...", "status": N, "request_id": "uuid" } }
```

## 画面一覧 (jte テンプレート)

| URL | 画面 | 説明 |
|-----|------|------|
| GET /login | ログイン | Google ボタン + テナントコンテキスト |
| GET /callback | コールバック | server-side 処理 → リダイレクト |
| GET /select-tenant | テナント選択 | 複数テナント所属時 |
| GET /invite/{code} | 招待着地 | テナント名・招待者・ロール |
| POST /invite/{code}/accept | 招待同意 | [参加する] 確認 |
| GET /settings/sessions | セッション管理 | 一覧・終了・全終了 |
| GET /admin/members | メンバー管理 | 一覧・ロール変更 |
| GET /admin/invitations | 招待管理 | コピー・QR・状態 |
| (共通) | エラー | 人間向けメッセージ + 次のアクション |

## Content Negotiation

```
Accept: application/json を含む → 必ず JSON（302 禁止）
Accept: text/html or なし → HTML or リダイレクト
X-Requested-With: XMLHttpRequest → JSON
Authorization: Bearer → JSON
```

## エラーコード

| HTTP | code | ユーザー向けメッセージ | 次のアクション |
|------|------|----------------------|---------------|
| 401 | AUTHENTICATION_REQUIRED | ログインが必要です | [ログイン] |
| 401 | SESSION_EXPIRED | セッションの有効期限が切れました | [再ログイン] |
| 401 | SESSION_REVOKED | セッションが無効化されました | [再ログイン] + 管理者連絡先 |
| 403 | FORBIDDEN | アクセスする権限がありません | [戻る] |
| 403 | TENANT_ACCESS_DENIED | ワークスペースへのアクセス権がありません | [招待リクエスト] / [切替] |
| 403 | TENANT_SUSPENDED | ワークスペースは一時停止中です | 管理者連絡先 |
| 403 | ROLE_INSUFFICIENT | この操作の権限がありません | [戻る] + 管理者連絡先 |
| 404 | NOT_FOUND | 見つかりませんでした | [戻る] |
| 409 | CONFLICT | 既に存在します | - |
| 410 | INVITATION_EXPIRED | 招待リンクの有効期限が切れました | [再招待リクエスト] |
| 410 | INVITATION_EXHAUSTED | この招待リンクは使用済みです | [ログイン] |
| 429 | RATE_LIMITED | しばらくお待ちください（{N}秒後） | カウントダウン |

## ロール階層

```
OWNER > ADMIN > MEMBER > VIEWER
OWNER: テナント削除、OWNER 譲渡、全 ADMIN 権限
ADMIN: メンバー招待/削除、ロール変更 (ADMIN 以下)、テナント設定変更
MEMBER: 通常利用
VIEWER: 読み取り専用 (App 側制御)
```

## テナント解決優先順位

```
1. Cookie/JWT の tenant_id → 使う
2. URL サブドメイン → tenant_domains 検索
3. email domain → tenant_domains 検索（フリーメール除外）
4. 該当なし → 招待コード要求 or テナント選択画面

フリーメール除外: gmail.com, outlook.com, yahoo.com, yahoo.co.jp, hotmail.com, icloud.com, protonmail.com
```

## App 登録

```yaml
# volta-config.yaml
apps:
  - id: app-wiki
    name: "Wiki"
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    name: "Admin Console"
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

## volta-sdk-js (~150 行)

```javascript
class VoltaClient {
  constructor({ gatewayUrl }) { this.gatewayUrl = gatewayUrl; this._refreshing = null; }

  setupInterceptor(axios) {
    axios.interceptors.response.use(res => res, async err => {
      if (err.response?.status !== 401) throw err;
      if (err.config._retried) {
        window.location = `${this.gatewayUrl}/login?return_to=${encodeURIComponent(window.location.href)}`;
        return;
      }
      await this._refresh();
      err.config._retried = true;
      return axios(err.config);
    });
  }

  async _refresh() {
    if (this._refreshing) return this._refreshing;
    this._refreshing = fetch(`${this.gatewayUrl}/auth/refresh`, {
      method: 'POST', credentials: 'include'
    }).then(r => { if (!r.ok) throw new Error(); return r.json(); })
      .finally(() => { this._refreshing = null; });
    return this._refreshing;
  }

  async switchTenant(tenantId) {
    await fetch(`${this.gatewayUrl}/auth/switch-tenant`, {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantId })
    });
    window.location.reload();
  }
}
```

## volta-sdk (Java, App 向け)

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .jwksCacheDuration(Duration.ofHours(1))
    .build();

app.before("/api/*", volta.middleware());

app.get("/api/data", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    if (!user.hasRole("ADMIN")) throw new ForbiddenResponse();
});
```

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
RATE_LIMIT_API_PER_MIN=200

# Audit
AUDIT_RETENTION_DAYS=365

# Dev
DEV_MODE=false

# Service Token (App バッチ処理用)
VOLTA_SERVICE_TOKEN=           # crypto random 64 bytes, base64

# Redirect whitelist
ALLOWED_REDIRECT_DOMAINS=      # comma-separated: app-a.example.com,app-b.example.com
```

## 拡張 Interface

```java
interface OidcProvider {
    String authorizeUrl(String state, String nonce, String codeVerifier);
    OidcTokenResponse exchangeCode(String code, String codeVerifier);
    OidcUserInfo verifyIdToken(String idToken, String nonce);
}

interface SessionStore {
    Session save(Session session);
    Session load(UUID sessionId);
    void invalidate(UUID sessionId);
    List<Session> listByUser(UUID userId);
}

interface KeyStore {
    SigningKey getActiveKey();
    List<SigningKey> getPublicKeys(); // JWKS 用
    SigningKey rotateKey();
}

interface RateLimiter {
    boolean tryAcquire(String key, int limit, Duration window);
}

interface AuditSink {
    void emit(AuditEvent event);
}

interface NotificationChannel {
    void send(String recipient, String subject, String body);
}
```

## Phase 1 で意図的にやらないこと

1. 水平スケーリング（単一インスタンス前提）
2. SAML / 他 IdP 対応
3. 細粒度パーミッション（RBAC 4 ロールで十分）
4. JWT 暗号化 (JWE)
5. Webhook / イベント通知
6. テナント間 DB レベル分離
7. メール送信

---

# ═══════════════════════════════════════
# Phase 2: Scale
# ═══════════════════════════════════════

## 追加機能

### 2-1. 複数 IdP 対応

```
OidcProvider 実装追加:
  GitHubOidcProvider
  MicrosoftOidcProvider

users テーブル変更:
  google_sub → 別テーブルに分離

CREATE TABLE user_identities (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id),
  provider    VARCHAR(30) NOT NULL,    -- google, github, microsoft
  provider_sub VARCHAR(255) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(provider, provider_sub)
);
CREATE INDEX idx_identities_user ON user_identities(user_id);

ログイン画面:
  [Google でログイン]
  [GitHub でログイン]
  [Microsoft でログイン]

アカウントリンク:
  同一 email で複数 provider を自動リンク
  設定画面で手動リンク/解除
```

### 2-2. M2M 認証（Client Credentials）

```
選択肢 A: Ory Hydra 導入
  → OIDC プロトコル処理を Hydra に委譲
  → client_id / client_secret で M2M トークン取得

選択肢 B: 自前 Client Credentials（推奨）
  → signing_keys を使って M2M JWT を発行
  → App ごとに client_id / client_secret を管理

CREATE TABLE api_clients (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  client_id     VARCHAR(64) NOT NULL UNIQUE,
  client_secret_hash VARCHAR(255) NOT NULL,  -- bcrypt
  name          VARCHAR(100) NOT NULL,
  tenant_id     UUID REFERENCES tenants(id),  -- NULL = platform level
  scopes        TEXT[],                        -- 許可スコープ
  is_active     BOOLEAN NOT NULL DEFAULT true,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

POST /oauth/token
  grant_type=client_credentials
  client_id=xxx
  client_secret=yyy
  → JWT 発行（volta_roles に client のスコープを反映）
```

### 2-3. Redis セッションストア

```
SessionStore 実装追加:
  RedisSessionStore

環境変数:
  SESSION_STORE=redis          # postgres | redis
  REDIS_URL=redis://redis:6379

メリット:
  - 水平スケーリング対応
  - セッション一覧が高速
  - TTL 自動管理
```

### 2-4. Webhook イベント通知

```
CREATE TABLE webhook_endpoints (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID REFERENCES tenants(id),
  url         VARCHAR(2048) NOT NULL,
  secret      VARCHAR(255) NOT NULL,        -- HMAC 署名用
  events      TEXT[] NOT NULL,               -- 購読イベント
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_deliveries (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  endpoint_id   UUID NOT NULL REFERENCES webhook_endpoints(id),
  event_type    VARCHAR(50) NOT NULL,
  payload       JSONB NOT NULL,
  status        VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending/success/failed
  attempts      INT NOT NULL DEFAULT 0,
  last_attempt  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

イベント:
  user.created, user.suspended, user.deleted
  member.joined, member.role_changed, member.removed
  tenant.suspended, tenant.deleted

配信:
  POST {webhook_url}
  Content-Type: application/json
  X-Volta-Signature: HMAC-SHA256(secret, body)
  X-Volta-Event: user.suspended

  リトライ: 3 回（指数バックオフ: 1min, 5min, 30min）
```

### 2-5. App 単位 aud + Rate Limit

```
JWT aud 拡張:
  "aud": ["volta-apps", "app-wiki", "app-chat"]

App ごとの Rate Limit:
  X-Volta-App-Id ヘッダ導入
  200 req/min per {user_id}:{app_id}

volta-config.yaml を DB に移行:
  CREATE TABLE registered_apps (
    id            VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    url           VARCHAR(2048) NOT NULL,
    allowed_roles TEXT[] NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  );
```

### 2-6. テナント別ブランディング

```
ALTER TABLE tenants ADD COLUMN logo_url VARCHAR(2048);
ALTER TABLE tenants ADD COLUMN primary_color VARCHAR(7);  -- #hex
ALTER TABLE tenants ADD COLUMN theme VARCHAR(20) DEFAULT 'default';

ThemeProvider interface 追加。
ログイン画面・招待画面にテナントのロゴ/カラー反映。
```

## Phase 2 の追加環境変数

```env
SESSION_STORE=postgres          # postgres | redis
REDIS_URL=                      # redis://redis:6379
WEBHOOK_RETRY_MAX=3
WEBHOOK_ENABLED=false
```

---

# ═══════════════════════════════════════
# Phase 3: Enterprise
# ═══════════════════════════════════════

## 追加機能

### 3-1. SAML IdP 対応

```
OidcProvider を拡張して SamlProvider を追加。
テナント管理画面で SAML metadata URL を設定可能に。

CREATE TABLE tenant_idp_configs (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  provider    VARCHAR(30) NOT NULL,          -- saml, oidc
  config      JSONB NOT NULL,                -- metadata_url, entity_id, etc.
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, provider)
);

ライブラリ: onelogin/java-saml or pac4j
```

### 3-2. メール送信

```
NotificationChannel 実装追加:
  SmtpNotificationChannel
  SendGridNotificationChannel（SaaS 版）

用途:
  - 招待メール
  - パスワードリセット（パスワード認証追加時）
  - セキュリティアラート（新デバイスログイン）

環境変数:
  NOTIFICATION_CHANNEL=smtp     # smtp | sendgrid | none
  SMTP_HOST=
  SMTP_PORT=587
  SMTP_USER=
  SMTP_PASSWORD=
  SMTP_FROM=noreply@example.com

ローカル: MailHog / Mailpit でモック
```

### 3-3. MFA (多要素認証)

```
TOTP (Google Authenticator 等):
  ライブラリ: com.warrenstrange:googleauth

CREATE TABLE user_mfa (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id),
  type        VARCHAR(20) NOT NULL,          -- totp, webauthn
  secret      TEXT NOT NULL,                 -- 暗号化保存
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

テナント設定で MFA 必須/任意を制御。
JWT claims に amr (Authentication Methods References) を追加。
```

### 3-4. ローカライゼーション (ja/en)

```
ALTER TABLE users ADD COLUMN locale VARCHAR(10) DEFAULT 'ja';

jte テンプレートで i18n:
  messages_ja.properties
  messages_en.properties

Accept-Language ヘッダでフォールバック。
```

### 3-5. 管理 UI 拡張

```
/admin/tenants         — テナント一覧・作成・停止・削除
/admin/tenants/{id}    — テナント詳細・設定
/admin/users           — 全ユーザー一覧（platform admin）
/admin/audit           — 監査ログ検索
/admin/webhooks        — Webhook 管理
/admin/idp             — IdP 設定（SAML/OIDC）
```

### 3-6. テナント self-service 作成

```
ALLOW_SELF_SERVICE_TENANT=true に変更。
POST /api/v1/tenants でユーザーがテナント作成可能。
作成者は自動的に OWNER。
```

---

# ═══════════════════════════════════════
# Phase 4: Platform
# ═══════════════════════════════════════

## 追加機能

### 4-1. SCIM (System for Cross-domain Identity Management)

```
エンタープライズ顧客の Active Directory / Okta と同期。

GET    /scim/v2/Users
POST   /scim/v2/Users
GET    /scim/v2/Users/{id}
PUT    /scim/v2/Users/{id}
PATCH  /scim/v2/Users/{id}
DELETE /scim/v2/Users/{id}
GET    /scim/v2/Groups
POST   /scim/v2/Groups

ライブラリ: 自前 or Apache SCIMple
```

### 4-2. Policy Engine

```
RBAC 4 ロールでは足りなくなった場合:

選択肢 A: Ory Keto (Zanzibar 風)
選択肢 B: Cedar (AWS 製, Rust)
選択肢 C: 自前のシンプルな ABAC

CREATE TABLE policies (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID REFERENCES tenants(id),
  resource    VARCHAR(100) NOT NULL,
  action      VARCHAR(50) NOT NULL,
  condition   JSONB,
  effect      VARCHAR(10) NOT NULL DEFAULT 'allow',  -- allow | deny
  priority    INT NOT NULL DEFAULT 0,
  is_active   BOOLEAN NOT NULL DEFAULT true
);

SDK 拡張:
  volta.can("edit", "document", { ownerId: "..." })
```

### 4-3. Billing / Plan 管理

```
CREATE TABLE plans (
  id          VARCHAR(30) PRIMARY KEY,       -- free, pro, enterprise
  name        VARCHAR(100) NOT NULL,
  max_members INT NOT NULL,
  max_apps    INT NOT NULL,
  features    TEXT[] NOT NULL
);

CREATE TABLE subscriptions (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  plan_id     VARCHAR(30) NOT NULL REFERENCES plans(id),
  status      VARCHAR(20) NOT NULL,          -- active, past_due, canceled
  stripe_sub_id VARCHAR(255),
  started_at  TIMESTAMPTZ NOT NULL,
  expires_at  TIMESTAMPTZ
);

Stripe 連携:
  Webhook で subscription 状態同期
  JWT claims に volta_plan を追加
```

### 4-4. データエクスポート / Right to be Forgotten

```
POST /api/v1/users/{id}/export
  → ユーザーデータの JSON エクスポート（Gateway 側 + App 側に通知）

DELETE /api/v1/users/{id}/data
  → GDPR Right to Erasure
  → Gateway: ユーザー関連データ全削除
  → App: Webhook で削除通知 → App 側で対応

Webhook event: user.data_export_requested, user.data_deletion_requested
```

### 4-5. 監査ログの外部連携

```
AuditSink 実装追加:
  KafkaAuditSink
  ElasticsearchAuditSink

環境変数:
  AUDIT_SINK=postgres           # postgres | kafka | elasticsearch
  KAFKA_BOOTSTRAP_SERVERS=
  ELASTICSEARCH_URL=
```

---

# ═══════════════════════════════════════
# 全 Phase まとめ
# ═══════════════════════════════════════

```
Phase 1: Core
  ✅ Google OIDC 直接連携
  ✅ テナント解決 + role + 招待
  ✅ JWT 自前発行 (nimbus-jose-jwt)
  ✅ ForwardAuth (X-Volta-* ヘッダ)
  ✅ Internal API (/api/v1/*)
  ✅ UI (login, tenant-select, invite, sessions, admin)
  ✅ volta-sdk (Java) + volta-sdk-js
  ✅ 監査ログ
  docker-compose: Traefik + Gateway + Postgres

Phase 2: Scale
  + 複数 IdP (GitHub, Microsoft)
  + M2M 認証 (Client Credentials)
  + Redis セッション
  + Webhook イベント通知
  + App 単位 aud + Rate Limit
  + テナント別ブランディング
  docker-compose: + Redis

Phase 3: Enterprise
  + SAML IdP
  + メール送信
  + MFA (TOTP)
  + ローカライゼーション (ja/en)
  + 管理 UI 拡張
  + テナント self-service 作成

Phase 4: Platform
  + SCIM
  + Policy Engine
  + Billing (Stripe)
  + データエクスポート / GDPR
  + 監査ログ外部連携 (Kafka/ES)
```
