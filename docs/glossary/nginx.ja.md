# nginx

[English version](nginx.md)

---

## これは何？

nginx（「エンジンエックス」と発音）は、2000年代半ばからインターネットで最も広く使われているサーバーの一つである、高性能Webサーバーおよびリバースプロキシです。Igor Sysoevが「C10K問題」（1万の同時接続を処理する）を解決するために開発し、安定性、省メモリ、そして生のスピードで知られています。

nginxは、忙しい交差点のベテラン交通整理員のようなものです。何十年も交通を誘導し、あらゆるコツを知っており、膨大な量を処理できます。信頼性が高く速いのですが、交通パターンを変更するには新しい書面のルールブックを渡す必要があります -- 自分で判断はしてくれません。

対照的に、[Traefik](traefik.md)は無線機を持った交通整理員のようなもので、新しい道路ができると自動的に対応します。nginxには明示的に指示する必要があります。

---

## なぜ重要なのか？

nginxはインターネットの大部分を支えています。各種調査によると、全ウェブサイトの30〜40%がnginxで動いています。多くの開発者がすでにインフラにnginxを持っています。そのような場合、volta-auth-proxyを使うためだけにTraefikに切り替える必要はありません。

nginxは、TraefikのForwardAuthと同様に動作する`auth_request`モジュールをサポートしています。メインのリクエストを許可する前に、認証サービスにサブリクエストを送信します。これにより、volta-auth-proxyはnginxとすぐに統合できます。

nginxが重要なのは、利用可能な最も実戦検証済みのリバースプロキシだからです。生のパフォーマンス、豊富なドキュメント、大規模な実績のある安定性が必要な場合、nginxがデフォルトの選択肢です。

---

## どう動くのか？

### コアアーキテクチャ

nginxは**イベント駆動の非同期**アーキテクチャを使用します。接続ごとに新しいスレッドやプロセスを生成する（Apacheのように）のではなく、少数のワーカープロセスがノンブロッキングI/Oを使って数千の接続を処理します。

```
  ┌─────────────────────────────────────────┐
  │               nginx                      │
  │                                          │
  │  マスタープロセス（設定読み込み、管理）      │
  │       │                                  │
  │       ├── ワーカープロセス1（〜1万接続を処理）
  │       ├── ワーカープロセス2（〜1万接続を処理）
  │       └── ワーカープロセスN              │
  └─────────────────────────────────────────┘
```

### auth_requestモジュール

`auth_request`モジュールは、TraefikのForwardAuthに相当するnginxの機能です。メインリクエストを処理する前に、内部ロケーションにサブリクエストを送信します。

```nginx
server {
    listen 80;
    server_name app.example.com;

    # /app/ へのすべてのリクエストは認証を通過する必要がある
    location /app/ {
        auth_request /volta-verify;
        auth_request_set $volta_user $upstream_http_x_volta_user;
        auth_request_set $volta_tenant $upstream_http_x_volta_tenant;

        proxy_set_header X-Volta-User $volta_user;
        proxy_set_header X-Volta-Tenant $volta_tenant;
        proxy_pass http://backend:8080;
    }

    # 内部ロケーション -- volta-auth-proxyを呼び出す
    location = /volta-verify {
        internal;
        proxy_pass http://volta:7070/verify;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-Original-URI $request_uri;
        proxy_set_header X-Forwarded-Host $host;
    }
}
```

### auth_requestの動作

```
  ブラウザ ──► nginx ──► /volta-verify（voltaへのサブリクエスト）
                           │
                           ├── 200 OK → nginx は proxy_pass へ進む → バックエンド
                           ├── 401     → nginx は 401 をブラウザに返す
                           └── 302     → nginx は 302 をブラウザに返す（ログインリダイレクト）
```

### TraefikおよびCaddyとの比較

| 機能 | nginx | [Traefik](traefik.md) | [Caddy](caddy.md) |
|------|-------|---------|-------|
| 認証サブリクエスト | `auth_request`モジュール | ForwardAuthミドルウェア | `forward_auth`ディレクティブ |
| 自動ディスカバリ | なし | あり（Docker、K8s） | 限定的 |
| 設定方式 | `nginx.conf`（独自構文） | ラベル / YAML | Caddyfile / JSON |
| 自動HTTPS | certbotが必要 | 組み込み | 組み込み（デフォルト） |
| パフォーマンス | 優秀（業界ベンチマーク） | 良好 | 良好 |
| メモリ使用量 | 非常に少ない | 少ない | 少ない |
| コミュニティ/エコシステム | 巨大 | 大きい | 成長中 |
| ホットリロード | `nginx -s reload` | 自動 | 自動 |

---

## volta-auth-proxy ではどう使われている？

volta-auth-proxyは、Traefikの代替としてnginxと連携します。Traefikが推奨プロキシですが、多くの本番環境ではすでにnginxが稼働しており、認証のためだけにプロキシを切り替えるのは現実的ではありません。

### 統合パターン

統合はnginxの`auth_request`モジュールを使用します：

1. リクエストがnginxに到着
2. nginxがvoltaの`/verify`エンドポイントにサブリクエストを送信
3. voltaがセッションCookieを確認し、200（認証済み）または401/302（未認証）で応答
4. 200の場合、nginxはvoltaのIDヘッダー（`X-Volta-User`、`X-Volta-Tenant`）をコピーしてリクエストをバックエンドに転送
5. 401/302の場合、nginxはそのレスポンスをブラウザに返す

### Traefikとの主な違い

- **手動設定**：保護する各ロケーションについて`nginx.conf`ルールを記述する必要がある。自動ディスカバリなし。
- **ヘッダー転送**：明示的な`auth_request_set`と`proxy_set_header`ディレクティブが必要（Traefikの`authResponseHeaders`より冗長）。
- **リロードが必要**：新しいサービスを追加するには設定を編集して`nginx -s reload`を実行する必要がある。

### TraefikよりnginxをChooseするケース

- すでに本番環境にnginxがあり、別のプロキシを追加したくない
- 最大限の生スループットが必要（nginxは一貫してベンチマークが上）
- インフラが頻繁に変わらない（静的設定で問題ない）
- GeoIP、高度なレート制限、Luaスクリプティングなどの機能が必要

---

## よくある間違いと攻撃

### 間違い1：auth_requestモジュールの有効化を忘れる

`auth_request`モジュールは、デフォルトでnginxにコンパイルされていない場合があります。一部のディストリビューションでは、`nginx-extras`のインストールまたは`--with-http_auth_request_module`でのコンパイルが必要です。存在しない場合、ディレクティブは静かに失敗します。

### 間違い2：verifyロケーションをinternalとしてマークしない

`/volta-verify`ロケーションには`internal;`ディレクティブが必要です。これがないと、外部クライアントが直接呼び出すことができ、情報漏洩や意図したフローのバイパスにつながる可能性があります。

### 間違い3：リクエストボディを認証エンドポイントに転送する

サブリクエストに元のリクエストボディを含めるべきではありません。常に`proxy_pass_request_body off;`と`proxy_set_header Content-Length "";`を設定してください。大きなPOSTボディを認証エンドポイントに送信すると、リソースが無駄になりタイムアウトの原因になります。

### 間違い4：auth_requestエラーを処理しない

voltaが到達不能な場合、nginxはデフォルトで500エラーを返します。`error_page 500 502 503 504 /auth-error.html;`を追加してフレンドリーなエラーページを表示し、認証エンドポイントの障害をアラートすることを検討してください。

### 攻撃：リクエストスマグリング

nginxとバックエンドサーバーが、あるHTTPリクエストの終了と次の開始について意見が一致しないことがあります（HTTPリクエストスマグリング）。nginxを最新に保ち、適切な`Connection: close`ハンドリングでHTTP/1.1を使用してこれを軽減してください。

---

## さらに学ぶ

- [nginx公式ドキュメント](https://nginx.org/en/docs/) -- 完全なリファレンス。
- [nginx auth_requestモジュール](https://nginx.org/en/docs/http/ngx_http_auth_request_module.html) -- サブリクエストモジュールの仕組み。
- [traefik.md](traefik.md) -- ForwardAuth付きのvolta推奨プロキシ。
- [caddy.md](caddy.md) -- forward_auth付きの別の代替プロキシ。
- [forwardauth.md](forwardauth.md) -- ForwardAuthパターンの説明。
- [reverse-proxy.md](reverse-proxy.md) -- リバースプロキシとは何か。
