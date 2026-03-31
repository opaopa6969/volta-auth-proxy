# volta-auth-proxy

[English](README.md) | [Japanese (日本語)](README.ja.md)

マルチテナント SaaS 向け Identity Gateway。
認証・テナント管理・ロール・招待を一手に引き受け、下流の App は何もしなくてよくなります。

**Keycloak 不要。oauth2-proxy 不要。制御こそ正義。**

---

## なぜ volta-auth-proxy？

SaaS を作る。認証が必要。選択肢は:

| 選択肢 | 結果 |
|--------|------|
| Auth0 / Clerk | すぐ動く。MAU 10万で月 $2,400。ベンダーロックイン。セルフホスト不可 |
| Keycloak | 無料。realm.json 500 行の設定地獄。メモリ 512MB。起動 30 秒。FreeMarker テーマ |
| 自前で全部作る | 完全な制御。ただし OIDC、JWT、セッション、CSRF、テナント分離を全部正しくやる必要あり |

**volta-auth-proxy は選択肢 3 を正しくやったもの。** 難しい部分（OIDC フロー、JWT 署名、セッション管理、テナント解決）は実装済み。App はヘッダを読むだけ。

### 競合比較

| | volta-auth-proxy | Keycloak | Auth0 | ZITADEL | Ory Stack |
|---|---|---|---|---|---|
| **セルフホスト** | はい（のみ） | はい | 不可 | はい | はい |
| **起動** | ~200ms | ~30秒 | N/A | ~3-5秒 | ~数秒 |
| **メモリ** | ~30MB | ~512MB+ | N/A | ~150-300MB | ~200-400MB |
| **マルチテナント** | 設計の中核 | レルム制限あり | Organizations（有料） | ネイティブ | 自前実装 |
| **ログイン UI** | 完全制御 (jte) | テーマ地獄 | 制限あり | テーマ | 自前 |
| **10万 MAU コスト** | $0 | $0（運用コスト別） | ~$2,400/月 | $0（セルフホスト） | $0 |
| **App 連携** | ForwardAuth + Internal API | 汎用 OIDC | SDK | 汎用 OIDC | Oathkeeper |
| **設定の複雑さ** | .env + YAML 1 個 | 数百の設定項目 | ダッシュボード | 中程度 | 4 サービス |
| **外部依存** | Postgres のみ | Postgres + JVM | クラウド | Postgres/CRDB | Postgres + 複数 |

volta は最も軽量で、最も制御しやすい選択肢。トレードオフ: セキュリティの責任を自分で負う。

---

## 設計哲学

- **制御しやすいは正義** — 外部サーバーへの依存を最小化
- **理解できる地獄を選ぶ** — Keycloak の設定地獄も、OIDC 自前実装の地獄も、どちらも地獄。ならスタックトレースが読める方を選ぶ。Auth だけは自分で持つ。何が起きているか分からないシステムにユーザーの認証を預けない
- **密結合上等** — 1 プロセスで完結。マイクロサービス的な疎結合がもたらすのは「正しいアーキテクチャ」ではなく「設定と通信の複雑性」。認証系はレイテンシに敏感で障害が全体に波及する。ネットワークホップを減らし、デバッグを 1 箇所で完結させる
- **ForwardAuth パターン** — Proxy はリクエストボディを中継しない。認証チェックだけ
- **App のやることは 2 つだけ** — ヘッダを読む or API を叩く
- **Phase ごとの最小構成** — 今必要なものだけ作り、Interface で拡張点を残す。App 固有のロジックを proxy に入れない

---

## 機能一覧

### 認証

| 機能 | 詳細 |
|------|------|
| Google OIDC | 直接連携。PKCE + state + nonce。中間 IdP 不要 |
| セッション管理 | 署名付き Cookie。8 時間スライディング。ユーザーあたり最大 5 同時セッション |
| JWT 発行 | RS256 自前署名。5 分有効期限。初回起動時に自動鍵生成 |
| JWKS エンドポイント | `/.well-known/jwks.json`。active + rotated の鍵を配信 |
| 鍵ローテーション | 管理 API で回転/失効。オーバーラップ期間付きの安全な移行 |
| サイレントリフレッシュ | volta-sdk-js が 401 で自動リフレッシュ。通常利用中にログイン画面を見ない |
| ログアウト | 単一デバイス・全デバイス対応。JWT 有効期限（最大 5 分）で伝播 |

### マルチテナント

| 機能 | 詳細 |
|------|------|
| テナント解決 | 4 段階優先: セッション > サブドメイン > メールドメイン > 招待/手動 |
| フリーメール除外 | gmail.com, outlook.com 等は自動でドメインマッチング対象外 |
| 複数テナント所属 | 1 ユーザーが複数テナントに異なるロールで所属可能 |
| テナント切替 | セッション中に API で切替。ページリロードでクリーンな状態に |
| テナント停止 | 停止テナントの全メンバーアクセスをブロック。他テナントは維持 |
| テナント分離 | API パスの tenantId と JWT claim の一致を構造的に強制。クロステナントアクセス不可 |

### 招待システム

| 機能 | 詳細 |
|------|------|
| 招待コード | 暗号学的ランダム 32 バイト（base64url, 43 文字）。推測不可能 |
| 有効期限 | 招待ごとに設定可能。デフォルト 72 時間 |
| 使用回数制限 | 1 回限り or 複数回（上限設定可能） |
| メール制限 | オプション: 特定メールアドレスのみに限定 |
| 同意画面 | 「このワークスペースに参加しますか？」の明示的確認 |
| 状態管理 | 未使用 / 使用済み / 期限切れ。管理者が確認可能 |
| リンク共有 | コピーボタン + QR コード（Phase 1 はメール送信なし） |

### ロールベースアクセス制御

| 機能 | 詳細 |
|------|------|
| 4 段階階層 | OWNER > ADMIN > MEMBER > VIEWER |
| App 別制御 | volta-config.yaml で App ごとに許可ロールを定義。ForwardAuth で強制 |
| テナントスコープ | ロールはテナントごと。テナント A では ADMIN、テナント B では VIEWER が可能 |
| OWNER 保護 | 最後の OWNER は降格・削除不可 |
| ロール管理 UI | 管理画面でメンバーのロール変更。確認ダイアログ付き |

### セキュリティ

| 機能 | 実装 |
|------|------|
| OIDC | state (CSRF) + nonce (リプレイ防止) + PKCE (S256) |
| JWT 署名 | RS256 のみ。HS256/none 拒否。alg ホワイトリスト |
| 鍵の暗号化 | 秘密鍵は DB に AES-256-GCM で暗号化保存 |
| CSRF 保護 | HTML フォームはトークンベース。JSON API は SameSite + Content-Type で保護 |
| レート制限 | ログイン: 10/分/IP。API: 200/分/ユーザー |
| セッション固定攻撃 | ログイン成功時にセッション ID 再生成 |
| Content Negotiation | JSON リクエストには 302 を返さない。SPA の fetch 混乱を防止 |
| 監査ログ | 全認証イベント: ログイン、ログアウト、ロール変更、招待、セッション失効 |
| Cache-Control | 認証エンドポイントに `no-store, private`。戻るボタンでのデータ漏洩防止 |

### 開発者体験

| 機能 | 詳細 |
|------|------|
| ForwardAuth | App は HTTP ヘッダから identity を取得。認証コードゼロ |
| Internal API | ユーザー/テナント/メンバー CRUD の REST API |
| volta-sdk-js | ブラウザ SDK (~150 行)。401 自動リフレッシュ、テナント切替、ログアウト |
| volta-sdk (Java) | Javalin ミドルウェアで JWT 検証 |
| 開発モード | `POST /dev/token` でテスト用 JWT 生成（本番では無効） |
| ヘルスチェック | `GET /healthz` |
| 高速起動 | ~200ms。ローカル開発サイクルが瞬時 |
| 最小依存 | Gateway + Postgres。以上 |

### 管理 UI

| 機能 | 詳細 |
|------|------|
| メンバー管理 | 一覧、ロール変更、削除。テナントごと |
| 招待管理 | 作成、一覧、取消。リンクコピー、QR コード |
| セッション管理 | 全アクティブセッション表示、個別/一括失効 |

---

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

### 方法 2: Docker Compose（フルスタック）

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
3. APIs & Services > Credentials に移動
4. OAuth 2.0 クライアント ID を作成（ウェブアプリケーション）
5. 承認済みリダイレクト URI に `http://localhost:7070/callback` を追加
6. Client ID と Client Secret を `.env` にコピー

### 必要環境

| 要件 | バージョン | 備考 |
|------|-----------|------|
| Java | 21+ | LTS 推奨 |
| Maven | 3.9+ | ビルドツール |
| Postgres | 16+ | Docker or ローカルインストール |
| Docker | 24+ | Postgres 用（ローカル Postgres があれば不要） |

---

## 使い方

### プラットフォーム運用者として

#### 1. 最初のテナントを作成

```bash
# DEV_MODE=true でテスト用トークンを使ってブートストラップ:
curl -X POST http://localhost:7070/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"userId":"admin-001","tenantId":"tenant-001","roles":["OWNER"]}'
```

#### 2. チームメンバーを招待

`http://localhost:7070/admin/invitations` を開いて招待を作成。
リンクをコピーして Slack やメールで共有。

#### 3. App を登録

`volta-config.yaml` を編集:

```yaml
apps:
  - id: my-wiki
    url: https://wiki.mycompany.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

Traefik の ForwardAuth ミドルウェアを設定（アーキテクチャセクション参照）。

### App 開発者として

#### 最小連携（ヘッダのみ）

```java
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

#### 完全連携（JWT 検証 + SDK）

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

---

## Phase ロードマップ

| Phase | 状態 | 内容 |
|-------|------|------|
| **Phase 1: Core** | 開発中 | Google OIDC, テナント, ロール, 招待, ForwardAuth, Internal API |
| Phase 2: Scale | 計画中 | 複数 IdP (GitHub, Microsoft), M2M (Client Credentials), Redis セッション, Webhook, **パスキー (WebAuthn/FIDO2)** |
| Phase 3: Enterprise | 計画中 | SAML, メール通知, **MFA/2FA (TOTP, WebAuthn)**, i18n, 管理 UI 拡張, **条件付きアクセス (リスクベース認証)** |
| Phase 4: Platform | 計画中 | SCIM, Policy Engine, Billing (Stripe), GDPR データエクスポート/削除, **デバイストラスト** |

### 認証トレンドへの対応計画

| トレンド | Phase | 方針 |
|---------|-------|------|
| **パスキー (WebAuthn/FIDO2)** | Phase 2 | パスワードレス認証の主流。Google ログインと並ぶ第 2 の認証手段として追加 |
| **MFA/2FA (TOTP)** | Phase 3 | Google Authenticator 等の TOTP。テナント管理者が「MFA 必須」を設定可能に |
| **リスクベース認証** | Phase 3 | 新しいデバイス/IP からのアクセス時に追加認証。`amr` claim で認証強度を JWT に反映 |
| **不正検知/アラート (Fraud Alert)** | Phase 3 | 不審なログイン検知（あり得ない移動距離、クレデンシャルスタッフィング）。管理者に Webhook アラート。脅威インテリジェンスフィードとの連携 |
| **デバイストラスト** | Phase 4 | 既知のデバイスを記憶。未知デバイスからのアクセスに追加検証 |
| **モバイル SDK (iOS/Android)** | Phase 4 | ネイティブ iOS/Android SDK。招待フローのディープリンク対応。生体認証連携 |
| **SAML SSO** | Phase 3 | エンタープライズ顧客の社内 IdP (Active Directory 等) と連携 |
| **SCIM** | Phase 4 | エンタープライズ顧客のユーザー自動同期 (Okta, Azure AD 等) |

全仕様: [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md)

---

## 技術仕様

### JWT 仕様

```
アルゴリズム: RS256 (RSA 2048-bit)
有効期限:    5 分
鍵管理:     signing_keys テーブル (AES-256-GCM 暗号化)
JWKS:       GET /.well-known/jwks.json
```

**Claims:**

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

### テナント解決

優先順位:

1. セッション Cookie の `tenant_id` → そのまま使用
2. URL サブドメイン → `tenant_domains` テーブル検索
3. メールドメイン → `tenant_domains` テーブル検索（フリーメール除外）
4. 該当なし → 招待コード入力 or テナント選択画面

### ロール階層

```
OWNER > ADMIN > MEMBER > VIEWER
```

| ロール | 権限 |
|--------|------|
| OWNER | テナント削除、OWNER 譲渡、全 ADMIN 権限 |
| ADMIN | メンバー招待/削除、ロール変更（ADMIN 以下）、テナント設定変更 |
| MEMBER | 通常利用 |
| VIEWER | 読み取り専用（App 側で制御） |

### Content Negotiation

```
Accept: application/json            → 必ず JSON レスポンス（302 禁止）
Accept: text/html                   → HTML or リダイレクト
X-Requested-With: XMLHttpRequest    → JSON 扱い
Authorization: Bearer ...           → JSON 扱い
```

### 環境変数

| 変数 | デフォルト | 説明 |
|------|-----------|------|
| `PORT` | 7070 | サーバーポート |
| `DB_HOST` | localhost | Postgres ホスト |
| `DB_PORT` | 54329 | Postgres ポート |
| `DB_NAME` | volta_auth | データベース名 |
| `DB_USER` | volta | DB ユーザー |
| `DB_PASSWORD` | volta | DB パスワード |
| `BASE_URL` | http://localhost:7070 | 公開 URL |
| `GOOGLE_CLIENT_ID` | | Google OAuth クライアント ID |
| `GOOGLE_CLIENT_SECRET` | | Google OAuth クライアントシークレット |
| `GOOGLE_REDIRECT_URI` | http://localhost:7070/callback | OAuth リダイレクト URI |
| `JWT_ISSUER` | volta-auth | JWT issuer claim |
| `JWT_AUDIENCE` | volta-apps | JWT audience claim |
| `JWT_TTL_SECONDS` | 300 | JWT 有効期限（5 分） |
| `JWT_KEY_ENCRYPTION_SECRET` | | 署名鍵の暗号化用 AES-256 鍵 |
| `SESSION_TTL_SECONDS` | 28800 | セッション有効期限（8 時間） |
| `ALLOWED_REDIRECT_DOMAINS` | localhost,127.0.0.1 | return_to のホワイトリスト |
| `VOLTA_SERVICE_TOKEN` | | M2M 用静的サービストークン（Phase 1） |
| `DEV_MODE` | false | /dev/token エンドポイントの有効化 |
| `APP_CONFIG_PATH` | volta-config.yaml | App 登録ファイルのパス |
| `SUPPORT_CONTACT` | | エラーページに表示する管理者連絡先 |

---

## 設計ドキュメント

このプロジェクトは DGE（Dialogue-driven Gap Extraction）で設計されました。8 セッションで 106 の設計ギャップを発見し、全て解決しています。

| ドキュメント | 説明 |
|-------------|------|
| [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md) | 全 Phase 実装仕様 |
| [`dge/specs/ux-specs-phase1.md`](dge/specs/ux-specs-phase1.md) | UI/UX 仕様 |
| [`dge/specs/ui-flow.md`](dge/specs/ui-flow.md) | 画面遷移図（mermaid） |
| [`dge/feedback/2026-03-31-volta-auth-proxy.md`](dge/feedback/2026-03-31-volta-auth-proxy.md) | DGE method フィードバック |
| [`tasks/001-fix-critical-bugs-and-implement-templates.md`](tasks/001-fix-critical-bugs-and-implement-templates.md) | 現在の実装タスク |
| [`backlog/001-form-state-recovery.md`](backlog/001-form-state-recovery.md) | Phase 2: フォーム自動保存 |

---

## ライセンス

TBD
