# HTTPヘッダー

[English version](header.md)

---

## これは何？

HTTPヘッダーは、HTTPリクエストまたはレスポンスに付加されるメタデータです。ブラウザがサーバーにウェブページを要求するとき、リクエストと一緒にヘッダーを送信し、サーバーもレスポンスと一緒にヘッダーを返します。ヘッダーはメッセージの内容ではなく、メッセージ*に関する*情報を運びます。

手紙の封筒のようなものです。中の手紙が実際のコンテンツ（ウェブページ、JSONデータ、画像）です。封筒には差出人住所、宛先、切手、そしてもしかすると「取扱注意」のシールがあります。HTTPヘッダーが封筒です -- メッセージそのものではなく、メッセージに関する重要なことをサーバーとブラウザに伝えます。

---

## なぜ重要なのか？

ヘッダーはvolta-auth-proxyにおいて非常に重要です。なぜなら**ヘッダーがプロキシからアプリケーションへアイデンティティを運ぶ方法**だからです。voltaがユーザーを認証するとき、リクエストボディを変更したりJavaScriptを注入したりしません。HTTPヘッダー -- `X-Volta-User-Id`、`X-Volta-Tenant-Id`、`X-Volta-Roles`など -- を設定し、Traefikがダウンストリームアプリに渡します。

アプリはこれらのヘッダーを読んで、ユーザーが誰か、どのテナントに属するか、どのロールを持つかを正確に知ります。SDKは不要。認証ライブラリも不要。ヘッダーだけです。

---

## 日常的に使っているヘッダー

気づかなくても毎日HTTPヘッダーに触れています：

```
  リクエストヘッダー（ブラウザ → サーバー）：
  ┌──────────────────────────────────────────────────┐
  │ Host: wiki.example.com                           │  ← どのウェブサイト？
  │ Cookie: __volta_session=abc123                   │  ← セッショントークン
  │ Accept: text/html                                │  ← HTMLが欲しい
  │ User-Agent: Mozilla/5.0 (Chrome)                 │  ← Chromeです
  │ Accept-Language: ja                              │  ← 日本語話します
  └──────────────────────────────────────────────────┘

  レスポンスヘッダー（サーバー → ブラウザ）：
  ┌──────────────────────────────────────────────────┐
  │ Content-Type: text/html; charset=utf-8           │  ← HTMLです
  │ Set-Cookie: __volta_session=xyz789; HttpOnly     │  ← これを覚えて
  │ Cache-Control: no-store                          │  ← キャッシュしないで
  │ X-Volta-Request-Id: req-12345                    │  ← 追跡ID
  └──────────────────────────────────────────────────┘
```

ヘッダーはキーと値のペアです：名前と値をコロンで区切ります。

---

## X-Volta-* カスタムヘッダー

HTTPはカスタムヘッダーを許可しています。慣習として、カスタムヘッダーは標準ヘッダーとの衝突を避けるためにプレフィックスを使います。voltaはすべてのアイデンティティ関連ヘッダーに`X-Volta-`プレフィックスを使います。

voltaの[ForwardAuth](forwardauth.ja.md)エンドポイントがリクエストを認証すると、これらのヘッダーをTraefikに返し、Traefikがアプリに転送します：

| ヘッダー | 例の値 | 意味 |
|---------|--------|------|
| `X-Volta-User-Id` | `550e8400-e29b-41d4-a716-446655440000` | 認証済みユーザーのUUID |
| `X-Volta-Email` | `taro@acme.com` | ユーザーのメールアドレス |
| `X-Volta-Display-Name` | `Taro Yamada` | ユーザーの表示名 |
| `X-Volta-Tenant-Id` | `660e8400-e29b-41d4-a716-446655440001` | 現在のテナントのUUID |
| `X-Volta-Tenant-Slug` | `acme` | テナントのURL用識別子 |
| `X-Volta-Roles` | `ADMIN` | このテナントでのユーザーのロール |
| `X-Volta-JWT` | `eyJhbGciOiJSUzI1NiIs...` | すべてのクレームを含む短命JWT |
| `X-Volta-App-Id` | `app-wiki` | volta-config.yamlから一致したアプリ |

### ヘッダーがシステムを通過する流れ

```
  ブラウザ               Traefik              volta           あなたのアプリ
  ════════               ═══════              ═════           ════════════

  GET /dashboard
  Cookie: __volta_session=abc
  ─────────────────────►
                        認証チェックのため
                        voltaに転送
                        ──────────────────►
                                            セッション有効。
                                            ユーザー: taro
                                            テナント: acme
                                            ロール: ADMIN
                        ◄──────────────────
                        200 OK
                        X-Volta-User-Id: taro-uuid
                        X-Volta-Tenant-Id: acme-uuid
                        X-Volta-Roles: ADMIN

                        元のリクエスト
                        + voltaヘッダーを転送
                        ─────────────────────────────────►
                                                          GET /dashboard
                                                          X-Volta-User-Id: taro-uuid
                                                          X-Volta-Tenant-Id: acme-uuid
                                                          X-Volta-Roles: ADMIN
                                                          X-Volta-JWT: eyJ...

                                                          アプリがヘッダーを読む。
                                                          認証コード不要。
```

### アプリでヘッダーを読む

```java
// Javalin
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String role = ctx.header("X-Volta-Roles");

    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

```python
# Flask
@app.route('/api/data')
def get_data():
    user_id = request.headers.get('X-Volta-User-Id')
    tenant_id = request.headers.get('X-Volta-Tenant-Id')
    role = request.headers.get('X-Volta-Roles')

    data = db.execute("SELECT * FROM items WHERE tenant_id = %s", tenant_id)
    return jsonify(data)
```

```javascript
// Express.js
app.get('/api/data', (req, res) => {
    const userId = req.headers['x-volta-user-id'];
    const tenantId = req.headers['x-volta-tenant-id'];
    const role = req.headers['x-volta-roles'];

    // 注意: Expressではヘッダー名は小文字
});
```

---

## セキュリティ：Traefik経由でのみヘッダーを信頼する理由

ヘッダーは誰でも設定できます。悪意のあるユーザーが次のように送信できます：

```
curl -H "X-Volta-User-Id: admin-uuid" https://wiki.example.com/api/data
```

TraefikのForwardAuthを経由せずにアプリがこのヘッダーを信頼すると、攻撃者は管理者になりすますことに成功します。ヘッダーが信頼できるのは：

1. リクエストがTraefikを経由する（クライアントが送った`X-Volta-*`ヘッダーを除去）
2. TraefikのForwardAuthミドルウェアがvoltaを呼んでセッションを検証
3. voltaのレスポンスヘッダーがクライアント送信のものを置換

これが、アプリがリバースプロキシなしに**直接アクセス可能であってはならない**理由です。

---

## さらに学ぶために

- [forwardauth.ja.md](forwardauth.ja.md) -- voltaヘッダーを設定するメカニズム。
- [jwt.ja.md](jwt.ja.md) -- より高セキュリティの検証のための`X-Volta-JWT`ヘッダー。
- [http.ja.md](http.ja.md) -- HTTPプロトコルの基本。
- [cookie.ja.md](cookie.ja.md) -- 認証フローを開始するセッションCookie。
