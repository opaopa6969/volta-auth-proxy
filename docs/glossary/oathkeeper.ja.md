# Oathkeeper（Ory Oathkeeper）

[English version](oathkeeper.md)

---

## これは何？

Ory Oathkeeperは、Oryエコシステムのオープンソースのアイデンティティ・アクセスプロキシです。アプリケーションの前に立ち、設定可能なルールに基づいて受信HTTPリクエストを許可するか拒否するかを決定します。JWTの検証、Cookieの確認、外部認可サービスの呼び出し、ダウンストリームへ転送する前のリクエスト変更（ヘッダーの追加、トークンの書き換え）が可能です。

オフィスビルの各ドアに配置された訓練された警備員のようなものです。警備員にはルールブックがあります：「有効なバッジがあれば通す。手にスタンプを押す。期限切れのバッジなら受付に送る。バッジがなければ追い返す。」Oathkeeperがその警備員であり、ルールブックはあなたが書くJSONまたはYAMLのルールセットです。

---

## なぜ重要なのか？

OathkeeperはOryスタック（アイデンティティ用のOry Kratos、OAuth2用のOry Hydra、権限用のOry Keto）のアクセスプロキシコンポーネントです。セルフホスト型の認証ソリューションを評価するとき、Oryスタックは[Keycloak](keycloak.ja.md)や[Auth0](auth0.ja.md)と並んで一般的な候補です。

Oathkeeperを理解することで、volta-auth-proxyが既存のプロキシを採用せず、独自の[ForwardAuth](forwardauth.ja.md)を実装した理由が見えてきます。

---

## Oathkeeperの仕組み

Oathkeeperは4つのステージのパイプラインでリクエストを処理します：

```
  受信リクエスト
       │
       ▼
  ┌──────────────────┐
  │ 1. Authenticator  │  「あなたは誰？」
  │    (cookie,       │  JWT検証、Cookie確認、API呼び出しなど
  │     jwt, oauth2,  │
  │     anonymous...) │
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐
  │ 2. Authorizer     │  「許可されているか？」
  │    (allow, deny,  │  Ory Ketoで権限確認、または静的な許可/拒否
  │     keto_engine)  │
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐
  │ 3. Mutator        │  「ダウンストリームに何を見せるか？」
  │    (header, cookie,│  X-User-Idヘッダー追加、JWT発行、Cookie設定
  │     id_token...)  │
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐
  │ 4. Error Handler  │  「失敗時にどうするか？」
  │    (redirect, json)│  ログインにリダイレクト、401 JSON返却など
  └──────────────────┘
```

各ステージはルールごとに設定されます。1つのOathkeeperデプロイメントに何百ものルールがあり、それぞれ異なるauthenticator、authorizer、mutatorを持つことができます。

---

## voltaのForwardAuthとの比較

| 観点 | Oathkeeper | volta-auth-proxy |
|------|-----------|-----------------|
| **アーキテクチャ** | 独立したプロキシ（別プロセス） | 認証サービス自体に組み込み |
| **ルール定義** | ルートごとのJSON/YAMLルールファイル | `volta-config.yaml`のアプリレベル`allowed_roles` |
| **認証** | プラガブルなauthenticator（JWT、Cookie、OAuth2、anonymous） | セッションCookieのみ（1つの経路、深く検証） |
| **認可** | プラガブルなauthorizer（Ory Keto、allow/deny） | アプリごとのロールベース（設定の`allowed_roles`） |
| **ヘッダー変更** | 設定可能なmutator | 固定の`X-Volta-*`ヘッダーセット |
| **テナント認識** | なし（テナントは概念として存在しない） | ネイティブなマルチテナント（リクエストごとにテナント解決） |
| **JWT発行** | `id_token` mutatorでJWT発行可 | ユーザー/テナント/ロールクレーム付きRS256 JWT発行 |
| **依存関係** | フルスタックにはOry Kratos + Hydra + Keto必要 | PostgreSQLのみ |
| **設定** | 数十のルール、各4ステージパイプライン | YAMLファイル1つ + `.env` |

---

## voltaがOathkeeperを使わない理由

### 1. 4つのピースの1つに過ぎない

Oathkeeper自体はユーザー認証を処理しません。Ory Kratos（アイデンティティ/ログイン）、Ory Hydra（OAuth2/OIDC）、Ory Keto（権限）に委譲します。Oryスタック全体を運用するには4つの別々のサービスを操作することを意味します：

```
  Oryスタック（完全版）：
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │ Oathkeeper   │  │ Kratos       │  │ Hydra        │  │ Keto         │
  │ (プロキシ)   │  │ (ID管理)     │  │ (OAuth2)     │  │ (権限)       │
  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘
       +                  +                 +                  +
  ┌─────────────────────────────────────────────────────────────────────┐
  │ PostgreSQL（共有または別々のデータベース）                          │
  └─────────────────────────────────────────────────────────────────────┘

  voltaスタック：
  ┌──────────────┐
  │ volta-auth-  │
  │ proxy        │
  │ (すべて)     │
  └──────────────┘
       +
  ┌──────────────┐
  │ PostgreSQL   │
  └──────────────┘
```

voltaはすべてを1つのプロセスに収めることを選びました。デプロイするもの1つ、監視するもの1つ、理解するもの1つ。

### 2. ネイティブなマルチテナンシーがない

Oathkeeperにはテナントの概念がありません。テナントごとではなく、URLパターンごとにルールを処理します。マルチテナントな挙動（リクエストがどのテナントに属するかの解決、テナントスコープのロール確認、テナント固有のJWT発行）が必要な場合は、そのロジックを自分で構築する必要があります。voltaのForwardAuthにはテナント解決がすべてのリクエストに組み込まれています。

### 3. 設定のオーバーヘッド

1つのルートに対するOathkeeperの典型的なルールファイル：

```json
{
  "id": "wiki-app-rule",
  "upstream": { "url": "http://wiki-app:8080" },
  "match": {
    "url": "https://wiki.example.com/<**>",
    "methods": ["GET", "POST", "PUT", "DELETE"]
  },
  "authenticators": [
    { "handler": "cookie_session",
      "config": { "check_session_url": "http://kratos:4433/sessions/whoami" } }
  ],
  "authorizer": { "handler": "allow" },
  "mutators": [
    { "handler": "header",
      "config": { "headers": { "X-User-Id": "{{ print .Subject }}" } } }
  ],
  "errors": [
    { "handler": "redirect",
      "config": { "to": "https://auth.example.com/login" } }
  ]
}
```

これをすべてのアプリのすべてのルートに掛け算します。さらにKratosの設定、Hydraの設定、Ketoのポリシーも加えます。voltaはこれをすべて次の内容に置き換えます：

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

### 4. voltaのForwardAuthは「もっと知っている」から簡単

Oathkeeperは汎用的で、どんな認証システムでも動作します。その汎用性には設定が必要です。voltaのForwardAuthは特化型で、セッション、テナント、ロール、JWTについて知っています。なぜなら、voltaが認証システムそのものだからです。プロキシとアイデンティティロジックの両方を持っていれば、そもそも別のプロキシは必要ありません。

---

## Oathkeeperが適している場合

- すでにOryスタック（Kratos + Hydra + Keto）を使っている
- 複数の異なる認証バックエンドで動作するプロキシが必要
- ルートごとに異なる認証戦略を持つ詳細なルールが必要
- マイクロサービスの運用に慣れた運用チームがいる

---

## voltaがより適している場合

- 4つではなく1つのサービスが欲しい
- マルチテナンシーをファーストクラスの概念として必要
- 数十のルールファイルより20行のYAMLを好む
- 認証スタックのすべての行を理解したい

---

## さらに学ぶために

- [forwardauth.ja.md](forwardauth.ja.md) -- voltaのForwardAuthパターンの実装方法。
- [keycloak.ja.md](keycloak.ja.md) -- voltaが評価し使わないことを選んだもう1つの選択肢。
- [identity-gateway.ja.md](identity-gateway.ja.md) -- アイデンティティゲートウェイの広い概念。
- [config-hell.ja.md](config-hell.ja.md) -- 設定が多いことが常に良いとは限らない理由。
