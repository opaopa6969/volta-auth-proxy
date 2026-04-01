# App連携

[English version](app-integration.md)

---

アプリケーションはどうやって認証システムに接続するのか？この質問はシンプルに聞こえますが、答えがアーキテクチャ全体を形作ります。3つの基本モデルがあり、間違ったものを選ぶと数ヶ月の手戻りにつながります。

---

## App連携の3つのモデル

### モデル1: 組み込み（認証コードがアプリ内に）

認証ロジックがアプリケーション内に存在します。アプリが直接ログインフォーム、パスワード検証、セッション管理、トークン検証を処理します。

```
  ┌──────────────────────────────────────────────┐
  │ あなたのアプリケーション                       │
  │                                                │
  │  ┌──────────────┐  ┌──────────────────────┐  │
  │  │ ビジネスロジック│  │ 認証ロジック         │  │
  │  │ （あなたのコード）│ │ （Spring Security、 │  │
  │  │               │  │  Passport.js等）     │  │
  │  └──────────────┘  └──────────────────────┘  │
  │                                                │
  └────────────────────────────────────────────────┘
```

**例：** JavaアプリのSpring Security。Node.jsのPassport.js。Djangoの認証モジュール。Laravelの認証。

**利点：**
- すべてが一箇所にある
- 管理する外部サービスなし
- 認証の動作を完全にコントロール

**欠点：**
- 認証コードがすべてのアプリで重複
- すべてのアプリチームが認証セキュリティを理解する必要
- 認証ロジックの変更がすべてのアプリの変更を意味
- 異なるアプリが異なる認証実装をする可能性（一貫性のないセキュリティ）

このモデルは単一のアプリケーションで機能します。マルチサービスアーキテクチャでは失敗します。

### モデル2: リダイレクト（外部認証サーバー）

アプリがログインのためにユーザーを別の認証サーバーにリダイレクトします。認証後、サーバーがトークン付きでリダイレクトバックします。

```
  ┌──────────┐    リダイレクト   ┌──────────────┐
  │ アプリ    │ ──────────────► │ Keycloak /   │
  │           │                 │ Auth0 /      │
  │           │ ◄────────────── │ voltaログイン │
  │           │    トークン      └──────────────┘
  └──────────┘
```

**例：** KeycloakでのOAuth2/OIDC、Auth0のUniversal Login、あらゆる「Googleでログイン」フロー。

**利点：**
- 認証が集中（全アプリに1つのログインサーバー）
- アプリは生の認証情報ではなくトークンを受信
- 標準ベース（OAuth2/OIDC）

**欠点：**
- すべてのアプリがOAuth2クライアントフロー（リダイレクト、トークン交換、トークン検証）を実装する必要
- すべてのアプリに認証ライブラリ/SDKが必要
- トークンリフレッシュロジックをアプリごとに実装
- 認証サーバーが変わると、すべてのアプリのSDKを更新する必要

このモデルは組み込みよりは良いですが、まだ各アプリケーションに認証の負担を置きます。

### モデル3: プロキシ（ForwardAuth）

リバースプロキシがリクエストがアプリに到達する前に認証を処理します。アプリはHTTPヘッダー経由でアイデンティティ情報を受け取り、認証コードはゼロです。

```
  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ ブラウザ  │────►│ Traefik   │────►│ volta    │     │ アプリ   │
  │           │     │ (プロキシ)│     │ (認証)   │     │ (認証   │
  │           │     │           │◄────│          │     │  コード  │
  │           │◄────│           │─────┤          ├────►│  なし!) │
  └──────────┘     └──────────┘     └──────────┘     └──────────┘
                   X-Volta-*                          ヘッダーを
                   ヘッダー追加                       読むだけ
```

**例：** TraefikのForwardAuthでのvolta-auth-proxy、Ory Oathkeeper、oauth2-proxy。

**利点：**
- アプリの認証コードがゼロ
- 新しいアプリへの認証追加 = Traefik設定1行
- 認証の変更は1箇所（volta）で発生、すべてのアプリではない
- どの言語/フレームワークでも動作（ヘッダーを読むだけ）

**欠点：**
- リバースプロキシ（Traefik、Nginx等）が必要
- すべてのリクエストで認証チェックのネットワークホップ
- アプリがプロキシを信頼する必要（ネットワーク分離が必要）

---

## 「サービスを量産する」ためにプロキシモデルが最適な理由

複数のサービス（wiki、管理パネル、課金ダッシュボード、APIゲートウェイ）を持つSaaSプラットフォームを構築しているなら、プロキシモデルでは数日ではなく数分で各サービスに認証を追加できます。

### 新しいサービスに認証を追加：3つのモデル比較

**組み込み（Spring Security）：**
```
1. spring-boot-starter-security依存関係を追加
2. OAuth2クライアントプロパティを設定
3. SecurityFilterChainを実装
4. フィルターでトークンリフレッシュを処理
5. トークンクレームからユーザー/テナントを抽出
6. 各エンドポイントでロールベースアクセスを実装
7. 認証統合をテスト
8. デプロイ
時間：サービスあたり1-3日
```

**リダイレクト（Auth0 SDK）：**
```
1. @auth0/auth0-spa-jsをインストール（フロントエンド）
2. 認証プロバイダーラッパーを設定
3. アプリルートにAuth0Providerを追加
4. コンポーネントでuseAuth0フックを実装
5. トークンリフレッシュを処理
6. ユーザーデータにAuth0 Management APIを呼び出し
7. 新しいアプリ用にAuth0ダッシュボードを設定
8. 統合をテスト
時間：サービスあたり1-2日
```

**プロキシ（volta ForwardAuth）：**
```
1. ルートにTraefikミドルウェアを追加：
   middlewares: [volta-auth]
2. アプリでヘッダーを読む：
   ctx.header("X-Volta-User-Id")
   ctx.header("X-Volta-Tenant-Id")
3. 完了。
時間：サービスあたり30分
```

違いは劇的です。プロキシモデルでは、認証はインフラです。アプリは認証がどう動くか知らないし気にしません。ヘッダーを読むだけです。

---

## voltaの2ステップ統合

voltaは2つの補完的な統合ポイントを提供します：

### ステップ1: ヘッダー（受動的 -- すべてのリクエスト）

TraefikのForwardAuthを通過するすべてのリクエストは、アイデンティティヘッダー付きでアプリに到着します：

```
X-Volta-User-Id: user-uuid
X-Volta-Tenant-Id: tenant-uuid
X-Volta-Roles: ADMIN
X-Volta-Email: taro@acme.com
X-Volta-Display-Name: Taro Yamada
X-Volta-Tenant-Slug: acme
X-Volta-JWT: eyJhbGci...
X-Volta-App-Id: app-wiki
```

アプリはこれらのヘッダーを読みます。それがステップ1。大半のアプリはこれだけで十分です。

```java
// Javalinでの完全な認証済みAPIエンドポイント：
app.get("/api/items", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    var items = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(items);
});
// 3行。認証ライブラリなし。SDKなし。トークン解析なし。
```

### ステップ2: API（能動的 -- 必要時）

アプリが認証操作（メンバー一覧、ロール変更、招待作成）を行う必要があるとき、voltaの[内部API](internal-api.ja.md)を呼びます：

```java
// 現在のテナントの全メンバーを一覧：
var response = httpClient.send(
    HttpRequest.newBuilder()
        .uri(URI.create("http://volta:7070/api/v1/tenants/" + tenantId + "/members"))
        .header("Authorization", "Bearer " + serviceToken)
        .build(),
    HttpResponse.BodyHandlers.ofString()
);
```

ステップ2は管理パネル、チーム設定ページ、ユーザーやテナントを管理するUIに使います。

---

## 言語非依存の利点

プロキシモデルはHTTPヘッダーで通信するため、アプリはどの言語でも書けます：

```python
# Python (Flask)
@app.route('/api/data')
def get_data():
    tenant_id = request.headers.get('X-Volta-Tenant-Id')
    # ...
```

```go
// Go (net/http)
func handler(w http.ResponseWriter, r *http.Request) {
    tenantId := r.Header.Get("X-Volta-Tenant-Id")
    // ...
}
```

```ruby
# Ruby (Sinatra)
get '/api/data' do
    tenant_id = request.env['HTTP_X_VOLTA_TENANT_ID']
    # ...
end
```

```rust
// Rust (Actix-web)
async fn handler(req: HttpRequest) -> impl Responder {
    let tenant_id = req.headers().get("X-Volta-Tenant-Id");
    // ...
}
```

SDKなし。ベンダーライブラリなし。HTTPヘッダーだけ。すべての言語とフレームワークが既に理解しているもの。

---

## さらに学ぶために

- [forwardauth.ja.md](forwardauth.ja.md) -- ForwardAuthパターンの技術的詳細。
- [header.ja.md](header.ja.md) -- X-Volta-*ヘッダーの詳細。
- [internal-api.ja.md](internal-api.ja.md) -- アプリ委譲用のvoltaのREST API。
- [downstream-app.ja.md](downstream-app.ja.md) -- ダウンストリームアプリとは何か、voltaとの関係。
- [jwt.ja.md](jwt.ja.md) -- より高セキュリティのためのX-Volta-JWTヘッダー。
