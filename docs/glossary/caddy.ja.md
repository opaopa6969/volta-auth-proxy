# Caddy

[English version](caddy.md)

---

## これは何？

Caddyは、Go言語で書かれたモダンなオープンソースWebサーバーで、最大の特徴は**デフォルトで自動HTTPS**であることです。ドメインをCaddyに向けると、設定なしでLet's EncryptからTLS証明書を自動的に取得・更新します。certbotもcronジョブも手動更新も不要です。

Caddyは、すべてのゲストの手続きを自動で処理するホテルのコンシェルジュのようなものです。名前を言うだけで、コンシェルジュがカードキー、部屋の割り当て、チェックアウト時間をすべて処理します。他のホテル（[nginx](nginx.md)）ではフォームを自分で記入する必要があります。[Traefik](traefik.md)も自動チェックインがありますが、コンシェルジュデスクはより複雑です。

Caddyの設定言語（「Caddyfile」）は意図的にミニマルで人間が読みやすいものです。nginxがページ数分のディレクティブを必要とし、TraefikがYAML/TOMLファイルやDockerラベルを必要とするのに対し、Caddyはしばしば数行で済みます。

---

## なぜ重要なのか？

TLSの設定ミスは、インターネットで最もよくあるセキュリティ問題の一つです。期限切れの証明書、弱い暗号スイート、HTTPからHTTPSへのリダイレクト漏れ -- TLSの設定は従来手動でミスが起きやすいため、これらは常に発生しています。

Caddyはこの問題カテゴリ全体を排除します。HTTPSは有効にする機能ではなく、明示的に無効にしなければならないデフォルトです。

volta-auth-proxyユーザーにとって、Caddyはサポートされるリバースプロキシの選択肢として重要です。すでにCaddyを使用しているか、そのシンプルさを好む場合、Traefikに切り替える必要はありません。Caddyの`forward_auth`ディレクティブは、voltaが必要とするForwardAuth機能を提供します。

---

## どう動くのか？

### Caddyfile

Caddyの設定は競合製品と比べて劇的にシンプルです：

```caddyfile
app.example.com {
    forward_auth volta:7070 {
        uri /verify
        copy_headers X-Volta-User X-Volta-Tenant
    }
    reverse_proxy backend:8080
}
```

これだけです。5行です。Caddyは以下を行います：
1. `app.example.com`のTLS証明書を自動的に取得
2. HTTPからHTTPSへリダイレクト
3. すべてのリクエストに対して、voltaの`/verify`エンドポイントを呼び出す
4. voltaが200を返したら、IDヘッダーをコピーしてバックエンドに転送
5. voltaが401/302を返したら、それをクライアントに返す

同等の[nginx](nginx.md)設定（15行以上）や[Traefik](traefik.md)のDockerラベルと比較してみてください。

### forward_authディレクティブ

`forward_auth`ディレクティブは、TraefikのForwardAuthミドルウェアやnginxの`auth_request`モジュールに相当するCaddyの機能です。

```
  ブラウザ ──► Caddy ──► forward_auth ──► volta-auth-proxy
                           │
                           ├── 200 OK → ヘッダーコピー → reverse_proxy → バックエンド
                           └── 401/302 → ブラウザに返す
```

設定オプション：

| オプション | 説明 |
|-----------|------|
| `uri` | 認証サーバーで呼び出すパス（例：`/verify`） |
| `copy_headers` | 認証レスポンスからアップストリームリクエストにコピーするヘッダー |
| `copy_headers {header} {rename}` | ヘッダーをコピーしてリネーム |
| `header_up` | 認証リクエストにヘッダーを追加（例：元のURI） |

### 自動HTTPS

CaddyはACMEプロトコルを使って自動的に：

1. ドメインが証明書を必要としていることを検出
2. Let's Encrypt（またはZeroSSL）から証明書を要求
3. ACMEチャレンジ（HTTP-01またはTLS-ALPN-01）を完了
4. 証明書をインストール
5. 有効期限前に更新（通常30日前）

これはバックグラウンドで静かに行われます。設定は不要です。

### Caddy vs Traefik vs nginx

| 機能 | Caddy | [Traefik](traefik.md) | [nginx](nginx.md) |
|------|-------|---------|-------|
| 自動HTTPS | デフォルト（常にON） | 組み込み（設定が必要） | certbotが必要 |
| ForwardAuth | `forward_auth`ディレクティブ | ForwardAuthミドルウェア | `auth_request`モジュール |
| 設定のシンプルさ | 優秀（Caddyfile） | 中程度（ラベル/YAML） | 複雑（独自構文） |
| 自動ディスカバリ | 限定的（プラグイン経由） | ネイティブ（Docker、K8s） | なし |
| パフォーマンス | 良好 | 良好 | 優秀 |
| 開発言語 | Go | Go | C |

---

## volta-auth-proxy ではどう使われている？

volta-auth-proxyは、[Traefik](traefik.md)（推奨）および[nginx](nginx.md)と並んで、Caddyを代替リバースプロキシとしてサポートしています。

### 統合セットアップ

```caddyfile
# volta + アプリのCaddyfile
{
    # グローバルオプション（任意）
    email admin@example.com
}

# 保護されたアプリケーション
app.example.com {
    forward_auth volta:7070 {
        uri /verify
        copy_headers X-Volta-User X-Volta-Tenant X-Volta-Roles
    }
    reverse_proxy backend:8080
}

# voltaのエンドポイント（ログイン、コールバック）-- forward_authの背後に置かない
auth.example.com {
    reverse_proxy volta:7070
}
```

### Caddyを選ぶべきケース

- 可能な限りシンプルな設定が欲しい
- ゼロエフォートの自動HTTPSが重要
- Dockerの自動ディスカバリが不要（インフラが比較的静的）
- 機能の密度より読みやすい設定を重視
- すでにCaddyユーザーである

### Caddyを選ぶべきでないケース

- Dockerラベルベースの自動ディスカバリが必要（[Traefik](traefik.md)を使用）
- 最大限の生スループットが必要（[nginx](nginx.md)を使用）
- モニタリング用の組み込みダッシュボードが必要（[Traefik](traefik.md)を使用）

---

## よくある間違いと攻撃

### 間違い1：HTTPSが自動であることを忘れる

CaddyはデフォルトでHTTPSを有効にします。`localhost`でローカルテスト中、Caddyは自己署名証明書を生成します。ローカル環境でTLSを想定していない開発者を混乱させることがあります。開発用にプレーンHTTPが必要な場合は、Caddyfileで明示的に`http://`を使用してください。

### 間違い2：copy_headersを使わない

`forward_auth`を設定しても`copy_headers`を忘れると、バックエンドはvoltaのIDヘッダーを受け取れません。リクエストは認証されますが、バックエンドはユーザーが誰かわかりません。

### 間違い3：voltaのログインエンドポイントをforward_authの背後に置く

ログインページ、コールバックURL、パブリックエンドポイントはforward_authの背後に置いてはいけません。さもないと、まだ認証されていないためユーザーがログインページに到達できません -- 鶏と卵の問題です。

### 間違い4：本番環境でCaddyのJSON APIを無視する

Caddyにはランタイムで設定を変更できる強力な管理APIがあります。このAPIが保護なしで公開されていると、攻撃者がプロキシ全体を再設定できます。管理APIはlocalhostのみにバインドするか、無効にしてください。

### 攻撃：証明書透明性の監視

Caddyは自動的に証明書をリクエストするため、ドメイン名がCertificate Transparencyログに表示されます。攻撃者はこれらのログを監視して新しいサービスを発見します。これはCaddy固有の問題ではありませんが、自動証明書プロビジョニングにより内部ステージングドメインが露出しやすくなります。

---

## さらに学ぶ

- [Caddy公式ドキュメント](https://caddyserver.com/docs/) -- 完全なリファレンス。
- [Caddy forward_authディレクティブ](https://caddyserver.com/docs/caddyfile/directives/forward_auth) -- voltaが使用する特定の機能。
- [traefik.md](traefik.md) -- ForwardAuth付きのvolta推奨プロキシ。
- [nginx.md](nginx.md) -- `auth_request`を持つ従来の代替手段。
- [forwardauth.md](forwardauth.md) -- ForwardAuthパターンの説明。
- [reverse-proxy.md](reverse-proxy.md) -- リバースプロキシとは何か、なぜ必要なのか。
