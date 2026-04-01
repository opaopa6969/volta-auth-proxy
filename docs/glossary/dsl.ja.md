# DSL（ドメイン固有言語）

[English version](dsl.md)

---

## これは何？

ドメイン固有言語（DSL）とは、特定の分野の問題を解くために設計された小さな専用言語です。JavaやPythonのような何でもできる汎用言語とは違い、DSLはひとつのことを極めてうまくやるために意図的に機能が制限されています。SQLはデータベースのためのDSL。正規表現はパターンマッチングのためのDSL。HTMLはWebページを記述するためのDSLです。

テレビのリモコンと万能リモコンの違いのようなものです。テレビのリモコンには電源、音量、チャンネルなど必要なボタンだけがあり、誰でもすぐ使えます。万能リモコンは家中の機器を操作できますが、使い方を覚えるのに説明書が必要です。DSLはテレビのリモコンです。ボタンは少ないけれど、ちょうど必要なものが揃っています。

volta-auth-proxyは、認証の振る舞いをすべて4つのYAML DSLファイルで定義しています。これらのファイルは単なる設定ではなく、システム全体がどう動くかの権威ある仕様書です。DSLに記述されていない振る舞いは存在しません。

---

## なぜ重要なのか？

DSLがなければ、認証ロジックは何十ものJavaファイル、Javalinルート、データベースマイグレーション、設定プロパティに散らばります。バグは隅に隠れ、全体像を見渡せる人がいなくなります。

- **可読性**：新しい開発者が4つのYAMLファイルを読めば、認証システム全体を午後いっぱいで理解できる
- **唯一の情報源**：コード、ドキュメント、設定で定義が矛盾しない
- **テスト容易性**：DSLは構造化されているため、テストを自動生成できる
- **AI親和性**：AIツールがYAMLを解析して、同じファイルからドキュメント、テストケース、コードを生成できる
- **監査容易性**：セキュリティレビュアーは20以上のJavaクラスでなく4つのファイルを監査すればよい

---

## どう動くのか？

### 言語のスペクトラム

```
  汎用                                                ドメイン固有
  ◄────────────────────────────────────────────────────────────────►
  Java       Python      Terraform     SQL      正規表現   volta DSL
  (何でも)   (何でも)    (インフラ)    (データ) (マッチ)   (認証)
```

### 外部DSL vs 内部DSL

```
  外部DSL：
  ┌───────────────────────────────────────────┐
  │ 独自の構文とパーサーを持つ。              │
  │ 例：SQL、正規表現、Terraform HCL          │
  │ volta：起動時にパースされるYAMLファイル    │
  └───────────────────────────────────────────┘

  内部DSL：
  ┌───────────────────────────────────────────┐
  │ 汎用言語の中でホストされる。              │
  │ 例：RSpec (Ruby)、Gradle (Groovy)         │
  │ volta：このアプローチは使っていない       │
  └───────────────────────────────────────────┘
```

voltaはYAMLで書かれた**外部DSL**を使用しています。Javaアプリケーションが起動時にYAMLファイルを読み込み解釈します。DSL自体が持つ要素：

- **コンテキスト変数**：`session.valid`、`tenant.active`、`membership.role`のような型付き変数
- **ガード式**：CELライクな真偽条件、例：`"session.valid && !tenant.suspended"`
- **アクション型**：`side_effect`、`http`、`audit`、`guard_check`
- **遷移ルール**：状態A + トリガー + ガード = 状態B + アクション

### なぜYAMLで独自構文ではないのか？

```
  独自構文：               YAML：
  ┌──────────────────┐    ┌──────────────────────────────┐
  │ state AUTH {      │    │ states:                      │
  │   on login ->     │    │   AUTHENTICATED:             │
  │     if valid      │    │     transitions:             │
  │     goto DONE     │    │       forward_auth:          │
  │ }                 │    │         guard: "session.valid"│
  └──────────────────┘    │         next: AUTHENTICATED   │
                          └──────────────────────────────┘
  カスタムパーサーが必要    標準YAMLパーサー（SnakeYAML）
  エディタ対応なし          どこでもシンタックスハイライト
  学習コストあり            ほとんどの開発者が既に知っている
```

---

## volta-auth-proxyではどう使われているか？

### 4つのDSLファイル

```
  dsl/
  ├── auth-machine.yaml   ← 状態マシン：8状態、すべての遷移
  ├── protocol.yaml       ← ForwardAuth契約、JWT仕様、APIエンドポイント
  ├── policy.yaml         ← ロール階層、権限、制約
  └── errors.yaml         ← すべてのエラーコード、メッセージ、回復アクション
```

各ファイルの責務：

| ファイル | 定義内容 | 例 |
|---------|---------|-----|
| `auth-machine.yaml` | 状態、遷移、ガード、アクション | `UNAUTHENTICATED → AUTH_PENDING`（`GET /login`経由） |
| `protocol.yaml` | ゲートウェイ-アプリ間の契約 | `X-Volta-User-Id`ヘッダー、JWTクレーム、APIエンドポイント |
| `policy.yaml` | 認可ルール | `OWNER > ADMIN > MEMBER > VIEWER` |
| `errors.yaml` | エラーレスポンス | `SESSION_EXPIRED: 401, "セッションの有効期限が切れました"` |

### ファイル間の相互参照

DSLファイルは明示的に相互参照しています：

```yaml
# auth-machine.yaml
errors_ref: "dsl/errors.yaml"   # エラーコードはerrors.yamlから

# protocol.yaml
errors_ref: "dsl/errors.yaml"   # 同じエラーコード

# auth-machine.yaml の遷移
actions:
  - { type: http, action: json_error, error_ref: AUTHENTICATION_REQUIRED }
    # AUTHENTICATION_REQUIREDはerrors.yamlで定義
```

### ガード式の構文

DSLはガード用の独自ミニ式言語を定義しています：

```yaml
# auth-machine.yamlで定義されたCELライク構文
# 演算子: &&, ||, !, ==, !=, >, <, >=, <=, in
# 変数: contextセクションから（session.*, user.*, membership.*等）

guard: "session.valid && tenant.active"
guard: "membership.role in ['ADMIN', 'OWNER']"
guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"
```

### JavaコードがDSLをどう使うか

DSLファイルは仕様です。Javaコード（`AuthService.java`、`Main.java`）は仕様に従う実装です。DSLとコードに食い違いがあれば、DSLが正しくコードにバグがあります。

```
  DSL（仕様）                     Java（実装）
  ┌──────────────────────┐        ┌──────────────────────────────┐
  │ auth-machine.yaml    │───────►│ AuthService.java             │
  │   transition:        │  読む  │   authenticate()             │
  │     guard: "..."     │        │   issueSession()             │
  │     next: STATE      │        │   verify()                   │
  │     actions: [...]   │        │                              │
  └──────────────────────┘        └──────────────────────────────┘
  │                                │
  │  errors.yaml         ───────►  │  ApiException.java          │
  │  policy.yaml         ───────►  │  AppConfig + AppRegistry    │
  │  protocol.yaml       ───────►  │  HttpSupport.java           │
```

---

## よくある間違いと攻撃

### 間違い1：DSLファイルを「ただの設定」として扱う

DSLファイルは設定ではありません。設定とは`PORT=8080`のようなもの。DSLファイルは振る舞いを定義します：状態、遷移、ガード、エラーメッセージ。`auth-machine.yaml`のガード式を変更すれば、設定の調整ではなくセキュリティモデルの変更です。

### 間違い2：DSL外でロジックを重複させる

Javaコードに`policy.yaml`に反映されていない権限チェックを追加すると、情報源が2つになります。矛盾が生じるとバグやセキュリティホールが発生します。常にDSLを先に更新し、それからコードを実装すること。

### 間違い3：DSLを強力にしすぎる

DSLは設計上制限されるべきです。ループ、再帰、汎用的な計算を追加すると、ツールの貧弱な汎用言語を作ったことになります。voltaのDSLにはループもなく、関数もなく（Phase 2まで）、動的評価もありません。

### 間違い4：バージョン管理しない

voltaのDSLファイルには明示的な`version`フィールドがあります。DSLが進化するとバージョンが上がります。これにより古いコードが新しいDSL構造を誤って解釈するのを防ぎます。

---

## さらに学ぶために

- [state-machine.md](state-machine.md) -- `auth-machine.yaml`で定義される状態マシン。
- [guard.md](guard.md) -- DSL中のガード式。
- [transition.md](transition.md) -- DSLで遷移がどう定義されるか。
- [single-source-of-truth.md](single-source-of-truth.md) -- なぜDSLが唯一の情報源なのか。
- [yaml.md](yaml.md) -- DSLが使うデータ形式。
- [invariant.md](invariant.md) -- DSLが強制する形式的ルール。
