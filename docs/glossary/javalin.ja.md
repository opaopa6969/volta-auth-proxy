# Javalin

[English version](javalin.md)

---

## これは何？

Javalin は Java と Kotlin のための軽量 Web フレームワークです。Web アプリケーション構築に必要な基本機能 -- ルーティング、リクエスト/レスポンス処理、ミドルウェア、WebSocket サポート -- を、Spring Boot や Jakarta EE のような大きなフレームワークの儀式や複雑さなしに提供します。

スイスアーミーナイフとフル装備のワークショップの違いのようなものです。Spring Boot はワークショップ：想像できるすべてのツールがありますが、すべての場所を覚える必要があり、ドライバーしか使わなくても電気代を払います。Javalin はスイスアーミーナイフ：コンパクトで、ほとんどの仕事に必要なものがすべてあり、ポケットに入れて持ち歩けます。

Javalin は Jetty（HTTP サーバー）をラップし、クリーンで関数的な API を提供します。ルートをラムダ式で定義し、ミドルウェアをシンプルな関数として追加し、フレームワークは邪魔をしません。アノテーションの魔法なし、依存性注入コンテナなし、XML 設定なし。書いたものがそのまま実行されます。

---

## なぜ重要？

フレームワークの選択はプロジェクトのあらゆる面に長期的な影響を与えます：

| 関心事 | Javalin | Spring Boot |
|--------|---------|-------------|
| **起動時間** | ~200ms | 3-8秒 |
| **学習曲線** | 数時間 | 数週間 |
| **魔法** | なし（明示的なコード） | 多い（アノテーション、自動設定） |
| **依存関係数** | ~10 | 100以上 |
| **JAR サイズ** | ~5 MB | 30+ MB |
| **デバッグ** | コードを読む | ドキュメントを読み、ソースを読み、Stack Overflow を読む |
| **設定** | プログラム的（Java コード） | アノテーション + YAML + properties + profiles |

**慣例**より**制御**が重要なプロジェクトには、Javalin が適切なツールです。Spring Boot のエコシステムとコミュニティサイズを、コードが何をしているかの完全な理解と引き換えにします。

---

## どう動くのか？

### 基本的な Javalin アプリケーション

```java
  import io.javalin.Javalin;

  public class Main {
      public static void main(String[] args) {
          var app = Javalin.create()
              .get("/", ctx -> ctx.result("Hello"))
              .get("/healthz", ctx -> ctx.json(Map.of("status", "ok")))
              .post("/api/users", ctx -> {
                  var body = ctx.bodyAsClass(UserRequest.class);
                  // リクエスト処理
                  ctx.json(response);
              })
              .start(8080);
      }
  }
```

Spring Boot の同等コードとの比較：

```java
  // Spring Boot では以下が必要：
  // - メインクラスに @SpringBootApplication
  // - コントローラークラスに @RestController
  // - @GetMapping / @PostMapping アノテーション
  // - コントローラーを発見するためのコンポーネントスキャン
  // - Tomcat をセットアップするための自動設定
  // - ポート設定用の properties ファイル
  // 結果：Javalin が1ファイルで済むことに6ファイル
```

### Javalin のアーキテクチャ

```
  あなたのコード（Main.java）
       │
       ▼
  ┌─────────────────────┐
  │  Javalin             │  ← 薄いラッパー
  │  - ルートマッチング   │
  │  - リクエスト/レスポンス │
  │  - ミドルウェアチェーン │
  │  - 例外ハンドラー      │
  └─────────────────────┘
       │
       ▼
  ┌─────────────────────┐
  │  Jetty               │  ← 組み込み HTTP サーバー
  │  - HTTP パース        │
  │  - TLS/SSL           │
  │  - スレッドプール      │
  └─────────────────────┘
       │
       ▼
  TCP/HTTP トラフィック
```

### Javalin のミドルウェア

```java
  // ミドルウェアはシンプルな before/after 関数：
  app.before(ctx -> {
      // すべてのリクエストの前に実行
      logger.info("{} {}", ctx.method(), ctx.path());
  });

  app.before("/api/*", ctx -> {
      // /api/* リクエストの前のみ実行
      if (ctx.sessionAttribute("user") == null) {
          throw new UnauthorizedResponse();
      }
  });

  app.after(ctx -> {
      // すべてのリクエストの後に実行
      ctx.header("X-Request-Id", UUID.randomUUID().toString());
  });
```

### なぜ他の軽量オプションでなく Javalin か？

| フレームワーク | 言語 | 起動時間 | 備考 |
|-------------|------|---------|------|
| **Javalin** | Java/Kotlin | ~200ms | Jetty ベース、関数的 API |
| **Spark Java** | Java | ~200ms | Javalin に似ているがメンテナンスが少ない |
| **Vert.x** | Java | ~500ms | リアクティブ、イベントループ -- より複雑 |
| **Micronaut** | Java | ~1s | コンパイル時 DI、AOT -- より多くの魔法 |
| **Quarkus** | Java | ~800ms | GraalVM ネイティブ -- より多くのツール |
| **Express.js** | Node.js | ~200ms | 異なる言語/エコシステム |

volta に Javalin が選ばれたのは、「理解できる地獄を選べ」哲学に合致するからです：すべての行を読めるほどシンプルで、本番に十分な力を持つ。

---

## volta-auth-proxy ではどう使われている？

volta は Web フレームワークとして Javalin 6.x を使用しています。アプリケーション全体が単一の `Main.java` ファイルで設定されています。

### volta の Javalin セットアップ

```java
  var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.staticFiles.add("/public");
  });

  // ミドルウェア
  app.before(SessionMiddleware::handle);
  app.before("/api/*", AuthMiddleware::handle);

  // ルート
  app.get("/healthz", HealthController::check);
  app.get("/login", AuthController::login);
  app.get("/callback", AuthController::callback);
  app.get("/api/v1/tenants/{tid}/members", MemberController::list);
  app.post("/api/v1/tenants/{tid}/members/invite", MemberController::invite);
  // ... その他のルート

  app.start(config.port());
```

### なぜこの設計が volta で機能するか

| Javalin の機能 | volta の利点 |
|---------------|------------|
| **明示的なルート** | すべてのエンドポイントが1ファイルで見える。スキャンの驚きなし。 |
| **ラムダハンドラー** | ハンドラーはプレーンな Java メソッド。テストしやすく、デバッグしやすい。 |
| **Before/after フック** | アノテーション魔法なしのセッション、CSRF、認証チェック。 |
| **組み込み Jetty** | 単一 JAR デプロイ。外部サーバー不要。 |
| **DI コンテナなし** | オブジェクトは `new` で作成。コンストラクタ = 配線図。 |
| **Jackson 経由の JSON** | `ctx.json()` と `ctx.bodyAsClass()` でクリーンなシリアライゼーション。 |

### 「制御が王様」との一致

Javalin は volta の哲学を体現しています：

```
  Spring Boot アプローチ：
    「フレームワークに任せろ。」
    @EnableWebSecurity
    @EnableOAuth2Client
    @EnableGlobalMethodSecurity
    （これら実際に何をする？500ページのドキュメントを読め。）

  Javalin アプローチ：
    「自分で処理する。明示的に。」
    app.before("/api/*", ctx -> {
        var session = SessionStore.get(ctx.cookie("__volta_session"));
        if (session == null) throw new UnauthorizedResponse();
        ctx.attribute("session", session);
    });
    （これは何をする？コードを読め。そこにある。）
```

---

## よくある間違いと攻撃

### 間違い1：間違ったプロジェクトに Javalin を使う

Javalin は制御が欲しい小〜中規模アプリケーションに優れています。200人のチームが500エンドポイントのエンタープライズ SaaS を構築するなら、Spring Boot の慣例とエコシステムがより良い選択かもしれません。規模を把握しましょう。

### 間違い2：Spring Boot のエコシステムが恋しい

Javalin には Spring Security、Spring Data、Spring Cloud、数千の Spring 互換ライブラリがありません。代替を自分で構築または探す必要があります。これは機能（魔法が少ない）であると同時にコスト（作業が多い）です。

### 間違い3：エラーハンドリングを追加しない

Javalin のシンプルさは、エラーハンドリングを自動追加しないことを意味します。未処理の例外は生の 500 エラーを返します。必ず追加しましょう：

```java
  app.exception(Exception.class, (e, ctx) -> {
      logger.error("Unhandled error", e);
      ctx.status(500).json(Map.of("error", "Internal server error"));
  });
```

### 間違い4：Javalin はスケールしないと思い込む

Javalin は Jetty 上で動作し、Jetty は Eclipse Foundation 等の組織で日に数百万リクエストを処理しています。フレームワークは軽量ですが、下の HTTP サーバーは実戦で鍛えられています。

---

## さらに学ぶ

- [startup.md](startup.md) -- Javalin が volta の ~200ms 起動をどう可能にするか。
- [greenfield.md](greenfield.md) -- グリーンフィールドプロジェクトで Javalin が選ばれた理由。
- [yagni.md](yagni.md) -- Javalin のミニマルアプローチは YAGNI に合致。
- [Javalin ドキュメント](https://javalin.io/) -- 公式ドキュメントとチュートリアル。
- [Javalin GitHub](https://github.com/javalin/javalin) -- ソースコードと例。
