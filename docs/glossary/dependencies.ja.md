# 依存関係（Dependencies）

[English version](dependencies.md)

---

## これは何？

依存関係とは、プロジェクトが機能するために必要な外部パッケージ、ライブラリ、モジュールのことです。すべてのコードをゼロから書く代わりに、他の人がすでに書き、テストし、メンテナンスしているコードに頼ります。

市販の食材を使った料理で考えてみましょう。自分でトマトを育て、小麦粉を挽き、鶏を飼うこともできますが、ほとんどのシェフは食材を業者から買います。依存関係とは、自分で作っていない食材です。`pom.xml`ファイル（Mavenプロジェクト）は買い物リストのようなものです：「Javalin 6.x、HikariCP 5.x、Caffeine 3.x、Flyway 10.xが必要」。Mavenがこのリストを読んで、すべてダウンロードしてくれます。

依存関係で重要なこと：他の人のコードをアプリケーション内部で実行することを信頼しています。依存関係にバグがあれば、あなたのアプリにもバグがあります。依存関係にセキュリティ脆弱性があれば、あなたのアプリにもセキュリティ脆弱性があります。

---

## なぜ重要なのか？

- **膨大な開発時間を節約。** 独自のデータベースコネクションプールやJWTライブラリを書くには数ヶ月かかる。HikariCPやnimbus-jose-jwtはすでに存在し、実戦で検証済み。
- **セキュリティリスク。** すべての依存関係は自分が書いておらず、完全に理解していないかもしれないコード。サプライチェーン攻撃は人気のあるライブラリを標的にする。
- **バージョン競合。** ライブラリAがライブラリCの1.0を必要とし、ライブラリBがライブラリCの2.0を必要とすると、競合が発生。
- **メンテナンス負担。** 依存関係はアップデート、セキュリティパッチ、破壊的変更をリリースする。追いつく必要がある。
- **推移的依存関係。** 依存関係にはそれ自身の依存関係がある。`pom.xml`の1行が数十のJARを引き込む可能性がある。

---

## どう動くのか？

### Mavenの依存関係管理

volta-auth-proxyは[Maven](maven.ja.md)を使って依存関係を管理します。依存関係は`pom.xml`で宣言されます：

```xml
<dependencies>
    <!-- Webフレームワーク -->
    <dependency>
        <groupId>io.javalin</groupId>
        <artifactId>javalin</artifactId>
        <version>6.3.0</version>
    </dependency>

    <!-- データベースコネクションプール -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>

    <!-- インメモリキャッシュ -->
    <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>
        <version>3.1.8</version>
    </dependency>
</dependencies>
```

### Mavenが依存関係を解決する方法

```
  pom.xmlの指定: "Javalin 6.3.0が必要"
       │
       ▼
  Mavenがローカルキャッシュを確認 (~/.m2/repository)
       │
       ├── あった？ → それを使う
       │
       └── なかった？ → Maven Centralからダウンロード
                              │
                              ▼
                   ┌──────────────────┐
                   │  Maven Central   │
                   │  (リポジトリ)     │
                   │                  │
                   │  javalin-6.3.0   │
                   │    └─ 必要:      │
                   │      jetty 11.x  │
                   │      slf4j 2.x   │
                   └──────────────────┘
                              │
                   Javalin自身の依存関係も
                   ダウンロード（推移的）
```

### 直接依存と推移的依存

```
  pom.xmlで宣言:               Mavenが追加ダウンロード:
  ┌───────────────────┐         ┌───────────────────┐
  │ Javalin           │────────▶│ Jetty (Webサーバー) │
  │ HikariCP          │         │ SLF4J (ログ)       │
  │ Caffeine          │         │ Jackson (JSON)     │
  │ Flyway            │         │ PostgreSQLドライバ  │
  │ jte               │         │ ... 他に数十個      │
  └───────────────────┘         └───────────────────┘
     直接（自分が選択）            推移的（引き込まれる）
       約10-15エントリ              合計約50-100 JAR
```

### 依存関係のスコープ

Mavenの依存関係にはスコープがあり、いつ利用可能かを制御します：

| スコープ | いつ利用可能か | 例 |
|---------|--------------|-----|
| `compile`（デフォルト） | ビルド＋実行時 | Javalin、HikariCP |
| `runtime` | 実行時のみ | PostgreSQL JDBCドライバ |
| `test` | テスト時のみ | JUnit、Mockito |
| `provided` | ビルド時のみ、サーバーが実行時に提供 | Servlet API（voltaでは未使用） |

---

## volta-auth-proxy ではどう使われている？

### voltaの依存関係哲学

voltaは意図的に依存関係を最小限にしています。すべての依存関係はリスクであり、voltaの設計哲学は認証レイヤーで実行されるすべてのコード行を理解することを重視しています。

### 主要な依存関係

| 依存関係 | 目的 | なぜこれを選んだか |
|---------|------|-------------------|
| **Javalin** | HTTPフレームワーク | 軽量、魔法なし、フルコントロール |
| **HikariCP** | [コネクションプール](connection-pool.ja.md) | 最速のJavaコネクションプール |
| **Caffeine** | [インメモリキャッシュ](in-memory.ja.md) | 最速のJavaキャッシュ、賢い退去 |
| **Flyway** | [データベースマイグレーション](migration.ja.md) | 業界標準、信頼性 |
| **jte** | テンプレートエンジン | 型安全、コンパイル済み、高速 |
| **nimbus-jose-jwt** | [JWT](jwt.ja.md)処理 | 包括的、よくメンテナンスされている |
| **PostgreSQL JDBC** | データベースドライバ | Postgres公式ドライバ |
| **SLF4J + Logback** | ログ | 業界標準 |

### voltaが意図的に依存しないもの

| 不使用 | 理由 |
|--------|------|
| Spring / Spring Boot | 魔法が多すぎ、推移的依存が多すぎ |
| Hibernate / JPA | SQLの方が明確；ORMの複雑さ不要 |
| Keycloak SDK | 自前認証、ベンダー依存なし |
| Node.js / npm | サプライチェーンリスク、依存関係の爆発 |

### 脆弱性のチェック

```bash
# Mavenには既知の脆弱性をチェックするプラグインがある
mvn org.owasp:dependency-check-maven:check

# すべての依存関係（推移的含む）をNational Vulnerability
# Database (NVD) と照合してスキャンする
```

---

## よくある間違いと攻撃

### 間違い1：評価なしに依存関係を追加

`pom.xml`に追加するすべての依存関係は、フルアクセスでアプリケーション内部で実行されます。追加前に確認：推移的依存はいくつ？活発にメンテナンスされている？既知の脆弱性は？

### 間違い2：バージョンを固定しない

```xml
<!-- 悪い例：バージョン範囲、予測不能なビルド -->
<version>[1.0,2.0)</version>

<!-- 良い例：正確なバージョン、再現可能なビルド -->
<version>6.3.0</version>
```

### 間違い3：推移的依存のアップデートを無視

直接依存は最新でも、推移的依存に重大な脆弱性があるかもしれません。`mvn dependency:tree`で全体のツリーを確認しましょう。

### 間違い4：サプライチェーン攻撃への無関心

攻撃者は人気のあるライブラリを侵害したり、タイポスクワッティングパッケージ（例：`javalin`ではなく`javelin`）を公開します。groupIdとartifactIdを常に注意深く確認しましょう。

### 間違い5：依存関係を更新しない

古い依存関係はセキュリティ脆弱性を蓄積します。Java 21 [LTS](lts.ja.md)は安定したベースを提供しますが、ライブラリは定期的な更新が必要です。

---

## さらに学ぶ

- [maven.ja.md](maven.ja.md) -- voltaの依存関係を管理するビルドツール。
- [hikaricp.ja.md](hikaricp.ja.md) -- voltaのコネクションプール依存関係。
- [caffeine-cache.ja.md](caffeine-cache.ja.md) -- voltaのキャッシュ依存関係。
- [external-dependency.ja.md](external-dependency.ja.md) -- 外部コードに依存するリスク。
- [fat-jar.ja.md](fat-jar.ja.md) -- 依存関係が単一のデプロイ可能ファイルにパッケージされる方法。
