# Mermaid

[English version](mermaid.md)

---

## これは何？

Mermaid はテキストベースの図表作成ツールです。シンプルな構文でプレーンテキストとして図を書くと、Mermaid がそれをビジュアルな図 -- フローチャート、シーケンス図、状態図、ER 図など -- にレンダリングします。ドラッグ＆ドロップなし、画像エディタなし、バイナリファイルなし。

楽譜を書くのと音声を録音するの違いのようなものです。音楽家はテキスト（楽譜）を読んで音（図）を生み出せます。楽譜はバージョン管理可能で、差分比較可能で、人間にも機械にも読めます。Mermaid は図に対して同じことをします：説明を書くと、ツールが絵を描きます。

Mermaid は GitHub Markdown、GitLab、Notion、Docusaurus など多くのドキュメントツールでネイティブサポートされています。README.md ファイルに図を埋め込むと、GitHub がプラグインなしでビジュアルな図としてレンダリングします。

---

## なぜ重要？

従来の図は Visio、Lucidchart、draw.io などのツールで作成します。これらはバイナリファイル（PNG、SVG、.drawio）を生成し：

- プルリクエストで差分比較できない
- 編集に特定のツールが必要
- 更新が面倒なので古くなりやすい
- コードベースの外（別フォルダ、wiki、誰かのノートパソコン）に存在

Mermaid の図はテキストなのでこれらの問題を解決します：

| 観点 | バイナリ図 | Mermaid（テキスト） |
|------|----------|-------------------|
| **バージョン管理** | git 内のバイナリ blob | プルリクエストでテキスト差分 |
| **編集** | Visio/Lucidchart が必要 | 任意のテキストエディタ |
| **コードレビュー** | 変更をレビューできない | 行ごとに変更が見える |
| **鮮度** | すぐに古くなる | 更新しやすいので鮮度を保てる |
| **自動化** | 手動作成のみ | コードから生成可能 |

最後のポイント -- コードからの生成 -- は volta にとって特に重要です。

---

## どう動くのか？

### 基本的な Mermaid 構文

**フローチャート：**
```
  flowchart TD
    A[開始] --> B{ユーザーはログイン済？}
    B -->|はい| C[ダッシュボードを表示]
    B -->|いいえ| D[/login にリダイレクト]
    D --> E[Google OIDC]
    E --> F[コールバック]
    F --> C
```

**シーケンス図：**
```
  sequenceDiagram
    Browser->>volta: GET /login
    volta->>Google: OIDC にリダイレクト
    Google->>Browser: ログインページ
    Browser->>Google: 資格情報
    Google->>volta: コード付きコールバック
    volta->>Google: コードをトークンに交換
    Google->>volta: ID トークン + アクセストークン
    volta->>Browser: セッションクッキーを設定
```

**状態図：**
```
  stateDiagram-v2
    [*] --> 匿名
    匿名 --> 認証中: GET /login
    認証中 --> 認証済: コールバック成功
    認証中 --> 匿名: コールバック失敗
    認証済 --> 匿名: セッション期限切れ
    認証済 --> 認証済: API リクエスト
```

**ER 図：**
```
  erDiagram
    USER ||--o{ MEMBERSHIP : has
    TENANT ||--o{ MEMBERSHIP : has
    TENANT ||--o{ INVITATION : has
    MEMBERSHIP {
      string userId
      string tenantId
      string role
    }
```

### Mermaid がレンダリングされる場所

| プラットフォーム | サポート |
|---------------|---------|
| **GitHub** | Markdown でネイティブ（```mermaid コードブロック） |
| **GitLab** | Markdown でネイティブ |
| **VS Code** | 拡張機能経由（Markdown Preview Mermaid） |
| **Notion** | ネイティブサポート |
| **Docusaurus** | プラグイン利用可能 |
| **CLI** | `mmdc`（mermaid-cli）で PNG/SVG 生成 |

### Mermaid ライブエディタ

[Mermaid Live Editor](https://mermaid.live/) で Mermaid 構文を書き、レンダリングされた図をリアルタイムで確認できます。複雑な図のプロトタイピングに便利です。

---

## volta-auth-proxy ではどう使われている？

volta は Mermaid を2つの方法で使用します：ドキュメントの手書き図と、**DSL から生成された図**です。

### ドキュメント内の手書き図

volta の用語集とアーキテクチャドキュメントは、インライン図に Mermaid を使用しています。これらはテキストなので、プルリクエストでレビューでき、コードと同期が保たれます。

### DSL から生成される状態図

こちらがより興味深い用途です。volta の DSL は認証ルール定義から Mermaid 状態図を生成できます。DSL でルールを定義すると、volta は認証フローの状態機械を示す Mermaid 図を出力できます。

```yaml
  # volta DSL ルール定義：
  rules:
    - name: "public-health"
      path: "/healthz"
      action: allow-anonymous

    - name: "login-flow"
      path: "/login"
      action: redirect-to-oidc

    - name: "api-authenticated"
      path: "/api/**"
      guard: "user.authenticated == true"
      action: allow

    - name: "api-deny"
      path: "/api/**"
      action: deny
```

```
  # 生成された Mermaid 出力：
  stateDiagram-v2
    [*] --> リクエスト受信

    リクエスト受信 --> 匿名許可: path == /healthz
    リクエスト受信 --> OIDCリダイレクト: path == /login
    リクエスト受信 --> ガード評価: path == /api/**

    ガード評価 --> 許可: user.authenticated == true
    ガード評価 --> 拒否: その他

    OIDCリダイレクト --> GoogleOIDC
    GoogleOIDC --> コールバック処理
    コールバック処理 --> セッション作成
    セッション作成 --> リクエスト受信: 次のリクエスト
```

### 生成された図が重要な理由

| 利点 | 説明 |
|------|------|
| **常に正確** | 図は実行中のルールと同じソースから来る |
| **手動同期不要** | ルールを変更し、図を再生成。労力ゼロ。 |
| **複雑なポリシーの可視化** | 20のルールはテキストでは推論が困難。状態図はフローを明確にする。 |
| **ドキュメント・アズ・コード** | 図はビルド成果物であり、手動作成物ではない |
| **レビューツール** | 新ルールをデプロイする前に、図を生成してフローを視覚的に検証 |

### volta の CEL ライクガードとの統合

生成された図はガード式を遷移ラベルとして表示し、どの条件がどの状態につながるかが一目でわかります。ガード式の詳細は [cel.md](cel.md) を参照。

---

## よくある間違いと攻撃

### 間違い1：複雑すぎる図

Mermaid の図はシンプルで焦点を絞るべきです。50ノードと100エッジの図は読めません。複雑なシステムは複数の焦点を絞った図に分割しましょう。

### 間違い2：コードコメントの複製である図

図がコードコメントとまったく同じことを言っているなら、価値はありません。図はコードでは見えにくい関係やフローを示すべきです -- 詳細ではなく全体像。

### 間違い3：図を更新しない

古い図は図がないより悪い。読者を誤解させます。図がコードから生成される場合（volta が DSL ルールで行うように）、この問題はなくなります。手書きの図の場合、動作を変更する PR と同じ PR で更新しましょう。

### 間違い4：すべてに Mermaid を使う

Mermaid はフローチャート、シーケンス図、状態図に優れています。詳細なアーキテクチャ図、ネットワークトポロジー、UI ワイヤーフレームにはあまり適していません。仕事に適したツールを使いましょう。

### 間違い5：レンダリングの違いを無視する

Mermaid はプラットフォーム間（GitHub vs. GitLab vs. VS Code）でわずかに異なるレンダリングをします。最も多く閲覧されるプラットフォームで図をテストしましょう。

---

## さらに学ぶ

- [cel.md](cel.md) -- 生成された図で遷移として表示されるガード式。
- [Mermaid ドキュメント](https://mermaid.js.org/) -- 公式構文リファレンス。
- [Mermaid Live Editor](https://mermaid.live/) -- インタラクティブな図エディタ。
- [GitHub Mermaid サポート](https://github.blog/2022-02-14-include-diagrams-markdown-files-mermaid/) -- GitHub が Mermaid をレンダリングする方法。
