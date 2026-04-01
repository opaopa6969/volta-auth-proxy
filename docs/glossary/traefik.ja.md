# Traefik

[English version](traefik.md)

---

## これは何？

Traefik（「トラフィック」と発音）は、クラウドネイティブ環境向けに設計されたモダンなリバースプロキシおよびロードバランサーです。リバースプロキシはインターネットとアプリケーションサーバーの間に立ち、受信リクエストを適切な宛先にルーティングします。Traefikの最大の特徴は**自動サービスディスカバリ**です。Docker、Kubernetesなどのオーケストレーターを監視し、サービスが起動・停止すると設定ファイルを編集することなく自動的に構成を更新します。

大きなオフィスビルのスマートな受付係を想像してください。新しい会社が405号室に入居すると、受付係は自動的にディレクトリを更新し、訪問者をそこに案内し始めます。手動で受付に電話してリストを更新する必要はありません -- ドアの新しい表札に気づくだけです。

[nginx](nginx.md)のような従来のリバースプロキシは、印刷されたリストだけに従う受付係のようなものです。テナントが入退去するたびに、誰かが新しいリストを印刷して渡す必要があります。

---

## なぜ重要なのか？

モダンなデプロイ環境では、サービスは常に変化します。Dockerコンテナは再起動し、スケールアップ・ダウンします。コンテナが変わるたびにプロキシの設定ファイルを手動で編集するのは、面倒でミスが起きやすい作業です。Traefikは、Dockerのラベル（またはKubernetesのアノテーション）を直接読み取ることでこれを解消します。

Traefikはまた、**ForwardAuth**をネイティブにサポートしています。これは、Traefikが外部サービスに「このリクエストを許可すべきか？」と尋ねてからリクエストを転送するパターンです。これはまさに[volta-auth-proxy](forwardauth.md)の動作方法です -- Traefikはすべての認証判断をvoltaに委任します。

Traefik（またはそれに類するもの）がなければ、認証ロジックをすべてのサービスに埋め込むか、従来のプロキシを手動で設定して認証エンドポイントを呼び出す必要があります。TraefikはForwardAuthパターンを一級市民の、十分にドキュメント化された機能として提供しています。

---

## どう動くのか？

### アーキテクチャ概要

```
  インターネット
       │
       ▼
  ┌──────────────┐
  │   Traefik    │  （リバースプロキシ、エントリポイント）
  │              │
  │  1. リクエスト│
  │     受信     │
  │              │
  │  2. 認証     │──── ForwardAuth ────► volta-auth-proxy
  │     チェック  │◄─── 200 OK ─────────  （または 401/302）
  │              │
  │  3. リクエスト│──── 200の場合のみ ──► あなたのアプリ
  │     転送     │
  └──────────────┘
```

### 主要な概念

| 概念 | 説明 |
|------|------|
| **エントリポイント** | Traefikがリッスンするポート（例：`:80`、`:443`） |
| **ルーター** | 受信リクエストに一致するルール（例：`Host(\`app.example.com\`)`） |
| **サービス** | リクエストを処理するバックエンド（Dockerコンテナ） |
| **ミドルウェア** | リクエスト/レスポンスパイプラインを変更するもの（例：ForwardAuth、レート制限、ヘッダー） |
| **プロバイダー** | Traefikがサービスを発見する場所（Docker、Kubernetes、ファイルなど） |

### ForwardAuthミドルウェア

ForwardAuthはTraefikに次のことを伝えるミドルウェアです：「このリクエストを転送する前に、まず別のサービスに送信してください。そのサービスが200で応答したら続行します。それ以外（401、302）で応答したら、そのレスポンスをクライアントに返します。」

```yaml
# docker-compose ラベルの例
labels:
  - "traefik.http.middlewares.volta-auth.forwardauth.address=http://volta:7070/verify"
  - "traefik.http.middlewares.volta-auth.forwardauth.authResponseHeaders=X-Volta-User,X-Volta-Tenant"
  - "traefik.http.routers.myapp.middlewares=volta-auth"
```

これはTraefikに以下を指示します：
1. `http://volta:7070/verify` を呼び出す `volta-auth` ミドルウェアを作成する
2. voltaが200を返したら、`X-Volta-User` と `X-Volta-Tenant` ヘッダーをアップストリームリクエストにコピーする
3. このミドルウェアを `myapp` ルーターにアタッチする

### Dockerによる自動ディスカバリ

TraefikはDockerソケットを監視してコンテナイベントを検知します。Traefikラベル付きのコンテナが起動すると、Traefikは自動的にルーター、サービス、ミドルウェアを作成します。コンテナが停止すると、それらを削除します。リロードも再起動も不要です。

### 他のプロキシとの比較

| 機能 | Traefik | [nginx](nginx.md) | [Caddy](caddy.md) |
|------|---------|-------|-------|
| 自動ディスカバリ（Docker） | ネイティブ、一級市民 | サードパーティツールが必要 | 限定的 |
| ForwardAuth | 組み込みミドルウェア | `auth_request` モジュール | `forward_auth` ディレクティブ |
| 自動HTTPS（Let's Encrypt） | 組み込み | certbotが必要 | 組み込み（デフォルトON） |
| 設定方式 | ラベル / YAML / TOML | 独自の設定言語 | Caddyfile / JSON |
| ダッシュボード | 組み込みWeb UI | サードパーティ | なし |
| パフォーマンス（生スループット） | 良好 | 優秀 | 良好 |
| 学習コスト | 中程度 | 高い（高度な設定） | 低い |
| 成熟度 | 2015年〜 | 2004年〜 | 2015年〜 |

---

## volta-auth-proxy ではどう使われている？

Traefikはvolta-auth-proxyの**推奨リバースプロキシ**です。voltaが依存するForwardAuthパターン全体が、Traefikを主要な統合ターゲットとして設計されました。

### 推奨スタック

```
  ブラウザ ──► Traefik ──► volta-auth-proxy（ForwardAuth）
                  │
                  ├──► アプリサービスA（保護対象）
                  ├──► アプリサービスB（保護対象）
                  └──► パブリックサービスC（認証ミドルウェアなし）
```

voltaのDocker Composeサンプルは、デフォルトでTraefikを使用します。セットアップに必要なのは：

1. **Traefik** をエッジプロキシとして（TLS、ルーティングを処理）
2. **volta-auth-proxy** をForwardAuthバックエンドとして（認証を処理）
3. **アプリサービス** にvolta-authミドルウェアをアタッチするTraefikラベル

### なぜ代替手段よりTraefikか？

voltaがTraefikを推奨する理由：

- **ForwardAuthがネイティブで十分にドキュメント化されたミドルウェア**である -- 後付けではない
- **Dockerラベルベースの設定**により、新しいサービスへの認証追加が1行で済む
- **ヘッダー転送**（`authResponseHeaders`）がvoltaのIDヘッダーをアップストリームサービスにシームレスに渡す
- **コミュニティ**にForwardAuthパターンの豊富なドキュメントがある

ただし、voltaは[nginx](nginx.md)（`auth_request`）や[Caddy](caddy.md)（`forward_auth`）でも動作します。設定の詳細はそれぞれの記事を参照してください。

---

## よくある間違いと攻撃

### 間違い1：Dockerソケットを読み取り専用なしで公開

TraefikはサービスをディスカバリするためにDockerソケット（`/var/run/docker.sock`）へのアクセスが必要です。`:ro`（読み取り専用）なしでマウントすると、侵害されたTraefikインスタンスがコンテナを作成・破壊できてしまいます。常に`:ro`を使用してください。

### 間違い2：Traefikダッシュボードを保護しない

Traefikの組み込みダッシュボードは、すべてのルーター、サービス、ミドルウェアを表示します。認証なしで公開すると、攻撃者がインフラ全体をマッピングできます。本番環境では無効にするか、volta-auth-proxyで保護してください。

### 間違い3：authResponseHeadersを忘れる

ForwardAuthを設定しても`authResponseHeaders`を設定しないと、アップストリームサービスはIDヘッダー（`X-Volta-User`など）を受け取れません。リクエストは認証されますが、アプリはユーザーが誰かわかりません。

### 間違い4：プロキシのバイパス

アプリサービスが直接到達可能な場合（例：ホストにポートを公開）、ユーザーはTraefikを完全にバイパスしてForwardAuthをスキップできます。アプリサービスはポートを公開せず、内部Dockerネットワーク上に置いてください。

### 攻撃：ヘッダーインジェクション

TraefikがForwardAuthを呼び出す前に受信した`X-Volta-User`ヘッダーを除去しない場合、攻撃者が偽のIDヘッダーを送信できます。TraefikのForwardAuthミドルウェアはこれらのヘッダーを認証レスポンスのもので置き換えますが、設定を誤ると脆弱になる可能性があります。

---

## さらに学ぶ

- [Traefik公式ドキュメント](https://doc.traefik.io/traefik/) -- 包括的なリファレンス。
- [Traefik ForwardAuthミドルウェア](https://doc.traefik.io/traefik/middlewares/http/forwardauth/) -- voltaが使用する特定の機能。
- [forwardauth.md](forwardauth.md) -- ForwardAuthパターンの一般的な仕組み。
- [nginx.md](nginx.md) -- `auth_request`を持つ代替プロキシ。
- [caddy.md](caddy.md) -- `forward_auth`を持つ代替プロキシ。
- [reverse-proxy.md](reverse-proxy.md) -- リバースプロキシとは何か、なぜ必要なのか。
- [docker.md](docker.md) -- voltaとTraefikがどのように一緒にデプロイされるか。
