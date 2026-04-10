# AUTH-014: Tenant & Organization Spec

> volta-auth-proxy のマルチテナント・マルチユーザー設計仕様

## 1. 概要

volta-auth-proxy は以下のデプロイメント形態をサポートする:

- 個人ホームサーバー（ユーザー数: 1-10）
- 小規模チーム SaaS（1 組織、10-50 人）
- マルチテナント SaaS（複数組織、各 10-100 人）
- 個人向けプラットフォーム（多数の個人ユーザー）

1 つのコードベース、設定の切り替えで全形態に対応する。

## 2. レイヤーアーキテクチャ

```
┌─────────────────────────────────────────────────────────┐
│  Layer 1: Identity                                      │
│  ユーザー認証: OIDC, Passkey, MFA, Magic Link           │
│  → 「誰か」を確定する                                    │
├─────────────────────────────────────────────────────────┤
│  Layer 2: Tenancy                                       │
│  テナント = 論理的セキュリティ境界 + 管理単位            │
│  → 「どの組織/ワークスペースか」を確定する               │
├─────────────────────────────────────────────────────────┤
│  Layer 3: Access                                        │
│  RBAC + per-resource ACL                                │
│  → 「何ができるか」を確定する                            │
├─────────────────────────────────────────────────────────┤
│  Layer 4: Binding                                       │
│  ユーザーとデータソースの紐づけ                          │
│  → 「どのデータを見るか」を確定する                      │
├─────────────────────────────────────────────────────────┤
│  Layer 5: Isolation                                     │
│  データの物理的分離                                      │
│  → 「どう分離するか」を確定する                          │
└─────────────────────────────────────────────────────────┘
```

各レイヤーは独立して設定可能。下位レイヤーは上位レイヤーの結果を入力として使う。

## 3. 用語定義

| 用語 | 定義 | 設定で変更可 |
|------|------|-------------|
| **Tenant** | インフラ上のリソース分離単位。DB の `tenants` テーブルに対応。 | No |
| **Organization** | ユーザー向けの名前。Tenant のユーザー向けエイリアス。 | Yes (`org_display_name`) |
| **Member** | Organization に所属するユーザー。ロールを持つ。 | No |
| **Binding** | ユーザーとデータソースの紐づけ。同じアプリ、違うデータ。 | No |

`org_display_name` 設定例:
```yaml
# "Organization" の代わりに表示する名前
org_display_name: "Workspace"    # Slack 風
org_display_name: "Team"         # 小規模チーム
org_display_name: "Account"      # B2C
org_display_name: "Project"      # dev tool
```

## 4. Tenant Mode

### 4.1 設定

```yaml
tenancy:
  mode: single | multi
  creation_policy: disabled | auto | admin_only | invite_only
  max_orgs_per_user: 1          # 1 = 固定、N = 切り替え可能
  shadow_org: true              # single mode 時に透明な org を自動作成
  slug_format: "{name}-{random6}"  # org slug の生成パターン
```

### 4.2 Mode 詳細

#### `single` mode

```yaml
tenancy:
  mode: single
  creation_policy: disabled
  shadow_org: true
```

- テナントは 1 つ（自動作成、ユーザーには見えない）
- サインアップ → 既存テナントに自動参加
- テナント切り替え UI は非表示
- ホームサーバー、小規模チーム向け

#### `multi` mode

```yaml
tenancy:
  mode: multi
  creation_policy: auto          # signup 時に personal org を自動作成
  max_orgs_per_user: 10
```

- ユーザーは複数 Organization に所属可能
- Discovery Flow: ログイン → 所属 org 一覧 → 選択
- org ごとに独立した設定・メンバー・ロール
- SaaS、プラットフォーム向け

### 4.3 Creation Policy

| Policy | 動作 | ユースケース |
|--------|------|-------------|
| `disabled` | org 作成不可。既存 org への招待のみ。 | single mode、閉じた環境 |
| `auto` | サインアップ時に personal org を自動作成 | B2C、個人プラットフォーム |
| `admin_only` | プラットフォーム管理者のみ org 作成可 | エンタープライズ |
| `invite_only` | 既存 org の admin が新規 org を作成可 | B2B SaaS |

## 5. Tenant Routing

### 5.1 設定

```yaml
tenancy:
  routing:
    mode: none | slug | subdomain | domain
    base_domain: "unlaxer.org"         # subdomain/domain mode 用
    slug_header: "X-Volta-Tenant-Slug"  # gateway に伝達するヘッダー
```

### 5.2 Routing Mode

#### `none` (single mode 用)

テナント解決不要。全リクエストがデフォルトテナントに属す。

#### `slug` (path or parameter)

```
https://app.example.com/login?org=acme-corp
https://app.example.com/api/v1/users?org=acme-corp
```

- URL パラメータまたはヘッダーで slug を指定
- volta-gateway が `X-Volta-Tenant-Slug` ヘッダーに変換
- Cookie は shared（同一ドメイン）
- **セキュリティ境界ではない**（同一 origin）

#### `subdomain`

```
https://acme-corp.app.example.com/
```

- ワイルドカード DNS (`*.app.example.com`) が必要
- volta-gateway が Host header からテナント slug を抽出
- Cookie scope: `.app.example.com`（共有）or `acme-corp.app.example.com`（分離）
- **設定可能なセキュリティ境界**

```yaml
tenancy:
  routing:
    mode: subdomain
    cookie_scope: shared | isolated
    # shared: .app.example.com (SSO 体験)
    # isolated: {slug}.app.example.com (セキュリティ優先)
```

#### `domain`

```
https://acme-corp.com/
```

- テナントごとにカスタムドメインを設定
- DNS CNAME + TLS 証明書が必要
- 完全なセキュリティ境界（Cookie、origin が完全分離）
- volta-gateway がドメイン → テナント slug のマッピングを持つ

```yaml
# テナントごとの設定 (DB or config)
custom_domains:
  - domain: "acme-corp.com"
    tenant_slug: "acme-corp"
    tls: auto  # Let's Encrypt
```

## 6. Member Model

### 6.1 User-to-Tenant 関係

```
┌─────────┐     ┌──────────────┐     ┌─────────┐
│  User   │────>│  Membership  │<────│  Tenant  │
│         │ N:N │  - role      │     │         │
│  (Layer1)│     │  - joined_at │     │ (Layer2)│
└─────────┘     │  - managed   │     └─────────┘
                └──────────────┘
```

### 6.2 Managed vs Unmanaged Members

| Type | ライフサイクル | 例 |
|------|-------------|---|
| **Managed** | org 削除 → member 削除 | IdP provisioning (SCIM), 招待のみの org |
| **Unmanaged** | org 削除 → membership 削除、user は残る | self-service join、SSO |

```yaml
tenancy:
  member_lifecycle: managed | unmanaged
```

### 6.3 Discovery Flow

multi mode 時のログインフロー:

```
1. ユーザーが認証（OIDC / Passkey / Magic Link）
2. auth-proxy が所属 org 一覧を取得
3. org が 1 つ → 自動選択
4. org が複数 → 選択画面を表示
5. 選択後、セッションに active_org を設定
6. 以降のリクエストは active_org のコンテキストで処理
```

## 7. Access Layer (Layer 3)

### 7.1 RBAC

```yaml
access:
  default_roles:
    - OWNER       # テナント管理、メンバー管理、全操作
    - ADMIN       # サービス管理、デプロイ、設定変更
    - OPERATOR    # サービス操作（再起動、ターミナル）
    - VIEWER      # 閲覧のみ
  custom_roles: true  # テナント管理者がカスタムロールを定義可能
```

### 7.2 Service Visibility

services.json (または DB) で設定:

```json
{
  "jellyfin": {
    "access": {
      "visibility": "all",
      "actions": {
        "view": "VIEWER",
        "open": "VIEWER",
        "deploy": "ADMIN",
        "terminal": "OPERATOR",
        "config": "ADMIN",
        "delete": "OWNER"
      }
    }
  },
  "portainer": {
    "access": {
      "visibility": "role:ADMIN",
      "actions": {
        "view": "ADMIN",
        "open": "ADMIN"
      }
    }
  }
}
```

### 7.3 Visibility 値

| 値 | 表示対象 |
|----|---------|
| `public` | 未認証ユーザーを含む全員 |
| `all` | 認証済みの全メンバー |
| `role:VIEWER` | VIEWER 以上のロール |
| `role:ADMIN` | ADMIN 以上のロール |
| `bound-users` | Binding が設定されたユーザーのみ |
| `explicit` | ACL で明示的に許可されたユーザーのみ |

## 8. Binding Layer (Layer 4)

### 8.1 概要

Binding Layer は「同じアプリケーション、違うデータソース」をプラットフォームレベルで実現する。

auth 製品の中でこの概念を提供するものは存在しない（2025 年時点）。
全製品が「org_id をトークンに入れるからアプリ側でフィルタしろ」で終わる。

volta-auth-proxy + volta-gateway の組み合わせでこれを解決する。

### 8.2 DataSource Types

| Type | 例 | Binding 方法 |
|------|---|-------------|
| `filesystem` | `/home/{user}/notebooks/` | Docker volume mount |
| `database` | `WHERE user_id = ?` or schema `user_{id}` | Connection string / RLS |
| `volume` | Docker named volume `{user}-data` | Docker volume mount |
| `credentials` | ユーザーごとの API key, OAuth token | 環境変数 / secret mount |
| `config` | ユーザーごとの設定ファイル | File mount |
| `object-storage` | `s3://bucket/{user}/` | 環境変数 |

### 8.3 Instance Pattern

| Pattern | 説明 | アプリ変更 | リソース |
|---------|------|-----------|---------|
| `container-per-user` | ユーザーごとにコンテナ起動 | 不要 | 大 |
| `shared-container` | 1 コンテナ、ヘッダーでユーザー識別 | 必要 | 小 |
| `gateway-rewrite` | gateway がユーザーごとに転送先を変更 | 不要 | 大 |

### 8.4 services.json での定義

```json
{
  "notebook": {
    "instance_policy": {
      "instance_mode": "multi",
      "user_mode": "per-user",
      "launch_mode": "docker",
      "container_image": "notebook:latest",
      "port_range": [8100, 8199],
      "binding": {
        "data": {
          "type": "filesystem",
          "host_path": "/srv/user-data/{user}/notebooks",
          "container_path": "/data",
          "auto_create": true,
          "template": "/srv/templates/notebook-default"
        },
        "config": {
          "type": "filesystem",
          "host_path": "/srv/user-data/{user}/notebook-config",
          "container_path": "/config",
          "auto_create": true
        }
      }
    },
    "access": {
      "visibility": "bound-users",
      "actions": {
        "view": "VIEWER",
        "open": "VIEWER",
        "admin": "ADMIN"
      }
    }
  }
}
```

### 8.5 Binding Lifecycle

| Event | 動作 |
|-------|------|
| ユーザー初回アクセス | `auto_create: true` なら DataSource を自動作成 |
| `template` 指定あり | テンプレートからコピーして初期化 |
| ユーザー削除 | `on_delete: archive` (デフォルト) or `delete` |
| テナント削除 | テナント内の全 Binding を archive/delete |
| GDPR 削除要求 | 全 DataSource を削除 + 監査ログ匿名化 |

```yaml
binding:
  lifecycle:
    on_user_delete: archive | delete
    on_tenant_delete: archive | delete
    archive_path: /srv/archives/{tenant}/{user}/
    retention_days: 90
```

### 8.6 Binding と Sandbox の関係

```
Binding  = 「何のデータを使うか」（データ管理の概念）
Sandbox  = 「どう隔離するか」（セキュリティの概念）

Sandbox の中で Binding されたデータにアクセスする。
両者は直交する概念。
```

```json
{
  "code-server": {
    "instance_policy": {
      "sandbox": {
        "enabled": true,
        "read_only_base": true,
        "capabilities": ["NET_BIND_SERVICE"],
        "memory_limit": "2g",
        "cpu_limit": "1.0"
      },
      "binding": {
        "workspace": {
          "type": "filesystem",
          "host_path": "/srv/user-data/{user}/code-workspace",
          "container_path": "/workspace",
          "auto_create": true
        }
      }
    }
  }
}
```

## 9. Isolation Layer (Layer 5)

### 9.1 設定

```yaml
isolation:
  mode: shared | schema | database
  # shared:   全テナント同一 DB、tenant_id カラムで分離
  # schema:   テナントごとに PostgreSQL schema
  # database: テナントごとに別 DB インスタンス
```

### 9.2 各モードの特性

| Mode | テナント数上限 | 分離強度 | マイグレーション | 適用 UC |
|------|-------------|---------|----------------|---------|
| `shared` | 無制限 | 論理 (RLS) | 1 回 | UC-1,2,3 |
| `schema` | 〜100 | 中 | テナント数 × 回 | UC-3 (厳密) |
| `database` | 〜10 | 完全 | テナント数 × 回 | エンタープライズ |

### 9.3 Shared mode の実装

```sql
-- 全テーブルに tenant_id を追加
ALTER TABLE users ADD COLUMN tenant_id UUID REFERENCES tenants(id);
ALTER TABLE sessions ADD COLUMN tenant_id UUID REFERENCES tenants(id);

-- RLS ポリシー
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
  USING (tenant_id = current_setting('app.current_tenant_id')::uuid);

-- auth-proxy が各リクエストで設定
SET app.current_tenant_id = '{tenant_id}';
```

## 10. 設定例: ユースケース別

### UC-1: 個人ホームサーバー

```yaml
tenancy:
  mode: single
  creation_policy: disabled
  shadow_org: true
  routing:
    mode: none

access:
  default_roles: [OWNER, ADMIN, VIEWER]

isolation:
  mode: shared
```

### UC-2: 小規模チーム

```yaml
tenancy:
  mode: single
  creation_policy: invite_only
  routing:
    mode: none
  member_lifecycle: unmanaged

access:
  default_roles: [OWNER, ADMIN, OPERATOR, VIEWER]
  custom_roles: false

isolation:
  mode: shared
```

### UC-3: マルチテナント B2B SaaS

```yaml
tenancy:
  mode: multi
  creation_policy: admin_only
  max_orgs_per_user: 5
  routing:
    mode: subdomain
    base_domain: "app.example.com"
    cookie_scope: isolated
  member_lifecycle: managed

access:
  default_roles: [OWNER, ADMIN, MEMBER, GUEST]
  custom_roles: true

isolation:
  mode: schema
```

### UC-4: 個人向けプラットフォーム

```yaml
tenancy:
  mode: multi
  creation_policy: auto
  max_orgs_per_user: 1
  routing:
    mode: slug
  member_lifecycle: managed

access:
  default_roles: [OWNER, COLLABORATOR]

binding:
  lifecycle:
    on_user_delete: delete
    retention_days: 30

isolation:
  mode: shared
```

## 11. volta-gateway との連携

volta-gateway はリクエストを受けて以下のヘッダーを付与する:

```
X-Volta-User-Id: {user_id}
X-Volta-Email: {email}
X-Volta-Roles: {roles}
X-Volta-Tenant-Id: {tenant_id}      ← Layer 2
X-Volta-Tenant-Slug: {slug}         ← Layer 2
X-Volta-Binding: {binding_json}     ← Layer 4 (optional)
```

### Routing Mode ごとの tenant 解決

| Mode | Gateway の動作 |
|------|---------------|
| `none` | デフォルト tenant_id を使用 |
| `slug` | URL param or header から slug → tenant_id |
| `subdomain` | Host header から slug 抽出 → tenant_id |
| `domain` | Host header → domain-to-tenant mapping → tenant_id |

### Binding ヘッダー

gateway が Binding 情報をバックエンドに伝達する場合:

```json
X-Volta-Binding: {
  "data": {"type": "filesystem", "path": "/srv/user-data/alice/notebooks"},
  "config": {"type": "filesystem", "path": "/srv/user-data/alice/notebook-config"}
}
```

バックエンドアプリは `X-Volta-Binding` を読んで DataSource を切り替える。
Container-per-user モードでは不要（volume mount で解決）。

## 12. マイグレーションパス

### single → multi

1. 既存の shadow tenant を「default」org として visible に
2. `creation_policy` を変更
3. 新規 org 作成を許可
4. 既存ユーザーの membership は default org に残る

### shared → schema

1. テナントごとに PostgreSQL schema を作成
2. 既存データを schema に移行（`tenant_id` で振り分け）
3. RLS ポリシーを schema 分離に置き換え
4. 接続プールを schema-aware に変更

## 13. 実装ロードマップ

### Phase 1: 基盤 (Low risk)

- [ ] `tenancy.creation_policy: disabled` の実装
- [ ] `tenancy.mode: single` の明示化（shadow_org パターン）
- [ ] `access.visibility` の services.json 拡張
- [ ] per-action 権限 (view / deploy / terminal / config)

### Phase 2: Multi-tenant (Medium risk)

- [ ] `tenancy.mode: multi` の実装
- [ ] Discovery Flow（org 選択画面）
- [ ] `tenancy.routing: slug` の実装
- [ ] `X-Volta-Tenant-Id` ヘッダーの gateway 対応

### Phase 3: Binding (Medium risk)

- [ ] `binding` スキーマの services.json 追加
- [ ] Container-per-user binding の instanceManager 汎用化
- [ ] Binding lifecycle（auto_create, template, archive）
- [ ] `visibility: bound-users` の実装

### Phase 4: Advanced (High risk)

- [ ] `tenancy.routing: subdomain` の実装
- [ ] Cookie scope 制御（shared / isolated）
- [ ] `isolation: schema` の実装
- [ ] `tenancy.routing: domain` の実装
- [ ] Custom role 定義

## Appendix: 競合比較

| Product | Tenant 概念 | 分離 | User:Tenant | 作成 | URL Routing |
|---------|-----------|------|-------------|------|-------------|
| Auth0 | Organization | Shared | configurable | Admin+API | param |
| Clerk | Organization | Shared | N:N | Self-service | none |
| WorkOS | Organization | Shared | N:N | Admin+API | none |
| Keycloak | Realm+Org | Realm=物理, Org=論理 | 1:1(Realm), N:N(Org) | Admin | path |
| Ory | Project | network_id scoping | 1:1 | API | project URL |
| PropelAuth | Organization | configurable | N:N (max 設定可) | Self-service | none |
| Stytch | Organization | Shared | N:N | Discovery | slug |
| FusionAuth | Tenant+App | Logical | 1:1 per tenant | Admin | domain/header |
| **volta** | **Tenant+Org** | **configurable** | **configurable** | **configurable** | **configurable** |

volta-auth-proxy の差別化:
- **全レイヤーが設定で切り替え可能**
- **Binding Layer** — 他製品にない per-user データソースマッピング
- **Self-hosted first** — Ory と同じポジションだが Binding を追加
