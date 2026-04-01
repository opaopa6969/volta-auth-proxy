# CEL（Common Expression Language：共通式言語）

[English version](cel.md)

---

## これは何？

CEL（Common Expression Language）は、条件を評価するために Google が設計した軽量な式言語です。`request.auth.role == "OWNER"` や `user.email.endsWith("@company.com")` のような短く読みやすいルールを書き、コードをコンパイルすることなく実行時に評価できます。

スプレッドシートの数式のようなものです。Excel で `=IF(A1 > 100, "大きい", "小さい")` と書くと、スプレッドシートが即座に評価します。CEL も同じ考え方ですが、セキュリティルールとアクセスポリシーが対象です。人間が読める条件を書き、システムが現在のリクエストに対して評価して「許可」か「拒否」を決定します。

CEL はもともと Google のインフラのために作られました -- Google Cloud IAM、Firebase Security Rules、Kubernetes のアドミッションコントロールでアクセスポリシーを制御しています。安全であるよう設計されています：ループなし、副作用なし、ファイルアクセスなし。CEL 式はデータを読んで値を返すことしかできません。これにより、暴走した式がサーバーをクラッシュさせる余裕のないセキュリティポリシーに最適です。

---

## なぜ重要？

セキュリティルールはコードを再コンパイルせずに設定可能である必要があります。`if (user.role == "ADMIN")` のようなハードコーディングは単純なケースには使えますが、ポリシーが複雑になると、アプリケーションコードの外で宣言的に表現する方法が必要です。

選択肢には以下があります：

| アプローチ | 長所 | 短所 |
|-----------|------|------|
| Java にハードコード | 高速、型安全 | 再コンパイルと再デプロイが必要 |
| JSON/YAML 設定 | 読みやすい | 表現力が限定的、ロジック不可 |
| フルスクリプティング（JS、Lua） | 非常に柔軟 | セキュリティリスク、副作用、パフォーマンス |
| **CEL** | 安全、高速、読みやすい | 学習コスト、式に限定 |

CEL はスイートスポットにあります：実際のポリシーに十分な表現力があり、理論的には信頼されていないユーザーでも書けるほど安全です（volta はこれを公開しませんが）。

---

## どう動くのか？

### CEL の基本構文

```
  // 比較
  request.method == "POST"

  // ブーリアン論理
  user.role == "OWNER" || user.role == "ADMIN"

  // 文字列操作
  user.email.endsWith("@example.com")

  // リスト所属
  request.path in ["/api/v1/health", "/api/v1/status"]

  // 三項演算
  user.verified ? "allowed" : "denied"

  // ネストしたアクセス
  request.headers["Authorization"].startsWith("Bearer ")

  // 数値比較
  request.body.amount <= 10000
```

### CEL の評価モデル

```
  ┌──────────────┐     ┌──────────────────────┐
  │  CEL          │     │  コンテキスト（変数）  │
  │  式           │     │                      │
  │               │     │  user.role = "MEMBER" │
  │  user.role    │     │  user.email = "a@b.c" │
  │  == "OWNER"   │────►│  request.method = GET │
  │               │     │  request.path = /api  │
  └──────────────┘     └──────────────────────┘
         │
         ▼
  ┌──────────────┐
  │  結果：       │
  │  false        │
  └──────────────┘
```

### CEL が安全な理由

| 特性 | 意味 |
|------|------|
| **ループなし** | `while(true)` を書けない -- 終了が保証される |
| **副作用なし** | ファイル書き込み、HTTPリクエスト送信、状態変更ができない |
| **変数代入なし** | 変数の作成や入力データの変更ができない |
| **制限された実行** | 式のサイズに比例した予測可能な時間で評価 |
| **型安全** | 型エラーは評価前に検出 |

---

## volta-auth-proxy ではどう使われている？

volta の DSL は**ガード式** -- 認証ルールがいつ適用されるかを制御する条件 -- に CEL ライクな構文を使用しています。

### volta DSL のガード式

volta の設定 DSL では、ガードがどのリクエストがルールにマッチするかを決定します：

```yaml
rules:
  - name: "owner-only-admin"
    guard: "request.path.startsWith('/api/v1/admin') && user.role == 'OWNER'"
    action: allow

  - name: "member-read-only"
    guard: "request.method == 'GET' && user.role == 'MEMBER'"
    action: allow

  - name: "block-free-email-signup"
    guard: "user.email.endsWith('@gmail.com') || user.email.endsWith('@yahoo.com')"
    action: deny
```

### フル CEL ではなく CEL ライクな理由

volta はフル CEL 仕様ではなく、CEL にインスパイアされた構文を使用しています。これは意図的な YAGNI の決定です：

| 機能 | フル CEL | volta の CEL ライク |
|------|---------|-------------------|
| ブーリアン演算子 | あり | あり |
| 文字列メソッド | すべて | サブセット（startsWith, endsWith, contains, matches） |
| 数値比較 | あり | あり |
| リスト操作 | フル（map, filter, exists） | 基本（in） |
| マクロ | あり | なし |
| カスタム関数 | あり | なし（Phase 2） |

サブセットが実際のガード式の 95% をカバーします。フル CEL を追加するには CEL ランタイム依存関係を追加することになり、volta の最小限の依存関係哲学に反します。

### ガードの評価方法

```
  受信 HTTP リクエスト
         │
         ▼
  コンテキストオブジェクトを構築：
  ┌────────────────────────┐
  │  request.method = POST │
  │  request.path = /api.. │
  │  request.headers = ... │
  │  user.id = "u-123"     │
  │  user.role = "MEMBER"  │
  │  user.email = "a@b.c"  │
  │  tenant.id = "t-456"   │
  │  tenant.plan = "free"  │
  └────────────────────────┘
         │
         ▼
  各ルールのガード式をコンテキストに
  対して評価
         │
    ┌────┴─────┐
    │          │
  マッチ    マッチなし
    │          │
    ▼          ▼
  アクション  次のルール
  を適用     を試行
```

### Mermaid 状態図の生成

volta の DSL はガード式から mermaid 図を生成し、ルール評価フローを可視化できます。詳細は [mermaid.md](mermaid.md) を参照。

---

## よくある間違いと攻撃

### 間違い1：ガード式を複雑にしすぎる

```
  悪い例：guard: "request.path.startsWith('/api/v1/tenants/')
         && request.path.split('/').size() >= 6
         && request.path.split('/')[5] == 'members'
         && (user.role == 'OWNER' || (user.role == 'MEMBER'
         && request.method == 'GET'))"

  良い例：複数のルールに分割：
    - name: "owner-manages-members"
      path: "/api/v1/tenants/{tid}/members/**"
      guard: "user.role == 'OWNER'"
      action: allow

    - name: "member-views-members"
      path: "/api/v1/tenants/{tid}/members/**"
      method: GET
      guard: "user.role == 'MEMBER'"
      action: allow
```

### 間違い2：ガードでクライアントサイドのデータを信頼する

ガードはサーバーで検証されたデータ（JWT クレーム、セッションデータ）を評価すべきであり、偽装可能な生のリクエストデータ（カスタムヘッダー、クエリパラメーター）ではありません。

### 間違い3：CEL をビジネスロジックに使う

CEL はアクセス制御の決定（許可/拒否）のためのものです。複雑なビジネスロジックは、適切にテストとデバッグができるアプリケーションコードに属します。

### 間違い4：ガード式をテストしない

ガード式はコードです。Java コードと同様にテストケースが必要です。ガードのタイプミス（`user.role` の代わりに `user.rolee`）はサイレントに失敗し、不正アクセスを許す可能性があります。

---

## さらに学ぶ

- [mermaid.md](mermaid.md) -- volta が DSL ルールからどう図を生成するか。
- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- CEL ガードは認可を処理。
- [tenant-resolution.md](tenant-resolution.md) -- ガード式にテナントコンテキストがどう提供されるか。
- [CEL 仕様](https://github.com/google/cel-spec) -- 公式 CEL 言語仕様。
- [Google CEL Go 実装](https://github.com/google/cel-go) -- リファレンス実装。
