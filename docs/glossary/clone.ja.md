# クローン

[English version](clone.md)

---

## これは何？

`git clone`は、[git](git.md) [リポジトリ](repository.md)の完全なコピーをローカルマシンにダウンロードするコマンドです。プロジェクト履歴のすべてのファイル、すべてのブランチ、すべてのコミットをコピーします。クローン後、オフラインで作業できる完全に独立したコピーを持つことになります。

ファイルキャビネット全体をコピーするのに似ています。すべてのフォルダ、すべての文書、すべての改訂メモを取り出し、自分のデスク用に完全なコピーを作ります。元のファイルキャビネットはオフィス（リモートリポジトリ）にまだ存在し、あなたのコピーは完全に機能します -- ファイルを読み、編集し、追加し、整理できます。変更を共有したいときは、元に戻してプッシュします。

クローンはvolta-auth-proxyで作業する最初のステップです。コードを[ビルド](build.md)、実行、変更する前に、ローカルコピーが必要です。

---

## なぜ重要なのか？

クローンが重要な理由：

- **入口である** -- 新しいプロジェクトで開発者が最初にすることはクローン
- **完全な履歴** -- すべてのコミットを取得し、コードがどう進化したか、誰が何をいつ変えたかを理解できる
- **オフライン作業** -- クローン後、コードの閲覧、履歴の確認、変更にインターネット接続は不要
- **独立性** -- クローンはあなたのもの。実験し、壊し、リセットしても他の誰にも影響しない
- **コラボレーション** -- クローンは複数の開発者が同じプロジェクトで作業する方法。全員が自分のコピーを持ち、[git](git.md)が変更のマージを処理する

---

## どう動くのか？

### cloneコマンド

```bash
git clone https://github.com/your-org/volta-auth-proxy.git
```

これで完全なリポジトリを含む`volta-auth-proxy/`ディレクトリが作成されます。

### cloneがダウンロードするもの

```
  リモート（GitHub）                  あなたのマシン（clone後）
  ───────────────                    ──────────────────────────
  ┌─────────────────┐               ┌─────────────────┐
  │ 全ファイル       │               │ 全ファイル       │  （完全なコピー）
  │ 全ブランチ       │  git clone    │ 全ブランチ       │
  │ 全コミット       │ ─────────>   │ 全コミット       │
  │ 全タグ           │               │ 全タグ           │
  │                  │               │ .git/ディレクトリ │  （メタデータ）
  └─────────────────┘               └─────────────────┘
```

### clone vs ZIPダウンロード

GitHubには「Download ZIP」ボタンがあります。これはクローンとは異なります：

| 機能 | git clone | ZIP ダウンロード |
|------|-----------|---------------|
| 完全な履歴 | はい（全コミット） | いいえ（最新のファイルのみ） |
| ブランチ | 全ブランチ | デフォルトブランチのみ |
| Gitメタデータ (.git/) | はい | いいえ |
| 変更をプッシュ可能 | はい | いいえ |
| 更新をプル可能 | はい | いいえ |
| 誰が何を変えたか確認可能 | はい | いいえ |

常に`git clone`を使い、ZIPは絶対にダウンロードしないでください。

### .gitディレクトリ

クローン後、リポジトリには隠し`.git/`ディレクトリがあります。ここにgitがすべてのメタデータを保存します：

```
  volta-auth-proxy/
  ├── .git/                    ← gitメタデータ（手動で編集しない）
  │   ├── objects/             ← すべてのファイルのすべてのバージョン
  │   ├── refs/                ← ブランチとタグのポインタ
  │   ├── HEAD                 ← 現在のブランチ
  │   └── config               ← リモートURL、設定
  ├── pom.xml                  ← ファイルの作業コピー
  ├── src/
  └── ...
```

### SSH vs HTTPSでのクローン

```bash
# HTTPS（すぐ使える、パスワードを求められる）
git clone https://github.com/your-org/volta-auth-proxy.git

# SSH（SSHキーの設定が必要、パスワードプロンプトなし）
git clone git@github.com:your-org/volta-auth-proxy.git
```

SSHは認証情報を入力する必要がないため、日常的な開発に推奨されます。

### 特定のディレクトリにクローン

```bash
# デフォルト: volta-auth-proxy/ディレクトリを作成
git clone https://github.com/your-org/volta-auth-proxy.git

# カスタムディレクトリ名
git clone https://github.com/your-org/volta-auth-proxy.git my-volta
```

### クローン後：典型的なワークフロー

```
  1. リポジトリをクローン
     git clone https://github.com/your-org/volta-auth-proxy.git
     cd volta-auth-proxy

  2. プロジェクトをビルド
     mvn package

  3. 環境をセットアップ
     export DATABASE_URL=jdbc:postgresql://localhost:5432/volta
     export GOOGLE_CLIENT_ID=...
     export GOOGLE_CLIENT_SECRET=...

  4. アプリケーションを実行
     java -jar target/volta-auth-proxy.jar

  5. 変更、コミット、プッシュ
     git add -A
     git commit -m "JWT期限チェックを修正"
     git push
```

---

## volta-auth-proxy ではどう使われている？

### voltaの始め方

volta-auth-proxyのREADMEの最初のステップは：

```bash
git clone https://github.com/your-org/volta-auth-proxy.git
cd volta-auth-proxy
```

これで以下を含む完全なソースコードが得られます：

```
  クローン後に得られるもの:
  ├── pom.xml                  → Mavenビルド設定
  ├── volta-config.yaml        → ランタイム設定テンプレート
  ├── Dockerfile               → コンテナビルド手順
  ├── docker-compose.yml       → ローカル開発環境
  ├── src/main/java/           → Javaソースコード
  │   └── dev/volta/
  │       ├── Main.java        → アプリケーションエントリポイント
  │       ├── JwtService.java  → JWT作成/検証
  │       └── ...
  ├── src/main/jte/            → jteテンプレート（ログインなど）
  ├── src/main/resources/
  │   └── db/migration/        → Flyway SQLマイグレーション
  ├── src/test/java/           → テストソースコード
  └── docs/glossary/           → この用語集
```

### docker-composeでのローカル開発

クローン後、開発環境全体を立ち上げられます：

```bash
git clone https://github.com/your-org/volta-auth-proxy.git
cd volta-auth-proxy
docker-compose up -d    # PostgreSQLなどを起動
mvn package
java -jar target/volta-auth-proxy.jar
```

クローンしたリポジトリの[docker-compose](docker-compose.md)ファイルが、開発に必要なローカル[データベース](database.md)やその他のサービスを定義しています。

### クローンを最新に保つ

初回クローン後、`git pull`で最新の変更を取得します：

```bash
cd volta-auth-proxy
git pull origin main    # 最新の変更をダウンロードしてマージ

# その後再ビルド
mvn package
```

### コントリビューション用のクローン

メインリポジトリへの書き込みアクセスがない場合、典型的なコントリビューションフローは：

```
  1. リポジトリをフォーク（GitHub上で）
  2. 自分のフォークをクローン
     git clone https://github.com/YOUR-NAME/volta-auth-proxy.git

  3. 変更、コミット、自分のフォークにプッシュ
  4. 自分のフォークからメインリポへプルリクエストを作成
```

---

## よくある間違いと攻撃

### 間違い1：間違ったディレクトリにクローンする

別のgitリポジトリの中にクローンすると、ネストしたリポジトリが作られ、混乱します：

```bash
# 間違い: 別のリポの中にクローン
cd ~/my-other-project/    # ここはすでにgitリポ
git clone https://github.com/.../volta-auth-proxy.git
# リポの中にリポ -- 混乱！

# 正しい: クリーンなディレクトリにクローン
cd ~/projects/
git clone https://github.com/.../volta-auth-proxy.git
```

### 間違い2：.git/内のファイルを編集する

`.git/`ディレクトリはgitが管理しています。中のファイルを手動で編集するとリポジトリが壊れる可能性があります。git設定を変更する必要がある場合は、`git config`コマンドを使ってください。

### 間違い3：クローンせずにビルドしようとする

GitHubから個別のファイルをダウンロードして手動でビルドしようとしても失敗します。Mavenにはpom.xmlが、gitには.gitディレクトリが必要で、プロジェクト構造が完全でなければなりません。常に`git clone`から始めてください。

### 間違い4：開発にシャロークローンを使う

```bash
git clone --depth 1 https://github.com/.../volta-auth-proxy.git
```

シャロークローンは最新のコミットだけをダウンロードし、帯域幅を節約します。しかし履歴を失い、ブランチの切り替えが難しくなり、`git blame`が動きません。シャロークローンはCI/CDパイプラインにのみ使い、開発には使わないでください。

### 攻撃1：悪意のあるリポジトリからのクローン

攻撃者が本物に見える偽のリポジトリURLを提供すると、悪意のあるコードをクローンしてしまうかもしれません。常にリポジトリURLを確認してください：

```bash
# 確認: これは本物のvoltaリポジトリか？
# チェック: 組織名、リポジトリ名、HTTPS証明書
git clone https://github.com/REAL-ORG/volta-auth-proxy.git  # ✓
git clone https://github.com/re4l-org/volta-auth-proxy.git  # ✗ タイポスクワット！
```

---

## さらに学ぶ

- [git.md](git.md) -- cloneが属するバージョン管理システム。
- [repository.md](repository.md) -- クローンする対象。
- [build.md](build.md) -- クローン後の次のステップ。
- [docker-compose.md](docker-compose.md) -- クローン後の開発環境セットアップ。
- [environment-variable.md](environment-variable.md) -- クローン後のvolta設定。
- [maven.md](maven.md) -- クローン後のプロジェクトビルド。
