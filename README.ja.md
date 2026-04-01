# volta-auth-proxy

[English](README.md) | [Japanese (日本語)](README.ja.md)

**マルチテナント認証を、[HTTP ヘッダ](docs/glossary/header.ja.md)1つで。**

[マルチテナント](docs/glossary/multi-tenant.ja.md) [SaaS](docs/glossary/saas.ja.md) 向け [Identity Gateway](docs/glossary/identity-gateway.ja.md)。
[認証](docs/glossary/authentication-vs-authorization.ja.md)・テナント管理・[ロール](docs/glossary/role.ja.md)・[招待](docs/glossary/invitation-flow.ja.md)を一手に引き受け、[下流](docs/glossary/downstream-app.ja.md)の [App](docs/glossary/downstream-app.ja.md) は何もしなくてよくなります。

**[Keycloak](docs/glossary/keycloak.ja.md) 不要。[oauth2-proxy](docs/glossary/reverse-proxy.ja.md) 不要。制御こそ正義。**

> **認証の用語は難しい。でも「分かったフリ」が一番危険。**
> このドキュメントでは全ての専門用語に[解説記事](docs/glossary/)がリンクされています。
> クリックして読んでください。恥ずかしくない。知らないことを知るのが一番大事。
> AI 時代は教育が大事！

> 全ての単語がクリッカブル。全ての用語に解説あり。
> おばあちゃんでも新人エンジニアでも、クリックすれば分かる。
> 書くのは地獄。読むのは天国。

## こんな人のためのもの

- BtoB [SaaS](docs/glossary/saas.ja.md) を作っている
- [マルチテナント](docs/glossary/multi-tenant.ja.md)[認証](docs/glossary/authentication-vs-authorization.ja.md)が必要
- [Keycloak](docs/glossary/keycloak.ja.md) や [Auth0](docs/glossary/auth0.ja.md) に疲れた
- でも認証を自作したくない

**→ [HTTP ヘッダ](docs/glossary/header.ja.md)を読むだけで認証が使えます**

> **はじめての方へ** → [はじめよう：会話で学ぶ volta-auth-proxy](docs/getting-started-dialogue.ja.md)

***

## どう動くか

```
Browser ──→ Traefik ──────────────────────────────────→ App
                  │                                      ↑
                  └──→ volta (ForwardAuth チェック) ─────┘
                                  │
                         認証・テナント解決
                                  │
                      X-Volta-User-Id: abc123
                      X-Volta-Tenant-Id: t456
                      X-Volta-Role: MEMBER
```

リクエストが来るたびに Traefik が volta に問い合わせます。
volta がセッションを確認し、テナントを解決し、ヘッダに書き込みます。
**アプリはヘッダを読むだけ。認証コードは1行も書かなくてよい。**

***

## Quick Start（5分）

```bash
# 1. リポジトリをクローン
git clone https://github.com/your-org/volta-auth-proxy
cd volta-auth-proxy

# 2. Google OAuth アプリを作成し、リダイレクト URI を設定
#    https://console.cloud.google.com/ → 認証情報 → OAuth 2.0 クライアント ID
#    リダイレクト URI: http://localhost:7070/callback
export GOOGLE_CLIENT_ID=your-client-id
export GOOGLE_CLIENT_SECRET=your-client-secret

# 3. DB を起動
docker-compose up -d

# 4. volta を起動
mvn compile exec:java

# 5. ブラウザで確認
open http://localhost:7070/login
```

***

## なぜ volta-auth-proxy？

[SaaS](docs/glossary/saas.ja.md) を作る。[認証](docs/glossary/authentication-vs-authorization.ja.md)が必要。選択肢は:

| 選択肢 | 結果 |
|--------|------|
| [Auth0](docs/glossary/auth0.ja.md) / Clerk | すぐ動く。[MAU](docs/glossary/mau.ja.md) 10万で月 $2,400。[ベンダーロックイン](docs/glossary/vendor-lock-in.ja.md)。[セルフホスト](docs/glossary/self-hosting.ja.md)不可 |
| [Keycloak](docs/glossary/keycloak.ja.md) | 無料。[realm.json](docs/glossary/realm-json.ja.md) 500 行の[設定地獄](docs/glossary/config-hell.ja.md)。メモリ 512MB。起動 30 秒。[FreeMarker](docs/glossary/freemarker.ja.md) テーマ |
| 自前で全部作る | 完全な制御。ただし [OIDC](docs/glossary/oidc.ja.md)、[JWT](docs/glossary/jwt.ja.md)、[セッション](docs/glossary/session.ja.md)、[CSRF](docs/glossary/csrf.ja.md)、[テナント](docs/glossary/tenant.ja.md)分離を全部正しくやる必要あり |

**volta-auth-proxy は選択肢 3 を正しくやったもの。** 難しい部分（[OIDC](docs/glossary/oidc.ja.md) フロー、[JWT](docs/glossary/jwt.ja.md) 署名、[セッション](docs/glossary/session.ja.md)管理、[テナント](docs/glossary/tenant.ja.md)解決）は実装済み。[App](docs/glossary/downstream-app.ja.md) は[ヘッダ](docs/glossary/header.ja.md)を読むだけ。

### 競合比較

| | volta-auth-proxy | [Keycloak](docs/glossary/keycloak.ja.md) | [Auth0](docs/glossary/auth0.ja.md) | [ZITADEL](docs/glossary/zitadel.ja.md) | [Ory Stack](docs/glossary/ory-stack.md) |
|---|---|---|---|---|---|
| **[セルフホスト](docs/glossary/self-hosting.ja.md)** | はい（のみ） | はい | 不可 | はい | はい |
| **起動** | ~200ms | ~30秒 | N/A | ~3-5秒 | ~数秒 |
| **メモリ** | ~30MB | ~512MB+ | N/A | ~150-300MB | ~200-400MB |
| **[マルチテナント](docs/glossary/multi-tenant.ja.md)** | 設計の中核 | [レルム](docs/glossary/realm.ja.md)制限あり | [Organizations](docs/glossary/organizations.ja.md)（有料） | ネイティブ | [自前実装](docs/glossary/native-implementation.ja.md) |
| **[ログイン](docs/glossary/login.ja.md) UI** | 完全制御 ([jte](docs/glossary/jte.ja.md)) | テーマ地獄 | 制限あり | テーマ | 自前 |
| **10万 [MAU](docs/glossary/mau.ja.md) コスト** | $0 | $0（運用コスト別） | ~$2,400/月 | $0（[セルフホスト](docs/glossary/self-hosting.ja.md)） | $0 |
| **[App 連携](docs/glossary/app-integration.ja.md)** | [ForwardAuth](docs/glossary/forwardauth.ja.md) ([📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid)) + [Internal API](docs/glossary/internal-api.ja.md) | 汎用 [OIDC](docs/glossary/oidc.ja.md) | [SDK](docs/glossary/sdk.ja.md) | 汎用 [OIDC](docs/glossary/oidc.ja.md) | [Oathkeeper](docs/glossary/oathkeeper.ja.md) |
| **[設定の複雑さ](docs/glossary/complexity-of-configuration.ja.md)** | [.env](docs/glossary/environment-variable.ja.md) + [YAML](docs/glossary/yaml.ja.md) 1 個 | 数百の設定項目 | [ダッシュボード](docs/glossary/dashboard.ja.md) | 中程度 | 4 サービス |
| **[外部依存](docs/glossary/external-dependency.ja.md)** | [Postgres](docs/glossary/database.ja.md) のみ | Postgres + [JVM](docs/glossary/jvm.ja.md) | クラウド | Postgres/[CRDB](docs/glossary/crdb.ja.md) | Postgres + 複数 |

volta は最も軽量で、最も制御しやすい選択肢。[トレードオフ](docs/glossary/tradeoff.ja.md): [セキュリティの責任](docs/glossary/security-responsibility.ja.md)を自分で負う。

***

## 設計哲学

- **制御しやすいは正義** — [外部サーバー](docs/glossary/external-vs-internal.ja.md)への[依存](docs/glossary/external-dependency.ja.md)を最小化
- **理解できる地獄を選ぶ** — [Keycloak](docs/glossary/keycloak.ja.md) の[設定地獄](docs/glossary/config-hell.ja.md)も、[OIDC](docs/glossary/oidc.ja.md) [自前実装](docs/glossary/native-implementation.ja.md)の地獄も、どちらも地獄。なら[スタックトレース](docs/glossary/stack-trace.ja.md)が読める方を選ぶ。Auth だけは自分で持つ。何が起きているか分からないシステムにユーザーの[認証](docs/glossary/authentication-vs-authorization.ja.md)を預けない
- **密結合上等** — 1 [プロセス](docs/glossary/process.ja.md)で完結。[マイクロサービス](docs/glossary/microservice.ja.md)的な疎結合がもたらすのは「正しい[アーキテクチャ](docs/glossary/architecture.ja.md)」ではなく「[設定](docs/glossary/complexity-of-configuration.ja.md)と通信の複雑性」。認証系は[レイテンシ](docs/glossary/latency.ja.md)に敏感で障害が全体に波及する。[ネットワークホップ](docs/glossary/network-hop.ja.md)を減らし、[デバッグ](docs/glossary/debugging.ja.md)を 1 箇所で完結させる
- **[ForwardAuth](docs/glossary/forwardauth.ja.md) パターン** — Proxy は[リクエストボディ](docs/glossary/request-body.ja.md)を中継しない。認証チェックだけ [📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid)
- **[App](docs/glossary/downstream-app.ja.md) のやることは 2 つだけ** — [ヘッダ](docs/glossary/header.ja.md)を読む or [API](docs/glossary/api.ja.md) を叩く
- **[Phase](docs/glossary/phase-based-development.ja.md) ごとの[最小構成](docs/glossary/minimum-viable-architecture.ja.md)** — 今必要なものだけ作り、[Interface](docs/glossary/interface-extension-point.ja.md) で[拡張点](docs/glossary/interface-extension-point.ja.md)を残す。[App](docs/glossary/downstream-app.ja.md) 固有のロジックを proxy に入れない

***

## 機能一覧

### 認証

| 機能 | 詳細 |
|------|------|
| Google [OIDC](docs/glossary/oidc.ja.md) | 直接連携。[PKCE](docs/glossary/pkce.ja.md) + [state](docs/glossary/state.ja.md) + [nonce](docs/glossary/nonce.ja.md)。中間 [IdP](docs/glossary/idp.ja.md) 不要 |
| [セッション](docs/glossary/session.ja.md)管理 | 署名付き [Cookie](docs/glossary/cookie.ja.md)。8 時間[スライディング](docs/glossary/sliding-window-expiry.ja.md)。ユーザーあたり最大 5 同時セッション |
| [JWT](docs/glossary/jwt.ja.md) 発行 | [RS256](docs/glossary/rs256.ja.md) 自前署名。5 分有効期限。初回起動時に[自動鍵生成](docs/glossary/auto-key-generation.ja.md) |
| [JWKS](docs/glossary/jwks.ja.md) [エンドポイント](docs/glossary/endpoint.ja.md) | `/.well-known/jwks.json`。active + rotated の鍵を配信 |
| [鍵ローテーション](docs/glossary/key-rotation.ja.md) | 管理 [API](docs/glossary/api.ja.md) で回転/失効。オーバーラップ期間付きの安全な移行 |
| [サイレントリフレッシュ](docs/glossary/silent-refresh.ja.md) | volta-[sdk](docs/glossary/sdk.md)-js が [HTTPステータスコード](docs/glossary/http-status-codes.ja.md) 401 で自動リフレッシュ。通常利用中に[ログイン](docs/glossary/login.ja.md)画面を見ない (→ [フロー図](dge/specs/ui-flow.md#flow-5-session-expired---silent-refresh)) |
| [ログアウト](docs/glossary/logout.ja.md) | 単一デバイス・全デバイス対応。[JWT](docs/glossary/jwt.ja.md) 有効期限（最大 5 分）で伝播 (→ [フロー図](dge/specs/ui-flow.md#flow-6-logout)) |

### マルチテナント

| 機能 | 詳細 |
|------|------|
| [テナント](docs/glossary/tenant.ja.md)解決 | 4 段階優先: [セッション](docs/glossary/session.ja.md) > [サブドメイン](docs/glossary/subdomain.ja.md) > メール[ドメイン](docs/glossary/domain.ja.md) > [招待](docs/glossary/invitation-flow.ja.md)/手動 (→ [フロー図](dge/specs/ui-flow.md#flow-3-tenant-selection)) |
| フリーメール除外 | gmail.com, outlook.com 等は自動でドメインマッチング対象外 |
| 複数[テナント](docs/glossary/tenant.ja.md)所属 | 1 ユーザーが複数[テナント](docs/glossary/tenant.ja.md)に異なる[ロール](docs/glossary/role.ja.md)で所属可能 |
| [テナント](docs/glossary/tenant.ja.md)切替 | [セッション](docs/glossary/session.ja.md)中に [API](docs/glossary/api.ja.md) で切替。ページリロードでクリーンな状態に (→ [フロー図](dge/specs/ui-flow.md#flow-4-tenant-switch-during-session)) |
| [テナント](docs/glossary/tenant.ja.md)停止 | 停止[テナント](docs/glossary/tenant.ja.md)の全メンバーアクセスをブロック。他テナントは維持 |
| [テナント](docs/glossary/tenant.ja.md)分離 | [API](docs/glossary/api.ja.md) パスの [tenant](docs/glossary/tenant.md)Id と [JWT](docs/glossary/jwt.ja.md) [claim](docs/glossary/claim.md) の一致を構造的に強制。[クロステナントアクセス](docs/glossary/cross-tenant-access.ja.md)不可 |

### 招待システム

| 機能 | 詳細 |
|------|------|
| [招待](docs/glossary/invitation-flow.ja.md)コード | [暗号](docs/glossary/encryption.ja.md)学的ランダム 32 バイト（[base64](docs/glossary/base64.md)[url](docs/glossary/url.md), 43 文字）。推測不可能 |
| 有効期限 | [招待](docs/glossary/invitation-flow.ja.md)ごとに設定可能。デフォルト 72 時間 |
| 使用回数制限 | 1 回限り or 複数回（上限設定可能） |
| メール制限 | オプション: 特定メールアドレスのみに限定 |
| [同意画面](docs/glossary/consent-screen.ja.md) | 「このワークスペースに参加しますか？」の明示的確認 (→ [フロー図](dge/specs/ui-flow.md#flow-1-invite-link---first-login)) |
| 状態管理 | 未使用 / 使用済み / 期限切れ。管理者が確認可能 |
| リンク共有 | コピーボタン + [QR コード](docs/glossary/qr-code.ja.md)（[Phase](docs/glossary/phase-based-development.ja.md) 1 はメール送信なし） |

### ロールベースアクセス制御

| 機能 | 詳細 |
|------|------|
| 4 段階階層 | OWNER > ADMIN > MEMBER > VIEWER |
| [App](docs/glossary/downstream-app.ja.md) 別制御 | volta-config.[yaml](docs/glossary/yaml.md) で [App](docs/glossary/downstream-app.ja.md) ごとに許可[ロール](docs/glossary/role.ja.md)を定義。[ForwardAuth](docs/glossary/forwardauth.ja.md) ([📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid)) で強制 |
| [テナント](docs/glossary/tenant.ja.md)[スコープ](docs/glossary/scopes.ja.md) | [ロール](docs/glossary/role.ja.md)は[テナント](docs/glossary/tenant.ja.md)ごと。テナント A では ADMIN、テナント B では VIEWER が可能 |
| OWNER 保護 | 最後の OWNER は降格・削除不可 |
| [ロール](docs/glossary/role.ja.md)管理 UI | 管理画面でメンバーの[ロール](docs/glossary/role.ja.md)変更。確認ダイアログ付き |

### セキュリティ

| 機能 | 実装 |
|------|------|
| [OIDC](docs/glossary/oidc.ja.md) | [state](docs/glossary/state.ja.md) ([CSRF](docs/glossary/csrf.ja.md)) + [nonce](docs/glossary/nonce.ja.md) ([リプレイ攻撃](docs/glossary/replay-attack.ja.md)防止) + [PKCE](docs/glossary/pkce.ja.md) (S256) |
| [JWT](docs/glossary/jwt.ja.md) 署名 | [RS256](docs/glossary/rs256.ja.md) のみ。[HS256](docs/glossary/hs256.ja.md)/none 拒否。alg [ホワイトリスト](docs/glossary/whitelist.ja.md) |
| 鍵の[暗号化](docs/glossary/encryption-at-rest.ja.md) | 秘密鍵は DB に AES-256-GCM で[暗号化](docs/glossary/encryption.ja.md)保存 |
| [CSRF](docs/glossary/csrf.ja.md) 保護 | [HTML](docs/glossary/html.ja.md) フォームは[トークン](docs/glossary/token.ja.md)ベース。[JSON](docs/glossary/json.ja.md) [API](docs/glossary/api.ja.md) は [SameSite](docs/glossary/samesite.ja.md) + [Content-Type](docs/glossary/content-type.ja.md) で保護 |
| [レート制限](docs/glossary/rate-limiting.ja.md) | [ログイン](docs/glossary/login.ja.md): 10/分/IP。[API](docs/glossary/api.ja.md): 200/分/ユーザー |
| [セッション固定攻撃](docs/glossary/session-fixation.ja.md) | ログイン成功時に[セッション](docs/glossary/session.ja.md) ID [再生成](docs/glossary/regenerate.ja.md) |
| [Content Negotiation](docs/glossary/content-negotiation.md) | [JSON](docs/glossary/json.ja.md) リクエストには 302 を返さない。[SPA](docs/glossary/spa.md) の fetch 混乱を防止 |
| 監査ログ | 全認証イベント: ログイン、[ログアウト](docs/glossary/logout.ja.md)、[ロール](docs/glossary/role.ja.md)変更、[招待](docs/glossary/invitation-flow.ja.md)、[セッション](docs/glossary/session.ja.md)失効 |
| [Cache-Control](docs/glossary/cache-control.ja.md) | 認証[エンドポイント](docs/glossary/endpoint.ja.md)に `no-store, private`。戻るボタンでのデータ漏洩防止 |

### 開発者体験

| 機能 | 詳細 |
|------|------|
| [ForwardAuth](docs/glossary/forwardauth.ja.md) | [App](docs/glossary/downstream-app.ja.md) は [HTTP](docs/glossary/http.ja.md) [ヘッダ](docs/glossary/header.ja.md)から identity を取得。認証コードゼロ [📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid) |
| [Internal API](docs/glossary/internal-api.ja.md) | ユーザー/[テナント](docs/glossary/tenant.ja.md)/メンバー [CRUD](docs/glossary/crud.ja.md) の REST [API](docs/glossary/api.ja.md) |
| volta-[sdk](docs/glossary/sdk.md)-js | [ブラウザ](docs/glossary/browser.ja.md) [SDK](docs/glossary/sdk.ja.md) (~150 行)。401 自動リフレッシュ、[テナント](docs/glossary/tenant.ja.md)切替、[ログアウト](docs/glossary/logout.ja.md) |
| volta-[sdk](docs/glossary/sdk.md) ([Java](docs/glossary/java.ja.md)) | [Javalin](docs/glossary/javalin.md) [ミドルウェア](docs/glossary/middleware.ja.md)で [JWT](docs/glossary/jwt.ja.md) [検証](docs/glossary/verification.ja.md) |
| 開発モード | `POST /dev/token` でテスト用 [JWT](docs/glossary/jwt.ja.md) 生成（本番では無効） |
| [ヘルスチェック](docs/glossary/health-check.ja.md) | `GET /healthz` |
| 高速起動 | ~200ms。ローカル開発サイクルが瞬時 |
| 最小依存 | Gateway + [Postgres](docs/glossary/database.ja.md)。以上 |

### 管理 UI

| 機能 | 詳細 |
|------|------|
| メンバー管理 | 一覧、[ロール](docs/glossary/role.ja.md)変更、削除。[テナント](docs/glossary/tenant.ja.md)ごと |
| [招待](docs/glossary/invitation-flow.ja.md)管理 | 作成、一覧、取消。リンクコピー、[QR コード](docs/glossary/qr-code.ja.md) [📊](dge/specs/ui-flow.md#flow-7-invitation-management---admin) |
| [セッション](docs/glossary/session.ja.md)管理 | 全アクティブ[セッション](docs/glossary/session.ja.md)表示、個別/一括失効 [📊](dge/specs/ui-flow.md#flow-9-session-management---user) |

***

## インストール

### 方法 1: ローカル開発（最初はこれ）

```bash
# クローン
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy

# Postgres 起動
docker compose up -d postgres

# 環境変数設定
cp .env.example .env
# .env を編集:
#   GOOGLE_CLIENT_ID=your-google-client-id
#   GOOGLE_CLIENT_SECRET=your-google-client-secret
#   JWT_KEY_ENCRYPTION_SECRET=ランダムな32バイト文字列
#   VOLTA_SERVICE_TOKEN=ランダムな64バイト文字列

# ビルド & 実行
mvn compile exec:java

# 確認
curl http://localhost:7070/healthz
# {"status":"ok"}
```

### 方法 2: [Docker Compose](docs/glossary/docker-compose.ja.md)（フルスタック）

```bash
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy
cp .env.example .env
# .env を編集（上と同じ）

docker compose up -d

curl http://localhost:7070/healthz
```

### 方法 3: 本番デプロイ

```bash
# fat JAR ビルド
mvn package -DskipTests

# 実行
java -jar target/volta-auth-proxy-0.1.0-SNAPSHOT.jar

# 環境変数は .env.example 参照
# Postgres がアクセス可能であること
# Flyway が起動時にマイグレーション自動実行
```

### Google OAuth 設定

1. [Google Cloud Console](https://console.cloud.google.com/) を開く
2. プロジェクトを作成 or 選択
3. [API](docs/glossary/api.md)s & Services > [Credentials](docs/glossary/credentials.md) に移動
4. [OAuth 2.0](docs/glossary/oauth2.ja.md) [クライアント](docs/glossary/client.ja.md) ID を作成（ウェブアプリケーション）
5. 承認済み[リダイレクト](docs/glossary/redirect.ja.md) [URI](docs/glossary/url.ja.md) に `http://localhost:7070/callback` を追加
6. [Client](docs/glossary/client.md) ID と [Client](docs/glossary/client.md) Secret を [`.env`](docs/glossary/environment-variable.ja.md) にコピー

### 必要環境

| 要件 | バージョン | 備考 |
|------|-----------|------|
| [Java](docs/glossary/java.ja.md) | 21+ | [LTS](docs/glossary/lts.md) 推奨 |
| [Maven](docs/glossary/maven.ja.md) | 3.9+ | [ビルド](docs/glossary/build.ja.md)ツール |
| [Postgres](docs/glossary/database.ja.md) | 16+ | [Docker](docs/glossary/docker.ja.md) or ローカルインストール |
| [Docker](docs/glossary/docker.ja.md) | 24+ | [Postgres](docs/glossary/database.ja.md) 用（ローカル Postgres があれば不要） |

***

## 使い方

### プラットフォーム運用者として

#### 1. 最初の[テナント](docs/glossary/tenant.ja.md)を作成

```bash
# DEV_MODE=true でテスト用トークンを使ってブートストラップ:
curl -X POST http://localhost:7070/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"userId":"admin-001","tenantId":"tenant-001","roles":["OWNER"]}'
```

#### 2. チームメンバーを[招待](docs/glossary/invitation-flow.ja.md)

`http://localhost:7070/admin/invitations` を開いて[招待](docs/glossary/invitation-flow.ja.md)を作成。
リンクをコピーして [Sla](docs/glossary/sla.md)ck やメールで共有。

#### 3. [App](docs/glossary/downstream-app.ja.md) を登録

`volta-config.yaml` を編集:

```yaml
apps:
  - id: my-wiki
    url: https://wiki.mycompany.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

[Traefik](docs/glossary/reverse-proxy.ja.md) の [ForwardAuth](docs/glossary/forwardauth.ja.md) [ミドルウェア](docs/glossary/middleware.ja.md)を設定（[アーキテクチャ](docs/glossary/architecture.ja.md)セクション参照）。

### [App](docs/glossary/downstream-app.ja.md) 開発者として

#### 最小連携（[ヘッダ](docs/glossary/header.ja.md)のみ）

```java
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

#### 完全連携（[JWT](docs/glossary/jwt.ja.md) 検証 + [SDK](docs/glossary/sdk.ja.md)）

```java
// サーバー側
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());

app.get("/api/data", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    if (!user.hasRole("ADMIN")) throw new ForbiddenResponse();
    // tenant-scoped query
});
```

```html
<!-- クライアント側 -->
<script src="http://volta-auth-proxy:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://volta-auth-proxy:7070" });

  // 401 自動回復付き fetch
  const res = await Volta.fetch("/api/data");

  // フォーム送信（セッション切れでも自動復帰）
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    await Volta.fetch("/api/data", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(Object.fromEntries(new FormData(e.target)))
    });
  });

  // テナント切替
  await Volta.switchTenant("other-tenant-id");
</script>
```

***

## [Phase](docs/glossary/phase-based-development.ja.md) ロードマップ

| [Phase](docs/glossary/phase-based-development.ja.md) | 状態 | 内容 |
|-------|------|------|
| **[Phase](docs/glossary/phase-based-development.ja.md) 1: Core** | **実装済み** | Google [OIDC](docs/glossary/oidc.ja.md), [テナント](docs/glossary/tenant.ja.md), [ロール](docs/glossary/role.ja.md), [招待](docs/glossary/invitation-flow.ja.md), [ForwardAuth](docs/glossary/forwardauth.ja.md), [Internal API](docs/glossary/internal-api.ja.md) |
| **[Phase](docs/glossary/phase-based-development.ja.md) 2: Scale** | **実装済み** | 複数 [IdP](docs/glossary/idp.ja.md) ([Git](docs/glossary/git.md)Hub, Microsoft), [M2M](docs/glossary/m2m.md) ([Client Credentials](docs/glossary/client-credentials.ja.md)), [Redis](docs/glossary/redis.md) [セッション](docs/glossary/session.ja.md), [Webhook](docs/glossary/webhook.md) |
| **[Phase](docs/glossary/phase-based-development.ja.md) 3: Enterprise** | **一部** | [SAML](docs/glossary/sso.ja.md) ✅, メール通知 ✅, **[MFA](docs/glossary/mfa.ja.md)/2FA ([TOTP](docs/glossary/totp.ja.md))** ✅ — i18n ❌, **条件付きアクセス** ❌, **不正検知** ❌ |
| **[Phase](docs/glossary/phase-based-development.ja.md) 4: Platform** | **一部** | [SCIM](docs/glossary/scim.ja.md) ✅, [Billing](docs/glossary/billing.md) ([Stripe](docs/glossary/stripe.md) [webhook](docs/glossary/webhook.md)) ✅ — [Policy Engine](docs/glossary/policy-engine.md) ❌, GDPR データ削除 ❌, **デバイストラスト** ❌, **モバイル [SDK](docs/glossary/sdk.md)** ❌ |

### 認証トレンドへの対応計画

| トレンド | [Phase](docs/glossary/phase-based-development.ja.md) | 方針 |
|---------|-------|------|
| **パスキー ([WebAuthn](docs/glossary/webauthn.ja.md)/FIDO2)** | [Phase](docs/glossary/phase-based-development.ja.md) 2 | パスワードレス認証の主流。Google [ログイン](docs/glossary/login.ja.md)と並ぶ第 2 の認証手段として追加 |
| **[MFA](docs/glossary/mfa.ja.md)/2FA ([TOTP](docs/glossary/totp.ja.md))** | [Phase](docs/glossary/phase-based-development.ja.md) 3 | Google Authenticator 等の [TOTP](docs/glossary/totp.ja.md)。[テナント](docs/glossary/tenant.ja.md)管理者が「[MFA](docs/glossary/mfa.md) 必須」を設定可能に |
| **リスクベース認証** | [Phase](docs/glossary/phase-based-development.ja.md) 3 | 新しいデバイス/IP からのアクセス時に追加認証。`amr` [claim](docs/glossary/claim.md) で認証強度を [JWT](docs/glossary/jwt.ja.md) に反映 |
| **不正検知/アラート (Fraud Alert)** | [Phase](docs/glossary/phase-based-development.ja.md) 3 | 不審なログイン検知（あり得ない移動距離、[クレデンシャルスタッフィング](docs/glossary/credential-stuffing.ja.md)）。管理者に [Webhook](docs/glossary/webhook.md) アラート。脅威インテリジェンスフィードとの連携 |
| **デバイストラスト** | [Phase](docs/glossary/phase-based-development.ja.md) 4 | 既知のデバイスを記憶。未知デバイスからのアクセスに追加[検証](docs/glossary/verification.ja.md) |
| **モバイル [SDK](docs/glossary/sdk.ja.md) (iOS/Android)** | [Phase](docs/glossary/phase-based-development.ja.md) 4 | ネイティブ iOS/Android [SDK](docs/glossary/sdk.ja.md)。[招待](docs/glossary/invitation-flow.ja.md)フローのディープリンク対応。生体認証連携 |
| **[SAML](docs/glossary/sso.ja.md) [SSO](docs/glossary/sso.ja.md)** | [Phase](docs/glossary/phase-based-development.ja.md) 3 | エンタープライズ顧客の社内 [IdP](docs/glossary/idp.ja.md) ([Active Directory](docs/glossary/active-directory.md) 等) と連携 |
| **[SCIM](docs/glossary/scim.ja.md)** | [Phase](docs/glossary/phase-based-development.ja.md) 4 | エンタープライズ顧客のユーザー自動同期 ([Okta](docs/glossary/okta.md), Azure AD 等) |

全仕様: [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md)

***

## 技術仕様

### JWT 仕様

```
アルゴリズム: RS256 (RSA 2048-bit)
有効期限:    5 分
鍵管理:     signing_keys テーブル (AES-256-GCM 暗号化)
JWKS:       GET /.well-known/jwks.json
```

**[Claims](docs/glossary/jwt-payload.ja.md):**

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "user-uuid",
  "exp": 1711900000,
  "iat": 1711899700,
  "jti": "uuid",
  "volta_v": 1,
  "volta_tid": "tenant-uuid",
  "volta_tname": "ACME Corp",
  "volta_tslug": "acme",
  "volta_roles": ["ADMIN"],
  "volta_display": "Taro Yamada"
}
```

### テナント解決 [📊 フロー図](dge/specs/ui-flow.md#flow-3-tenant-selection)

優先順位:

1. [セッション](docs/glossary/session.ja.md) [Cookie](docs/glossary/cookie.ja.md) の `tenant_id` → そのまま使用
2. [URL](docs/glossary/url.ja.md) [サブドメイン](docs/glossary/subdomain.ja.md) → `tenant_domains` テーブル検索
3. メール[ドメイン](docs/glossary/domain.ja.md) → `tenant_domains` テーブル検索（フリーメール除外）
4. 該当なし → [招待](docs/glossary/invitation-flow.ja.md)コード入力 or [テナント](docs/glossary/tenant.ja.md)選択画面 (→ [フロー図](dge/specs/ui-flow.md#flow-1-invite-link---first-login))

### [ロール](docs/glossary/role.ja.md)階層

```
OWNER > ADMIN > MEMBER > VIEWER
```

| [ロール](docs/glossary/role.ja.md) | 権限 |
|--------|------|
| OWNER | [テナント](docs/glossary/tenant.ja.md)削除、OWNER 譲渡、全 ADMIN 権限 |
| ADMIN | メンバー[招待](docs/glossary/invitation-flow.ja.md)/削除、[ロール](docs/glossary/role.ja.md)変更（ADMIN 以下）、[テナント](docs/glossary/tenant.ja.md)設定変更 |
| MEMBER | 通常利用 |
| VIEWER | 読み取り専用（[App](docs/glossary/downstream-app.ja.md) 側で制御） |

### Content Negotiation

```
Accept: application/json            → 必ず JSON レスポンス（302 禁止）
Accept: text/html                   → HTML or リダイレクト
X-Requested-With: XMLHttpRequest    → JSON 扱い
Authorization: Bearer ...           → JSON 扱い
```

### [環境変数](docs/glossary/environment-variable.ja.md)

| [変数](docs/glossary/variable.ja.md) | デフォルト | 説明 |
|------|-----------|------|
| `PORT` | 7070 | [サーバー](docs/glossary/server.ja.md)[ポート](docs/glossary/port.ja.md) |
| `DB_HOST` | [localhost](docs/glossary/localhost.ja.md) | [Postgres](docs/glossary/database.ja.md) ホスト |
| `DB_PORT` | 54329 | [Postgres](docs/glossary/database.ja.md) [ポート](docs/glossary/port.ja.md) |
| `DB_NAME` | volta\_auth | [データベース](docs/glossary/database.ja.md)名 |
| `DB_USER` | volta | DB ユーザー |
| `DB_PASSWORD` | volta | DB パスワード |
| `BASE_URL` | [http](docs/glossary/http.md)://[localhost](docs/glossary/localhost.md):7070 | 公開 [URL](docs/glossary/url.ja.md) |
| `GOOGLE_CLIENT_ID` | | Google [OAuth](docs/glossary/oauth2.ja.md) [クライアント](docs/glossary/client.ja.md) ID |
| `GOOGLE_CLIENT_SECRET` | | Google [OAuth](docs/glossary/oauth2.ja.md) クライアントシークレット |
| `GOOGLE_REDIRECT_URI` | [http](docs/glossary/http.md)://[localhost](docs/glossary/localhost.md):7070/callback | [オープンリダイレクト](docs/glossary/open-redirect.ja.md)対策済み [OAuth](docs/glossary/oauth2.ja.md) [リダイレクト](docs/glossary/redirect.ja.md) [URI](docs/glossary/url.ja.md) |
| `JWT_ISSUER` | volta-auth | [JWT](docs/glossary/jwt.ja.md) issuer [claim](docs/glossary/claim.md) |
| `JWT_AUDIENCE` | volta-apps | [JWT](docs/glossary/jwt.ja.md) audience [claim](docs/glossary/claim.md) |
| `JWT_TTL_SECONDS` | 300 | [JWT](docs/glossary/jwt.ja.md) 有効期限（5 分） |
| `JWT_KEY_ENCRYPTION_SECRET` | | [署名鍵](docs/glossary/signing-key.ja.md)の[暗号化](docs/glossary/encryption.ja.md)用 AES-256 鍵 |
| `SESSION_TTL_SECONDS` | 28800 | [セッション](docs/glossary/session.ja.md)有効期限（8 時間） |
| `ALLOWED_REDIRECT_DOMAINS` | [localhost](docs/glossary/localhost.ja.md),127.0.0.1 | return\_to の[ホワイトリスト](docs/glossary/whitelist.ja.md)（[オープンリダイレクト](docs/glossary/open-redirect.ja.md)防止） |
| `VOLTA_SERVICE_TOKEN` | | [M2M](docs/glossary/m2m.md) 用静的サービス[トークン](docs/glossary/token.ja.md)（[Phase](docs/glossary/phase-based-development.ja.md) 1） |
| `DEV_MODE` | false | /dev/[token](docs/glossary/token.md) [エンドポイント](docs/glossary/endpoint.ja.md)の有効化 |
| `APP_CONFIG_PATH` | volta-config.[yaml](docs/glossary/yaml.md) | [App](docs/glossary/downstream-app.ja.md) 登録ファイルのパス |
| `SUPPORT_CONTACT` | | エラーページに表示する管理者連絡先 |

***

## 設計ドキュメント

このプロジェクトは DGE（Dialogue-driven Gap Extraction）で設計されました。8 セッションで 106 の設計ギャップを発見し、全て解決しています。

| ドキュメント | 説明 |
|-------------|------|
| [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md) | 全 [Phase](docs/glossary/phase-based-development.ja.md) 実装仕様 |
| [`dge/specs/ux-specs-phase1.md`](dge/specs/ux-specs-phase1.md) | UI/UX 仕様 |
| [`dge/specs/ui-flow.md`](dge/specs/ui-flow.md) | 画面遷移図（[mermaid](docs/glossary/mermaid.md)）-- [📊 ユーザー状態](dge/specs/ui-flow.md#user-state-model), [ForwardAuth](dge/specs/ui-flow.md#flow-2-returning-user---session-valid), [招待](dge/specs/ui-flow.md#flow-1-invite-link---first-login), [テナント選択](dge/specs/ui-flow.md#flow-3-tenant-selection), [ログアウト](dge/specs/ui-flow.md#flow-6-logout), [エラー回復](dge/specs/ui-flow.md#error-recovery-flow) |
| [`docs/getting-started-dialogue.ja.md`](docs/getting-started-dialogue.ja.md) | **はじめよう: volta エンジニアと App 開発者の会話** |
| [`docs/llm-integration-guide.ja.md`](docs/llm-integration-guide.ja.md) | **AI アシスタント向け: エンジニアの volta 統合を支援するガイド** |
| [`docs/no-traefik-guide.ja.md`](docs/no-traefik-guide.ja.md) | [Traefik](docs/glossary/traefik.md) なしで動かす: 3 パターン（プロキシなし, [nginx](docs/glossary/nginx.md), [Caddy](docs/glossary/caddy.md)） |
| [`docs/target-audience.ja.md`](docs/target-audience.ja.md) | ターゲット層、マーケットポジション、収益機会 |
| [`docs/dsl-overview.ja.md`](docs/dsl-overview.ja.md) | [DSL](docs/glossary/dsl.md) 仕様、状態マシン、ポリシーエンジンドライバー戦略 |
| [`docs/dsl-validator-spec.md`](docs/dsl-validator-spec.md) | [DSL](docs/glossary/dsl.md) バリデーター（60+ チェック） |
| [`dge/feedback/2026-03-31-volta-auth-proxy.md`](dge/feedback/2026-03-31-volta-auth-proxy.md) | DGE method フィードバック |
| [`tasks/001-fix-critical-bugs-and-implement-templates.md`](tasks/001-fix-critical-bugs-and-implement-templates.md) | 現在の実装タスク |
| [`backlog/001-form-state-recovery.md`](backlog/001-form-state-recovery.md) | [Phase](docs/glossary/phase-based-development.ja.md) 2: フォーム自動保存 |

***

## ライセンス

TBD
