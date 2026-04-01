# エンドポイント

[English version](endpoint.md)

---

## これは何？

エンドポイントとは、[サーバー](server.md)が待ち受けて応答する、特定の[URL](url.md)パスと[HTTP](http.md)メソッドの組み合わせです。「`/api/v1/users/me`エンドポイントを呼ぶ」と言ったら、「その特定のパスにHTTPリクエストを送る」という意味です。各エンドポイントは1つのことをします -- ユーザーデータを返す、テナントを作成する、メンバーを削除する、など。

役所の窓口に似ています。建物には多くの窓口があり、それぞれが特定の業務を担当します：窓口1は出生届、窓口2は運転免許証、窓口3は税金の申告書。必要なものに応じて正しい窓口に行きます。エンドポイントは窓口です -- 特定の場所（URLパス）を持ち、特定の種類のリクエストを処理します。

[API](api.md)はサーバーが提供するすべてのエンドポイントの集合です。1つのエンドポイントはそのAPI内の1つの具体的な操作です。

---

## なぜ重要なのか？

エンドポイントはシステム間のインターフェースです。明確に定義されたエンドポイントがなければ：

- [クライアント](client.md)アプリケーションがリクエストの送り先を知れない
- [ダウンストリームアプリ](downstream-app.md)がvoltaからどんなデータが利用可能か知れない
- 操作ごとのセキュリティルールを適用できない（例：「ADMINだけがこのエンドポイントにアクセスできる」）
- ドキュメントが書けない -- ドキュメント化するものがない
- APIバージョニングが壊れる -- クライアントが頼れる安定したエンドポイントパスが必要

[SPA](spa.md)とvolta-auth-proxyの間、またはダウンストリームアプリとvoltaの[Internal API](internal-api.md)の間のすべてのやり取りは、エンドポイントを通じて行われます。

---

## どう動くのか？

### エンドポイントの構造

エンドポイントは3つのもので定義されます：

```
  HTTPメソッド  +   URLパス                =   エンドポイント
  ───────────      ──────────────────         ─────────────────────
  GET              /api/v1/users/me           「現在のユーザーを取得」
  POST             /api/v1/tenants            「テナントを作成」
  DELETE           /api/v1/admin/members/:id  「メンバーを削除」
```

**同じパス**で**異なるメソッド**は別のエンドポイントになります：

```
  GET    /api/v1/tenants      →  このユーザーの全テナントを一覧
  POST   /api/v1/tenants      →  新しいテナントを作成
  PUT    /api/v1/tenants/:id  →  テナントを更新
  DELETE /api/v1/tenants/:id  →  テナントを削除
```

### パスパラメータ

一部のエンドポイントには動的セグメントがあり、`:`や`{}`で示されます：

```
  /api/v1/admin/members/:memberId
                        ^^^^^^^^^^
                        この部分はリクエストごとに変わる

  GET /api/v1/admin/members/550e8400-e29b-41d4-a716-446655440000
  GET /api/v1/admin/members/660f9500-f30c-52e5-b827-557766551111
```

`:memberId`はパスパラメータ -- URLの[変数](variable.md)部分です。

### クエリパラメータ

エンドポイントはクエリパラメータで追加データを受け取れます：

```
  GET /api/v1/admin/members?page=2&limit=20&role=ADMIN
                            ^^^^^^^^^^^^^^^^^^^^^^^^
                            クエリパラメータ（フィルタリング、ページネーション）
```

### リクエストとレスポンス

完全なエンドポイントのやり取り：

```
  クライアント                                  サーバー（volta）
  ──────                                       ──────────────
  GET /api/v1/users/me
  ヘッダー:
    Cookie: JSESSIONID=abc123
    Accept: application/json
                          ─────────────────────>

                          <─────────────────────
  HTTP/1.1 200 OK
  ヘッダー:
    Content-Type: application/json
  ボディ:
    {
      "userId": "550e8400-...",
      "displayName": "Taro Yamada",
      "tenantId": "abcd1234-...",
      "roles": ["ADMIN"]
    }
```

### RESTエンドポイントの慣習

ほとんどのAPIはエンドポイント設計にRESTの慣習に従います：

| 操作 | メソッド | パスパターン | 例 |
|------|---------|------------|---|
| 全件取得 | GET | /resources | GET /api/v1/tenants |
| 1件取得 | GET | /resources/:id | GET /api/v1/tenants/:id |
| 作成 | POST | /resources | POST /api/v1/tenants |
| 更新 | PUT | /resources/:id | PUT /api/v1/tenants/:id |
| 削除 | DELETE | /resources/:id | DELETE /api/v1/tenants/:id |

### エンドポイントグループ

エンドポイントは論理的なグループに整理されることが多いです：

```
  volta-auth-proxyエンドポイント
  │
  ├── 認証エンドポイント（ブラウザ向け、HTMLレスポンス）
  │   ├── GET  /auth/login
  │   ├── GET  /auth/callback
  │   ├── POST /auth/logout
  │   └── POST /auth/refresh
  │
  ├── APIエンドポイント（JSONレスポンス、認証必須）
  │   ├── GET  /api/v1/users/me
  │   ├── GET  /api/v1/tenants
  │   ├── POST /api/v1/tenants
  │   └── ...
  │
  ├── 管理エンドポイント（ADMINまたはOWNERロールが必要）
  │   ├── GET  /api/v1/admin/members
  │   ├── POST /api/v1/admin/members/invite
  │   └── ...
  │
  ├── 内部エンドポイント（Traefikが呼ぶ、ユーザーは呼ばない）
  │   └── GET  /forwardauth
  │
  └── Well-knownエンドポイント（公開、認証不要）
      └── GET  /.well-known/jwks.json
```

---

## volta-auth-proxy ではどう使われている？

### Javalinでのエンドポイント登録

voltaはJavalinのルートメソッドを使ってMain.javaでエンドポイントを登録します：

```java
// 認証エンドポイント
app.get("/auth/login",     ctx -> authController.loginPage(ctx));
app.get("/auth/callback",  ctx -> authController.callback(ctx));
app.post("/auth/logout",   ctx -> authController.logout(ctx));
app.post("/auth/refresh",  ctx -> authController.refresh(ctx));

// APIエンドポイント
app.get("/api/v1/users/me",    ctx -> userController.me(ctx));
app.get("/api/v1/tenants",     ctx -> tenantController.list(ctx));
app.post("/api/v1/tenants",    ctx -> tenantController.create(ctx));

// 管理エンドポイント
app.get("/api/v1/admin/members",        ctx -> adminController.listMembers(ctx));
app.post("/api/v1/admin/members/invite", ctx -> adminController.invite(ctx));

// ForwardAuthエンドポイント
app.get("/forwardauth", ctx -> forwardAuthController.check(ctx));

// JWKSエンドポイント
app.get("/.well-known/jwks.json", ctx -> jwksController.jwks(ctx));
```

各行が1つのエンドポイントを1つのハンドラーメソッドにマッピングします。隠れたルーティング設定はありません。

### エンドポイントのセキュリティレイヤー

エンドポイントごとに異なるセキュリティ要件があり、[ミドルウェア](middleware.md)で強制されます：

```
  エンドポイント                      認証必須？      ロール必須？
  ────────────────────────────────  ──────────────  ──────────────
  GET  /auth/login                  不要            不要
  GET  /.well-known/jwks.json       不要            不要
  GET  /api/v1/users/me             必要(セッション) 不要(任意のロール)
  GET  /api/v1/tenants              必要(セッション) 不要(任意のロール)
  POST /api/v1/admin/members/invite 必要(セッション) ADMINまたはOWNER
  GET  /forwardauth                 必要(セッション) 不要(内部用)
```

### エンドポイントのAPIバージョニング

voltaはURLパスに[APIバージョニング](api-versioning.md)を使用します：

```
  /api/v1/users/me      ← 現在のバージョン
  /api/v2/users/me      ← 将来のバージョン（未実装）
```

`v1`プレフィックスは「このAPIのバージョン1」を意味します。voltaがレスポンスフォーマットに破壊的変更を加える必要がある場合、`v1`を動かし続けたまま`v2`エンドポイントを作ります。既存の[ダウンストリームアプリ](downstream-app.md)は壊れません。

### エンドポイントのレスポンス形式

| エンドポイントグループ | レスポンス形式 | 使用者 |
|---------------------|-------------|--------|
| /auth/* | HTML（jteテンプレート） | ブラウザ（ユーザーの目） |
| /api/v1/* | [JSON](json.md) | SPA、ダウンストリームアプリ |
| /forwardauth | 空ボディ + [ヘッダー](header.md) | Traefik |
| /.well-known/* | JSON | JWT検証ライブラリ |

---

## よくある間違いと攻撃

### 間違い1：一貫性のないエンドポイント命名

命名規則を混在させるとAPIが学びにくくなります：

```
  悪い例:
  /api/v1/getUsers          （パスに動詞）
  /api/v1/tenant/create     （パスに動詞）
  /api/v1/member-list       （上記と一貫性なし）

  良い例:
  GET  /api/v1/users        （名詞、複数形）
  POST /api/v1/tenants      （名詞、複数形）
  GET  /api/v1/members      （名詞、複数形）
```

HTTPメソッド（GET、POST、DELETE）がすでに動詞を表現しています。パスは名詞であるべきです。

### 間違い2：公開エンドポイントで内部IDを露出する

`GET /api/v1/users/:id`のようなエンドポイントで、IDを推測すれば誰でもユーザーデータを取得できるなら、安全でない直接オブジェクト参照（IDOR）です。voltaは[UUID](uuid.md)（推測困難）を使い、さらにリクエスト元のユーザーがリソースへのアクセス権を持っているかもチェックします。

### 攻撃1：エンドポイントの列挙

攻撃者が体系的にパスを試して未公開のエンドポイントを発見します：

```
  GET /api/v1/admin/settings       → 403（存在するが禁止）
  GET /api/v1/admin/debug          → 404（存在しない）
  GET /api/v1/admin/backup         → 404（存在しない）
  GET /api/v1/internal/health      → 200（何か見つけた！）
```

防御：未認証ユーザーに対して「見つからない」と「禁止」で同じエラーコードを返す（voltaは未認証のすべてのAPIリクエストに対して403や404ではなく401を返します）。

### 攻撃2：HTTPメソッドの改ざん

攻撃者が同じパスで異なるメソッドを試します：

```
  GET  /api/v1/admin/members/:id   → 200（読み取りアクセス）
  DELETE /api/v1/admin/members/:id → 403? or 405?
```

エンドポイントがGETでのみ認証チェックしてDELETEではしない場合、攻撃者がメンバーを削除できます。voltaは各メソッド + パスの組み合わせを明示的に登録し、未登録の組み合わせは405 Method Not Allowedを返します。

### 間違い3：重要なエンドポイントにレート制限がない

`/auth/login`や`/api/v1/admin/members/invite`のようなエンドポイントは、何千回も呼ばれると悪用される可能性があります。[レート制限](rate-limiting.md)なしでは、攻撃者がログインのブルートフォースや招待のスパムができてしまいます。

---

## さらに学ぶ

- [api.md](api.md) -- APIを形成するエンドポイントの集合。
- [api-versioning.md](api-versioning.md) -- voltaがエンドポイントパスに/v1/を使う理由。
- [http.md](http.md) -- エンドポイントが使うプロトコル。
- [middleware.md](middleware.md) -- エンドポイントグループにセキュリティがどう適用されるか。
- [internal-api.md](internal-api.md) -- ダウンストリームアプリ向けのvoltaのAPIエンドポイント。
- [forwardauth.md](forwardauth.md) -- 特別な/forwardauthエンドポイント。
- [url.md](url.md) -- エンドポイントパスと完全なURLの関係。
- [http-status-codes.md](http-status-codes.md) -- エンドポイントが返すレスポンスコード。
