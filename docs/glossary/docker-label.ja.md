# Docker ラベル

[English version](docker-label.md)

---

## これは何？

Docker ラベルは Docker コンテナ、イメージ、ボリューム、ネットワークに付与されるキーバリュー形式のメタデータです。ラベルはコンテナの動作に影響しません -- 純粋に情報的です。しかし、他のツールがこれらのラベルを読んで行動できるため、ラベルはファイルなしで設定を行う強力な仕組みになります。

カンファレンスの名札のようなものです。名札はあなた自身を変えませんが、他の人にあなたとどう接するべきかを伝えます -- 名前、会社、役職。Docker ラベルはコンテナの名札です。Traefik、Prometheus、Watchtower などのツールにコンテナとどう接するべきかを伝えます。

現代のデプロイで Docker ラベルの最も一般的な用途は**リバースプロキシ設定**です。Nginx や Traefik の設定ファイルを別途書く代わりに、「このドメインのトラフィックをこのコンテナのこのポートにルーティングせよ」というラベルをコンテナに付けます。リバースプロキシがラベルを読み、自動的に設定します。

---

## なぜ重要？

ラベルはインフラストラクチャの **Configuration-as-Code** を可能にします。リバースプロキシ、監視、ログ記録、オーケストレーションツール用の個別設定ファイルを管理する代わりに、すべてを一箇所 -- Docker Compose ファイル -- で宣言します。

利点：

- **唯一の情報源**：コンテナの Compose ファイルにすべての設定が含まれる
- **設定ファイルのドリフトなし**：ラベルはコンテナ定義と共に移動。同期ずれが起きない
- **動的設定**：Traefik のようなツールがコンテナの変更を監視し、自動的に再設定
- **再起動不要**：新しいサービスを追加すると、Traefik が再起動なしで検知

ラベルなしで新サービスを追加するには：Traefik 設定ファイルの更新、Traefik のリロード、他のルートを壊していないことを祈る。ラベルがあれば、ラベル付きの新サービスを追加するだけで、Traefik が自動設定します。

---

## どう動くのか？

### ラベルの構文

```yaml
  # Docker Compose の場合：
  services:
    my-app:
      image: my-app:latest
      labels:
        - "com.example.description=My application"
        - "com.example.version=1.2.3"
        - "traefik.enable=true"
        - "traefik.http.routers.myapp.rule=Host(`app.example.com`)"
```

```bash
  # Docker CLI の場合：
  docker run --label com.example.version=1.2.3 my-app:latest
```

### ラベルの慣例

| プレフィックス | 所有者 | 例 |
|-------------|--------|-----|
| `com.example.*` | あなたの組織 | `com.example.team=platform` |
| `org.opencontainers.*` | OCI 標準 | `org.opencontainers.image.version=1.0` |
| `traefik.*` | Traefik プロキシ | `traefik.http.routers.app.rule=...` |
| `com.datadoghq.*` | Datadog | `com.datadoghq.ad.check_names=...` |
| `prometheus.*` | Prometheus | `prometheus.io/scrape=true` |

### Traefik のラベルベースルーティング

Traefik は Docker ラベルを読んでルーティングを設定するリバースプロキシです。これにより別の Traefik 設定ファイルが不要になります。

```
  インターネット
     │
     ▼
  ┌──────────────────────────────────┐
  │  Traefik（リバースプロキシ）       │
  │  Docker のラベルを監視            │
  └──────────────────────────────────┘
     │         │            │
     ▼         ▼            ▼
  ┌──────┐  ┌──────┐  ┌────────────┐
  │ App A │  │ App B │  │ volta-auth │
  │       │  │       │  │ -proxy     │
  │ label:│  │ label:│  │ label:     │
  │ Host  │  │ Host  │  │ Host       │
  │ (a.co)│  │ (b.co)│  │ (auth.co)  │
  └──────┘  └──────┘  └────────────┘
```

### Traefik がラベルを読む方法

```yaml
  # Traefik はこれらのラベルを見てルーティングルールを作成：

  labels:
    # このコンテナで Traefik を有効化
    - "traefik.enable=true"

    # "volta" という名前のルーターを作成
    - "traefik.http.routers.volta.rule=Host(`auth.example.com`)"

    # HTTPS を使用
    - "traefik.http.routers.volta.tls=true"

    # Let's Encrypt 証明書を自動生成
    - "traefik.http.routers.volta.tls.certresolver=letsencrypt"

    # コンテナ内のポート 8080 にルーティング
    - "traefik.http.services.volta.loadbalancer.server.port=8080"
```

これは Traefik 設定ファイルを書くのと同等です：

```toml
  [http.routers.volta]
    rule = "Host(`auth.example.com`)"
    service = "volta"
    [http.routers.volta.tls]
      certResolver = "letsencrypt"

  [http.services.volta.loadBalancer]
    [[http.services.volta.loadBalancer.servers]]
      url = "http://volta-auth-proxy:8080"
```

しかしラベルアプローチの方が優れています -- サービス定義と一緒にあり、別ファイルではないからです。

---

## volta-auth-proxy ではどう使われている？

volta は主に Traefik リバースプロキシ設定のために Docker ラベルを使用しています。ラベルは Traefik に外部トラフィックを volta コンテナにどうルーティングするかを伝えます。

### volta の Docker Compose ラベル

```yaml
  services:
    volta-auth-proxy:
      image: volta-auth-proxy:latest
      labels:
        # Traefik ルーティングを有効化
        - "traefik.enable=true"

        # auth.example.com をこのコンテナにルーティング
        - "traefik.http.routers.volta.rule=Host(`auth.example.com`)"

        # 自動証明書付き HTTPS を有効化
        - "traefik.http.routers.volta.tls=true"
        - "traefik.http.routers.volta.tls.certresolver=letsencrypt"

        # volta はポート 8080 でリスニング
        - "traefik.http.services.volta.loadbalancer.server.port=8080"

        # Traefik 用ヘルスチェック
        - "traefik.http.services.volta.loadbalancer.healthcheck.path=/healthz"
        - "traefik.http.services.volta.loadbalancer.healthcheck.interval=10s"
```

### ラベルが volta にうまく機能する理由

| 観点 | ラベルアプローチ | 設定ファイルアプローチ |
|------|----------------|---------------------|
| **単一ファイル** | すべてが docker-compose.yml に | docker-compose.yml + traefik.yml + dynamic.yml |
| **サービス追加** | 新サービスにラベルを追加 | Traefik 設定を編集、Traefik をリロード |
| **可視性** | 設定がサービスの隣 | 設定が別ファイル |
| **バージョン管理** | 追跡するファイルは1つ | 同期を保つ複数ファイル |

### 完全なデプロイの全体像

```
  docker-compose.yml
  ┌──────────────────────────────────────────┐
  │  services:                               │
  │    traefik:                              │
  │      image: traefik:v3                   │
  │      ports: ["80:80", "443:443"]         │
  │      volumes:                            │
  │        - /var/run/docker.sock:/var/run/  │
  │          docker.sock                     │
  │                                          │
  │    volta-auth-proxy:                     │
  │      image: volta-auth-proxy:latest      │
  │      labels:                             │
  │        traefik.enable: true              │
  │        traefik.http.routers.volta.rule:  │
  │          Host(`auth.example.com`)        │
  │      volumes:                            │
  │        - volta-data:/app/data            │
  │                                          │
  │  volumes:                                │
  │    volta-data:                           │
  └──────────────────────────────────────────┘
```

ラベル + ボリューム + ヘルスチェックが完全なデプロイ設定を形成し、すべてが1つの Compose ファイルに。

---

## よくある間違いと攻撃

### 間違い1：Docker ソケットを保護せずに公開する

Traefik は Docker ソケット（`/var/run/docker.sock`）経由でラベルを読みます。攻撃者がソケットにアクセスできれば、すべてのコンテナを制御できます。本番では Traefik の Docker ソケットプロキシまたは読み取り専用ソケットアクセスを使いましょう。

### 間違い2：ラベル名のタイプミス

ラベルは文字列です。`traefik.http.router.volta.rule`（"routers" の "s" が抜けている）のようなタイプミスはサイレントに失敗します。Traefik は不明なラベルをエラーなしで無視します。変更後は必ずルーティングをテストしましょう。

### 間違い3：traefik.enable=true を忘れる

デフォルトで Traefik はすべてのコンテナを公開するか、しないか（設定次第）です。ベストプラクティス：Traefik 設定で `exposedByDefault: false` に設定し、ルーティングが必要なサービスに明示的に `traefik.enable=true` を追加。

### 間違い4：サービス間のラベル競合

同じルーター名（`traefik.http.routers.app.rule`）を持つ2つのサービスは競合します。各サービスに一意のルーター名を使いましょう。

### 間違い5：ヘルスチェックラベルを使わない

ヘルスチェックラベルがないと、Traefik は準備ができていないコンテナにトラフィックをルーティングします。`traefik.http.services.X.loadbalancer.healthcheck.path` を追加して、Traefik が健全なインスタンスにのみルーティングするようにしましょう。

---

## さらに学ぶ

- [docker-volume.md](docker-volume.md) -- ラベルと並んで設定される永続ストレージ。
- [health-check.md](health-check.md) -- Traefik がラベル経由でヘルスチェックに使うエンドポイント。
- [Traefik Docker ドキュメント](https://doc.traefik.io/traefik/providers/docker/) -- Traefik が Docker ラベルを読む方法。
- [OCI Image Spec - ラベル](https://github.com/opencontainers/image-spec/blob/main/annotations.md) -- 標準的なラベルの慣例。
