# Fat JAR（ファットJAR）

[English version](fat-jar.md)

---

## これは何？

Fat JAR（uber JARとも呼ばれる）は、アプリケーションコードとそのすべての依存ライブラリを一つにまとめた単一のJavaアーカイブファイル（.jar）で、1つのファイルでアプリケーション全体を実行できます。

お弁当箱と買い物リストの違いで考えてみましょう。通常のJARは買い物リストのようなものです：必要な材料が書いてありますが、自分で探さなければなりません。Fat JARは詰まったお弁当箱です：必要なものがすべて入っています。開けて食べるだけ。買い物は不要です。

技術的に言うと：通常のJARにはあなたのコードだけが入っています。Fat JARにはあなたのコードに加えて、コードが依存するすべてのライブラリ（Webサーバー、データベースドライバー、JSONパーサー、テンプレートエンジン -- すべて）が単一の`.jar`ファイルにパッケージされています。

---

## なぜ重要なのか？

Javaアプリケーションのデプロイは以前は面倒でした。必要だったもの：

1. アプリケーションサーバー（Tomcat、JBoss、WildFly）
2. そこにデプロイするアプリケーションのWARファイル
3. 正しいバージョンのライブラリのインストール
4. 正しいクラスパスの設定

これが有名な「自分のマシンでは動く」問題を引き起こしました。環境によってライブラリのバージョンが違い、アプリケーションサーバーの設定が違い、開発環境で動いたものが本番で壊れます。

Fat JARはデプロイをシンプルにすることでこれを解決します：

```
  従来のデプロイ：                     Fat JARデプロイ：
  ┌─────────────────────┐              ┌─────────────────────┐
  │ Javaをインストール    │              │ Javaをインストール    │
  │ Tomcatをインストール  │              │ java -jar app.jar    │
  │ Tomcatを設定         │              │                      │
  │ WARファイルをデプロイ  │              │ 以上。               │
  │ クラスパスを設定      │              └─────────────────────┘
  │ 動くことを祈る        │
  └─────────────────────┘
```

### Fat JARの作り方

MavenやGradleなどのビルドツールがプラグインを使ってFat JARを作成します：

- **Maven Shade Plugin** -- すべての依存関係を単一JARに再パッケージし、衝突を避けるためにパッケージを「シェーディング」（リネーム）する
- **Maven Assembly Plugin** -- プロジェクト出力と依存関係を単一アーカイブに結合する
- **Spring Boot Maven Plugin** -- 特殊なクラスローダーを持つ実行可能JARを作成する

Maven Shade Pluginの例：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.5.0</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="...ManifestResourceTransformer">
            <mainClass>com.volta.authproxy.Main</mainClass>
          </transformer>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

`mvn package`を実行すると、すべてを含む`volta-auth-proxy-0.1.0.jar`のような単一ファイルができます。

---

## voltaのデプロイ方法

volta-auth-proxyはJavalin（マイクロWebフレームワーク）で構築された軽量Javaアプリケーションです。単一のJavaプロセスとして実行します：

```bash
# 開発: Mavenで直接実行
mvn exec:java -Dexec.mainClass=com.volta.authproxy.Main

# 本番: パッケージされたJARを実行
java -jar volta-auth-proxy.jar
```

アプリケーションは必要なものすべてを含んでいます：

| コンポーネント | 役割 | JARに含まれる？ |
|-------------|------|---------------|
| Javalin | Webサーバー + ルーティング | はい |
| [HikariCP](hikaricp.ja.md) | データベースコネクションプール | はい |
| [Flyway](flyway.ja.md) | データベースマイグレーション | はい |
| [jte](jte.ja.md) | テンプレートエンジン | はい |
| nimbus-jose-jwt | JWT署名/検証 | はい |
| PostgreSQLドライバー | データベース通信 | はい |
| Jackson | JSON解析 | はい |

アプリケーションサーバー不要。別のWebサーバー不要。クラスパスの設定不要。`java -jar`だけで約200msで起動します。

### セルフホスティングで重要な理由

Fat JARアプローチにより、voltaの[セルフホスティング](self-hosting.ja.md)がシンプルになります：

```
  voltaの実行に必要なもの：
  ┌──────────────────────────┐
  │ 1. Java 21（ランタイム）   │
  │ 2. PostgreSQLデータベース  │
  │ 3. volta-auth-proxy.jar  │
  │ 4. .env + 設定YAML       │
  └──────────────────────────┘

  必要ないもの：
  ┌──────────────────────────┐
  │ ✗ Tomcat                 │
  │ ✗ アプリケーションサーバー │
  │ ✗ ライブラリ管理          │
  │ ✗ 複雑なデプロイ手順      │
  └──────────────────────────┘
```

Dockerコンテナではさらにシンプルになります -- Javaランタイムがイメージに焼き込まれているので、`docker run volta-auth-proxy`だけで済みます。

---

## Fat JARとThin JARの比較

| | Fat JAR | Thin JAR |
|---|---------|----------|
| **中身** | あなたのコード + すべての依存関係 | あなたのコードのみ |
| **ファイルサイズ** | 大きい（数十MB） | 小さい（KB〜MB） |
| **デプロイ** | 単一ファイル、自己完結 | 依存関係を別途用意する必要あり |
| **Dockerイメージ** | シンプルなDockerfile | 依存関係レイヤーの管理が必要 |
| **起動** | やや遅い可能性あり（展開処理） | 速い（すでに展開済み） |
| **最適な用途** | マイクロサービス、シンプルなデプロイ | 大規模な共有環境 |

voltaはFat JARアプローチを使います。デプロイのシンプルさがコアバリューだからです。セルフホスティングでは、動く部品が少ないほど壊れるものが少なくなります。

---

## さらに学ぶために

- [self-hosting.ja.md](self-hosting.ja.md) -- voltaのセルフホストモデルでシンプルなデプロイが重要な理由。
- [hikaricp.ja.md](hikaricp.ja.md) -- voltaのJARにバンドルされているライブラリの一つ。
- [flyway.ja.md](flyway.ja.md) -- JAR起動時に自動実行されるデータベースマイグレーション。
- [jte.ja.md](jte.ja.md) -- JARにバンドルされているテンプレートエンジン。
