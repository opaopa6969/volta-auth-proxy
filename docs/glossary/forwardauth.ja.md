# ForwardAuth

[English version](forwardauth.md)

---

## これは何？

ForwardAuthは、リバースプロキシ（TraefikやNginxなど）が実際のアプリケーションにリクエストを転送する前に、外部サービスに「このリクエストは許可されていますか？」と確認するパターンです。リバースプロキシが認証サービスにサブリクエストを送り、そのレスポンス（200 OKか401 Unauthorized）に基づいてリクエストを通すかブロックするかを決定します。

レストランのドアマンのようなものです。ドアマン（Traefik）がドアに立っています。お客さんが来ると、ドアマンが予約デスク（volta-auth-proxy）に電話して「この方はリストに載っていますか？」と聞きます。予約デスクは「はい」（「7番テーブル、VIP」などの詳細付き）または「いいえ」と答えます。ドアマンは誰も席に案内しません。入場の可否を決めるだけです。実際の食事サービスはレストラン内（あなたのアプリ）で行われます。

---

## なぜ重要なのか？

ForwardAuth（または類似のもの）がないと、すべてのアプリケーションが独自の認証を実装する必要があります：

```
  ForwardAuthなし：
  ┌─────────┐   ┌─────────────────────┐
  │ App A   │   │ App A内の認証コード   │  ← 認証ロジックの重複
  └─────────┘   └─────────────────────┘
  ┌─────────┐   ┌─────────────────────┐
  │ App B   │   │ App B内の認証コード   │  ← 同じロジック、別のバグ
  └─────────┘   └─────────────────────┘
  ┌─────────┐   ┌─────────────────────┐
  │ App C   │   │ App C内の認証コード   │  ← 3箇所でメンテナンス
  └─────────┘   └─────────────────────┘

  ForwardAuthあり：
  ┌─────────────────────────────────┐
  │ volta-auth-proxy（1箇所）       │  ← すべての認証ロジックがここ
  └─────────────────────────────────┘
            ↑ 「これOK？」
  ┌─────────────────────────────────┐
  │ Traefik（リバースプロキシ）       │  ← 転送前にvoltaに確認
  └─────────────────────────────────┘
       ↓ はい           ↓ いいえ
  ┌─────────┐     ┌──────────┐
  │ App A   │     │ ログインに │
  │（認証    │     │ リダイレクト│
  │ コード   │     └──────────┘
  │ ゼロ！） │
  └─────────┘
```

ForwardAuthにより、アプリは**認証コードがゼロ**になります。voltaが承認した後にTraefikが渡すヘッダーを読むだけです。

---

## どう動くのか？

### リクエストフロー図

```
  ブラウザ                 Traefik              volta-auth-proxy          App
  ========                 =======              ================          ===

  1. GET /dashboard
  ──────────────────────►

                          2. 「待って、先に認証を確認」

                             GET /auth/verify
                             (元のヘッダーを転送：
                              Cookie, Host, X-Forwarded-*)
                          ──────────────────────►

                                                  3. セッションCookieを読む
                                                  4. DBでセッションを検索
                                                  5. ユーザー+テナントを検証
                                                  6. ロールとアプリの
                                                     allowed_rolesを照合
                                                  7. 新鮮なJWTを発行

                          ◄──────────────────────
                             200 OK
                             X-Volta-User-Id: user-uuid
                             X-Volta-Tenant-Id: tenant-uuid
                             X-Volta-Roles: ADMIN
                             X-Volta-JWT: eyJhbGci...
                             X-Volta-Display-Name: Taro
                             X-Volta-Email: taro@acme.com
                             X-Volta-Tenant-Slug: acme
                             X-Volta-App-Id: app-wiki

                          8. 「OK、ユーザーは認証済み」
                             元のリクエストを転送
                             + voltaヘッダーを追加

                                                                   ──────►
                                                                   GET /dashboard
                                                                   X-Volta-User-Id: user-uuid
                                                                   X-Volta-Tenant-Id: tenant-uuid
                                                                   X-Volta-Roles: ADMIN
                                                                   X-Volta-JWT: eyJhbGci...

                                                                   9. アプリがヘッダーを読む
                                                                      適切なユーザーと
                                                                      テナントで
                                                                      ダッシュボードを描画

  ◄──────────────────────────────────────────────────────────────────
  10. ユーザーがダッシュボードを見る
```

### 認証失敗時

```
  ブラウザ                 Traefik              volta-auth-proxy
  ========                 =======              ================

  1. GET /dashboard
     （セッションCookieなし、またはセッション期限切れ）
  ──────────────────────►

                             GET /auth/verify
                          ──────────────────────►

                                                  2. 有効なセッションなし

                          ◄──────────────────────
                             401 Unauthorized
                             （JSONリクエストの場合）
                             または
                             302 /loginにリダイレクト
                             （ブラウザリクエストの場合）

                          3. Traefikが401/302をブラウザに返す

  ◄──────────────────────
  4. ブラウザが/loginにリダイレクト
```

### なぜプロキシはリクエストボディを見ないのか

これは重要なポイントです。ForwardAuthパターンでは：

```
  Traefikがvoltaに送るもの：           Traefikがアプリに送るもの：
  ┌──────────────────────────┐          ┌──────────────────────────────┐
  │ GET /auth/verify          │          │ GET /dashboard                │
  │ Cookie: __volta_session=  │          │ Cookie: __volta_session=      │
  │ X-Forwarded-Host: wiki.  │          │ X-Volta-User-Id: user-uuid   │
  │ X-Forwarded-Uri: /dash   │          │ X-Volta-Tenant-Id: tenant-id │
  │ X-Forwarded-Method: GET  │          │ X-Volta-JWT: eyJhbGci...     │
  │                           │          │                              │
  │ リクエストボディなし       │          │（元のリクエストボディがあれば  │
  │                           │          │ アプリに直接渡される）         │
  └──────────────────────────┘          └──────────────────────────────┘
```

volta-auth-proxyは実際のリクエストボディ（POSTデータ、ファイルアップロードなど）を**見ません**。ヘッダーだけを見ます。これが重要な理由：

1. **プライバシー：** リクエストボディの機密データが認証サービスを通過しない。
2. **パフォーマンス：** 大きなリクエストボディを認証経由でプロキシするオーバーヘッドがない。
3. **セキュリティ：** 認証サービスの攻撃対象面が最小限。リクエストボディを改変するために使えない。

### リバースプロキシパターンとの比較

一部の認証システムは完全なリバースプロキシとして動作し、すべてのトラフィックがそこを通ります：

```
  リバースプロキシパターン（voltaが使わないもの）：
  ┌─────────┐     ┌──────────────┐     ┌─────────┐
  │ ブラウザ  │────►│ 認証プロキシ   │────►│  アプリ  │
  │          │◄────│（すべての      │◄────│         │
  │          │     │ トラフィック   │     │         │
  │          │     │ を見る）       │     │         │
  └─────────┘     └──────────────┘     └─────────┘

  問題点：
  - 認証プロキシがボトルネック（すべてのトラフィックが通る）
  - 認証プロキシがリクエスト/レスポンスボディを見る（プライバシー懸念）
  - 認証プロキシがダウンするとすべてがダウン
  - 大きなファイルアップロード、WebSocketなども処理が必要


  ForwardAuthパターン（voltaが使うもの）：
  ┌─────────┐     ┌──────────────┐     ┌─────────┐
  │ ブラウザ  │────►│  Traefik     │────►│  アプリ  │
  │          │◄────│              │◄────│         │
  └─────────┘     └──────────────┘     └─────────┘
                         │
                    「これOK？」
                         │
                  ┌──────▼───────┐
                  │ volta-auth-  │
                  │ proxy        │
                  │（ヘッダー     │
                  │ だけを見る）   │
                  └──────────────┘

  利点：
  - voltaは認証チェックだけを処理（軽量）
  - 実際のトラフィックはTraefikからアプリに直接流れる
  - voltaが一時的にダウンしても処理中のリクエストは失われない
  - 大きなペイロードのボトルネックなし
```

---

## volta-auth-proxyのForwardAuth実装

### /auth/verifyエンドポイント

TraefikがForwardAuthサブリクエストを送ると、voltaの`/auth/verify`ハンドラが：

1. **セッションCookieを読む**（転送された`Cookie`ヘッダーから）
2. **セッションを検索**（PostgreSQLデータベース内）
3. **セッションを検証**（期限切れでない、無効化されていない、ユーザーがアクティブメンバー）
4. **アプリを特定**（`X-Forwarded-Host`ヘッダーと`volta-config.yaml`を照合）
5. **ロール認可をチェック**（ユーザーのロールがアプリの`allowed_roles`に含まれるか）
6. **新鮮なJWTを発行**（5分有効期限、ユーザー/テナント/ロールのクレーム付き）
7. **200とX-Volta-*ヘッダーを返す**（Traefikがアプリに渡す）

### Traefikの設定

```yaml
# traefik動的設定
http:
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
          - X-Volta-App-Id

  routers:
    my-wiki:
      rule: "Host(`wiki.example.com`)"
      middlewares: [volta-auth]
      service: wiki-service

  services:
    wiki-service:
      loadBalancer:
        servers:
          - url: "http://wiki-app:8080"
```

`authResponseHeaders`はvoltaのレスポンスからどのヘッダーを下流のアプリに転送するかをTraefikに指示します。このリストにないヘッダーは除去されます。

### volta-config.yamlでのアプリ登録

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

ForwardAuthリクエストが来ると、voltaは`X-Forwarded-Host`を登録済みアプリのURLと照合します。ユーザーのロールがアプリの`allowed_roles`に含まれなければ、403 Forbiddenを返します。

### アプリが受け取るもの

アプリはHTTPヘッダーでID情報を受け取ります。基本的な使用ではJWT検証は不要です：

```java
// 最小限のアプリ統合（Javalinの例）
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String roles = ctx.header("X-Volta-Roles");

    // データ分離のためにDBクエリでtenantIdを使用
    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

より高いセキュリティが必要な場合、アプリは`X-Volta-JWT`ヘッダーをvoltaのJWKSエンドポイントで検証できます：

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .build();

app.before("/api/*", volta.middleware());
```

---

## よくある間違いと攻撃

### 間違い1：ForwardAuthなしでヘッダーを信頼する

アプリが`X-Volta-User-Id`を読んでいるのにForwardAuthミドルウェアが前にない場合、誰でもそのヘッダーを手動で設定して任意のユーザーになりすませます。ヘッダーはTraefikがForwardAuth経由で除去/置換する場合のみ信頼できます。

### 間違い2：クライアントリクエストからX-Volta-*ヘッダーを除去しない

Traefikは`X-Volta-*`ヘッダーをマージではなく置換すべきです。クライアントが`X-Volta-User-Id: admin-uuid`をリクエストに含め、Traefikがそれを除去しなければ、アプリが偽造ヘッダーを信頼する可能性があります。

### 間違い3：アプリを直接公開する（Traefikをバイパス）

アプリがTraefikを経由せずにアクセスできる場合（例：ポート8080で直接）、ForwardAuthチェックがありません。認証なしで誰でもアプリにアクセスできます。アプリはリバースプロキシ経由でのみ到達可能にしてください。

### 攻撃：ヘッダーインジェクション

攻撃者がForwardAuthレスポンスにヘッダーを注入できる場合（例：認証サービスでのCRLFインジェクション）、任意の`X-Volta-*`ヘッダーを設定できます。voltaは検証済みのセッションデータからヘッダーを構築し、ユーザー入力からは構築しないため、この攻撃に対して脆弱ではありません。

---

## さらに学ぶために

- [Traefik ForwardAuthドキュメント](https://doc.traefik.io/traefik/middlewares/http/forwardauth/) -- Traefikの公式ドキュメント。
- [oidc.md](oidc.md) -- ForwardAuthがチェックするセッションを作成する認証フロー。
- [session.md](session.md) -- セッションの仕組み（ForwardAuthが検証するもの）。
- [jwt.md](jwt.md) -- ForwardAuthが発行しアプリに渡すJWT。
- [tenant.md](tenant.md) -- ForwardAuthフローでのテナント解決の仕組み。
