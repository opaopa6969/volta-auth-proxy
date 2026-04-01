# Content Negotiation（コンテンツネゴシエーション）

[English version](content-negotiation.md)

---

## これは何？

コンテンツネゴシエーションとは、クライアントが受け入れ可能と言っている形式に基づいて、サーバーが適切なレスポンス形式を選択するプロセスです。ブラウザはHTMLを要求します。APIクライアントはJSONを要求します。同じエンドポイントが両方を提供でき、リクエストの`Accept`[ヘッダー](header.ja.md)に基づいて形式を選択します。

店内飲食とテイクアウトの両方を提供するレストランでの注文のようなものです。同じ注文（「チキンカレー」）をしますが、包装が違います：店内はガーニッシュ付きのお皿、テイクアウトは密閉容器。料理は同じで、見せ方がお客さんのニーズに合わせて変わります。

volta-auth-proxyでは、コンテンツネゴシエーションはセキュリティ上**極めて重要**です。未認証のブラウザが保護されたページにアクセスすると、voltaは`/login`にリダイレクト（302）します。しかし未認証のSPA fetch()呼び出しが同じページにアクセスした場合、voltaは302ではなくJSONエラー（401）を返さなければなりません。302をfetch()に返すと、ブラウザが黙ってGoogleのログインページにリダイレクトを辿り、SPAがJSONエラーの代わりにGoogleのHTMLを受け取ります。

---

## なぜ重要なのか？

コンテンツネゴシエーションがなければ：

- **SPAが壊れる**：fetch()は302リダイレクトを自動的に辿る。SPAは処理できるJSONエラーの代わりにGoogleのログインHTMLを受け取る
- **APIクライアントが壊れる**：CLIツールやモバイルアプリがJSONを期待しているのにHTMLページを受け取る
- **混乱するUX**：SPAがリダイレクトレスポンスをパースできないため、ユーザーにエラーメッセージが表示されない
- **セキュリティの混乱**：SPAがHTMLレスポンスを成功レスポンスと解釈して安全でない方法でレンダリングするかもしれない

---

## どう動くのか？

### Acceptヘッダー

クライアントは`Accept`ヘッダーで希望する形式を示します：

```
  ブラウザリクエスト：
  GET /dashboard
  Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8

  API/SPAリクエスト：
  GET /api/v1/users/me
  Accept: application/json

  AJAXリクエスト：
  GET /some-page
  X-Requested-With: XMLHttpRequest
```

### 判定ツリー

```
  受信リクエスト
  │
  ├── Acceptが"application/json"を含む？
  │   └── はい → JSONで応答
  │
  ├── X-Requested-With: XMLHttpRequest？
  │   └── はい → JSONで応答
  │
  ├── Authorization: Bearer ...？
  │   └── はい → JSONで応答
  │
  └── その他（text/htmlまたはAcceptヘッダーなし）
      └── HTMLまたはリダイレクトで応答
```

### 302がSPA fetch()を壊す理由

```
  ❌ コンテンツネゴシエーションなし：

  SPA fetch("/dashboard")
  │
  ├── volta: "未認証！302 → /login"
  ├── ブラウザ（裏側で）: "302？辿ろう。"
  ├── ブラウザ: "GET /login → 302 → Google OAuth"
  ├── ブラウザ: "GET accounts.google.com/..."
  └── fetch()が受け取る: GoogleのHTMLログインページ
      SPA: "このHTMLは何？JSONを期待していたのに！"
      結果: 白い画面、壊れたアプリ

  ✅ コンテンツネゴシエーションあり：

  SPA fetch("/dashboard", { headers: { "Accept": "application/json" }})
  │
  ├── volta: "未認証 + JSONを要求 → 401 JSON"
  └── fetch()が受け取る: { "error": { "code": "AUTHENTICATION_REQUIRED" } }
      SPA: "401？ログインモーダルを表示しよう。"
      結果: クリーンなユーザー体験
```

---

## volta-auth-proxyではどう使われているか？

### `dsl/protocol.yaml`での定義

```yaml
content_negotiation:
  rules:
    - condition: "Acceptヘッダーが'application/json'を含む"
      response: json

    - condition: "X-Requested-With: XMLHttpRequest"
      response: json

    - condition: "Authorization: Bearer ..."
      response: json

    - condition: "Accept: text/htmlまたはAcceptヘッダーなし"
      response: html_or_redirect

  reason: >
    SPA fetch()は302を自動的に辿る。ゲートウェイがGoogleログインに
    302を返すと、fetchはJSONエラーの代わりにGoogleのHTMLを受け取る。
```

### コンテンツネゴシエーションのための状態マシンガード

`auth-machine.yaml`は`request.accept_json`コンテキスト変数を使って分岐します：

```yaml
# UNAUTHENTICATED状態
login_browser:
  trigger: "GET /login"
  guard: "!request.accept_json"    # ブラウザ → Googleにリダイレクト
  actions:
    - { type: side_effect, action: create_oidc_flow }
    - { type: http, action: redirect, target: google_authorize_url }
  next: AUTH_PENDING

login_api:
  trigger: "GET /login"
  guard: "request.accept_json"      # SPA/API → JSONエラー
  actions:
    - { type: http, action: json_error, error_ref: AUTHENTICATION_REQUIRED }
  next: UNAUTHENTICATED
```

### グローバル遷移：ログアウト

```yaml
logout_browser:
  trigger: "POST /auth/logout"
  guard: "!request.accept_json"
  actions:
    - { type: http, action: redirect, target: "/login" }
  next: UNAUTHENTICATED

logout_api:
  trigger: "POST /auth/logout"
  guard: "request.accept_json"
  actions:
    - { type: http, action: json_ok }
  next: UNAUTHENTICATED
```

### `HttpSupport.java`での実装

```java
// HttpSupport.java
public static boolean acceptsJson(Context ctx) {
    String accept = ctx.header("Accept");
    if (accept != null && accept.contains("application/json")) return true;
    if (ctx.header("X-Requested-With") != null) return true;
    String auth = ctx.header("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) return true;
    return false;
}
```

---

## よくある間違いと攻撃

### 間違い1：JSONクライアントに302を返す

最もよくある間違いです。リダイレクトを返す前に必ずAcceptヘッダーをチェックすること。voltaはこれを[DSL](dsl.ja.md)で別々の遷移としてエンコードすることで、忘れることを不可能にしています。

### 間違い2：すべてのブラウザがAccept: text/htmlを送ると仮定

一部のブラウザ、古いHTTPライブラリ、プロキシはAcceptヘッダーをまったく送らないかもしれません。voltaは「Acceptヘッダーなし」を「text/html」と同じに扱います。ブラウザライクなクライアントに対する安全なデフォルトです。

### 間違い3：Content-Typeの混同

`Accept`はクライアントが欲しいもの。`Content-Type`はサーバーが送るもの。混同しないこと。voltaは`Accept`（リクエスト）をチェックし、それに応じて`Content-Type`（レスポンス）を設定します。

### 攻撃：Acceptヘッダーの操作

攻撃者が通常HTMLを返すエンドポイントに`Accept: application/json`を送り、JSONレスポンスがより多くの情報を漏らすことを期待するかもしれません。voltaは形式に関係なく同じ情報を返します -- 包装が違うだけです。

---

## さらに学ぶために

- [header.md](header.md) -- AcceptとContent-Typeを含むHTTPヘッダー。
- [dsl.md](dsl.md) -- コンテンツネゴシエーションがガード式としてどうエンコードされるか。
- [guard.md](guard.md) -- `request.accept_json`ガード変数。
- [state-machine.md](state-machine.md) -- ブラウザ vs APIの別々の遷移。
- [downstream-app.md](downstream-app.md) -- 下流アプリがレスポンス形式をどう扱うか。
