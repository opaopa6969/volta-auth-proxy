# 変数

[English version](variable.md)

---

## これは何？

変数とは、値を保持する名前付きの入れ物です。名前をつけ、中に値を入れ、後でその名前を使って値を参照します。プログラミングでは、変数は数値、テキスト、ユーザーID、設定値などのデータを格納します。プログラムが実行されるとき、値をハードコードする代わりに変数から値を読み取ります。

ラベル付きの箱に似ています。箱の外側に「ポート番号」と書き、中に「7070」を入れます。後でポート番号が必要になったら、「ポート番号」とラベルの付いた箱を見て7070を見つけます。ラベルを変えずに箱の中身を変えることもできます。明日は同じ箱に「8080」を入れるかもしれません。

volta-auth-proxyにおいて最も重要な変数の種類は**[環境変数](environment-variable.md)** -- プログラムの外部で設定され、動作を設定する変数です。

---

## なぜ重要なのか？

変数がなければ：

- すべての値がハードコードされる：あらゆる場所に`port = 7070`。ポートを変えるにはすべてのファイルを編集する。
- 設定が不可能になる。同じプログラムを異なる設定で実行できない。
- データ処理が不可能になる。リクエストからユーザーIDを保存し、後でデータベースクエリに使うことができない。
- 秘密情報がソースコードに埋め込まれる。Google Client IDがMain.javaに記載され、コードを読む誰にでも見える。

変数が可能にすること：

- **柔軟性** -- コードを変えずに動作を変更
- **再利用性** -- 同じコード、異なる値
- **抽象化** -- 概念に一度名前をつけ、どこでも使う
- **セキュリティ** -- 秘密情報をコードの外に、[環境変数](environment-variable.md)に保持

---

## どう動くのか？

### Javaでの変数の基本

```java
// 変数を宣言して代入
String tenantName = "ACME Corp";
int port = 7070;
UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
boolean isAdmin = true;

// 変数を使う
System.out.println("Welcome to " + tenantName);  // "Welcome to ACME Corp"
app.start(port);                                    // 7070で起動
```

### Javaの変数の型

[型安全](type-safe.md)な言語であるJavaでは、すべての変数に型があります：

| 型 | 保持するもの | 例 |
|---|-----------|---|
| `String` | テキスト | `"ACME Corp"`, `"taro@example.com"` |
| `int` | 整数 | `7070`, `5`, `0` |
| `long` | 大きな整数 | `1711899700L`（Unixタイムスタンプ） |
| `boolean` | 真か偽 | `true`, `false` |
| `UUID` | 一意識別子 | `550e8400-e29b-41d4-a716-446655440000` |
| `Duration` | 時間の期間 | `Duration.ofMinutes(5)` |
| `List<Role>` | ロールのリスト | `[ADMIN, MEMBER]` |

### スコープ：変数が存在する場所

変数は特定のスコープ -- アクセス可能なコードの領域 -- 内に存在します：

```java
public class Example {
    // クラスレベル変数：クラス全体でアクセス可能
    private final String appName = "volta-auth-proxy";

    public void handleRequest(Context ctx) {
        // メソッドレベル変数：このメソッド内でのみアクセス可能
        String userId = ctx.pathParam("userId");

        if (userId != null) {
            // ブロックレベル変数：このifブロック内でのみアクセス可能
            UUID parsed = UUID.fromString(userId);
        }
        // 'parsed'はここでは存在しない -- スコープ外
    }
}
```

```
  ┌─────────────────────────────────────────┐
  │  クラススコープ                           │
  │  appName = "volta-auth-proxy"            │
  │                                          │
  │  ┌───────────────────────────────────┐   │
  │  │  メソッドスコープ                  │   │
  │  │  userId = "550e8400-..."           │   │
  │  │                                    │   │
  │  │  ┌─────────────────────────────┐   │   │
  │  │  │  ブロックスコープ            │   │   │
  │  │  │  parsed = UUID(550e8400...) │   │   │
  │  │  └─────────────────────────────┘   │   │
  │  └───────────────────────────────────┘   │
  └─────────────────────────────────────────┘
```

### ミュータブル vs イミュータブル変数

```java
// ミュータブル（変更可能）
String name = "Taro";
name = "Hanako";           // ✓ 許可

// イミュータブル（代入後に変更不可）
final String name = "Taro";
name = "Hanako";           // ✗ コンパイルエラー: final変数に代入できません
```

voltaは可能な限り`final`変数を好みます。イミュータブル変数は、コードの他の部分から誤って値を変更されることがないため、安全です。

### 環境変数

[環境変数](environment-variable.md)は特別なカテゴリです -- プログラムの外部、OSやコンテナ環境で設定されます：

```bash
# voltaを実行する前に環境変数を設定
export VOLTA_PORT=7070
export GOOGLE_CLIENT_ID=abc123.apps.googleusercontent.com
export DATABASE_URL=jdbc:postgresql://localhost:5432/volta

java -jar volta-auth-proxy.jar
```

Java内部では`System.getenv()`で読み取ります：

```java
int port = Integer.parseInt(System.getenv("VOLTA_PORT"));
String clientId = System.getenv("GOOGLE_CLIENT_ID");
String dbUrl = System.getenv("DATABASE_URL");
```

---

## volta-auth-proxy ではどう使われている？

### 環境変数による設定

voltaはすべての設定をハードコードされた値からではなく、環境変数から読み取ります：

```
  環境変数                       設定内容
  ────────────────────          ──────────────────
  VOLTA_PORT                     HTTPサーバーポート（デフォルト: 7070）
  GOOGLE_CLIENT_ID               Google OIDCクライアントID
  GOOGLE_CLIENT_SECRET           Google OIDCクライアントシークレット
  DATABASE_URL                   PostgreSQL接続文字列
  SESSION_TIMEOUT                セッション期間（デフォルト: 8h）
  JWT_EXPIRY                     JWT有効期間（デフォルト: 5m）
  VOLTA_BASE_URL                 voltaの公開URL
```

これにより、同じJARファイルが環境変数を変えるだけで異なる環境（開発、ステージング、本番）で実行できます。

### ルートハンドラーでの変数

リクエストスコープの変数は1つのリクエストの間だけデータを保持します：

```java
app.get("/api/v1/users/me", ctx -> {
    // これらの変数はこのリクエスト中だけ存在
    UUID userId = ctx.attribute("userId");       // 認証ミドルウェアが設定
    UUID tenantId = ctx.attribute("tenantId");   // 認証ミドルウェアが設定

    UserInfo user = userService.findById(userId, tenantId);
    ctx.json(user);
});
```

### JWTクレームでの変数

[JWT](jwt.md)クレームは本質的にトークンと一緒に移動する名前付きの値（変数）のセットです：

```json
{
  "sub": "550e8400-...",       // 変数: このユーザーは誰？
  "volta_tid": "abcd1234-...", // 変数: どのテナント？
  "volta_roles": ["ADMIN"],    // 変数: どのロール？
  "exp": 1711900000            // 変数: いつ期限切れ？
}
```

### テンプレート変数

[jte](jte.md) [テンプレート](template.md)は変数を使って動的コンテンツを埋めます：

```java
// ハンドラーがテンプレートに変数を渡す
ctx.render("dashboard.jte", Map.of(
    "userName", "Taro Yamada",
    "tenantName", "ACME Corp",
    "memberCount", 42
));
```

```html
<!-- テンプレートが変数を使う -->
<h1>Welcome, ${userName}</h1>
<p>テナント: ${tenantName} (${memberCount}メンバー)</p>
```

---

## よくある間違いと攻撃

### 間違い1：変数を使わずに値をハードコードする

```java
// 悪い例: ハードコード -- 再コンパイルなしで変更不可
String dbUrl = "jdbc:postgresql://localhost:5432/volta";

// 良い例: 環境変数 -- 再コンパイルなしで変更可能
String dbUrl = System.getenv("DATABASE_URL");
```

### 間違い2：変数を初期化しない

Javaでは、ローカル変数は使用前に代入しなければなりません：

```java
String name;
System.out.println(name);  // ✗ コンパイルエラー: 変数が初期化されていない可能性
```

### 間違い3：異なる目的で変数名を再利用する

```java
// 紛らわしい -- ここで'id'は何を意味する？
String id = ctx.pathParam("userId");
// ... 50行後 ...
id = tenant.getId().toString();  // 今度はテナントID？！

// 明確 -- 各変数が1つの目的を持つ
String userId = ctx.pathParam("userId");
String tenantId = tenant.getId().toString();
```

### 攻撃1：環境変数の漏洩

攻撃者が[プロセス](process.md)環境にアクセスできると（例：`System.getenv()`をダンプするデバッグエンドポイント経由）、すべての秘密が見えます。APIを通じて環境変数を公開しないでください。voltaには設定を明かすエンドポイントはありません。

### 間違い4：グローバルミュータブル変数を使う

どこからでも変更できるグローバルミュータブル変数は、マルチスレッドサーバーで競合状態を生みます：

```java
// マルチスレッドサーバーで危険
static String currentUser = null;  // すべてのスレッドで共有！

// 安全 -- リクエストスコープの変数を使う
ctx.attribute("userId", userId);   // リクエストごと、スレッドごと
```

voltaはグローバルミュータブル状態を避けます。設定は起動時に一度だけ`final`フィールドに読み込まれます。リクエストデータはJavalinのリクエストごとのコンテキストに格納されます。

---

## さらに学ぶ

- [environment-variable.md](environment-variable.md) -- volta設定にとって最も重要な変数の種類。
- [type-safe.md](type-safe.md) -- Javaの型システムが変数に正しいデータを保持させる方法。
- [yaml.md](yaml.md) -- 名前付きの値（変数のような）を定義する設定ファイル。
- [template.md](template.md) -- jteテンプレートでの変数。
- [uuid.md](uuid.md) -- voltaでよく使われる変数の型（識別子）。
- [java.md](java.md) -- voltaが使う変数システムの言語。
