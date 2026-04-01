# 型安全

[English version](type-safe.md)

---

## これは何？

型安全とは、[コンパイラ](compile.md)がコード中で正しい種類のデータが正しい場所で使われているかチェックし、プログラムが実行される前にミスを捕捉することです。関数が数値を期待しているのにテキストを渡してしまった場合、型安全な言語は[コンパイル](compile.md)を拒否し、すぐにミスを修正させます。

鍵と錠前に似ています。型安全なシステムは、正しい鍵だけが正しい錠前に合うことを保証します。車の鍵で玄関のドアを開けようとしたら、システムはすぐに（コンパイル時に）止めてくれます。ドアの前に立って試して失敗する（実行時に）のではなく。型安全がなければ、鍵が間違っていることをドアの前に立つまで知れません -- さらに悪いことに、錠前が間違った鍵を受け入れて誰にでも開いてしまうかもしれません。

volta-auth-proxyが使う[Java](java.md)は型安全な言語です。[JavaScript](javascript.md)はそうではありません（TypeScriptが型チェックを追加しますが）。voltaが使う[jte](jte.md)テンプレートエンジンは型安全であり、代替よりも選ばれた理由の1つです。

---

## なぜ重要なのか？

型安全がなければ、バグは本番まで隠れます：

- [テンプレート](template.md)が`user.name`ではなく`user.nmae`を参照 -- そのコードパスを通らなければ開発中は動き、本番の午前3時にクラッシュ
- 関数が`String`を期待する場所で`null`を返す -- 実行時にNullPointerException
- APIハンドラーが`tenantId`を`String`として読むがデータベースは`UUID`を期待 -- 最初の実リクエストが来た時に失敗

型安全はこれらのバグをコンパイル時に捕捉します：

- **フィードバックが速い** -- ミスを数秒で知れる（コンパイルエラー）、数時間後ではなく（本番クラッシュ）
- **安全なリファクタリング** -- フィールド名を変更するとコンパイラが更新が必要なすべてのファイルを教えてくれる
- **自己文書化コード** -- 型は常に最新のドキュメントとして機能する
- **テストが少なくて済む** -- コンパイラがバグのカテゴリ全体を排除し、ユニットテストが不要になる

---

## どう動くのか？

### 静的型付け vs 動的型付け

```
  静的型付け（Java - 型安全）:
  ─────────────────────────────────
  String name = "Taro";
  int age = 30;

  name = 42;          // ✗ コンパイルエラー: 互換性のない型
  age = "thirty";     // ✗ コンパイルエラー: 互換性のない型

  // コンパイラがプログラム実行前にこれらを捕捉。


  動的型付け（JavaScript - 型安全でない）:
  ─────────────────────────────────────────────
  let name = "Taro";
  let age = 30;

  name = 42;          // ✓ エラーなし（nameが数値になる）
  age = "thirty";     // ✓ エラーなし（ageが文字列になる）

  // 下流で特定の型が期待されるまでこれらは「動く」。
```

### コンパイラが型をどう使うか

```
  ソースコード                   コンパイラ                   結果
  ───────────                   ────────                     ──────

  void greet(String name) {     チェック: "Taro"は           ✓ コンパイル成功
      print("Hi " + name);     Stringか？ はい。
  }
  greet("Taro");


  void greet(String name) {     チェック: 42は               ✗ エラー:
      print("Hi " + name);     Stringか？ いいえ。          "intはStringに
  }                                                          変換できません"
  greet(42);
```

### Javaレコードの型安全

voltaはデータクラスにJavaレコードを使用しており、本質的に型安全です：

```java
// コンパイラがすべてのTenantにこれらの正確な型を強制
public record Tenant(
    UUID id,           // UUIDでなければならない、Stringではない
    String name,       // Stringでなければならない、intではない
    String slug,       // Stringでなければならない
    Instant createdAt  // Instantでなければならない、Stringではない
) {}

// これはコンパイルされる：
new Tenant(UUID.randomUUID(), "ACME Corp", "acme", Instant.now());

// これはコンパイルされない：
new Tenant("not-a-uuid", "ACME Corp", "acme", "2024-01-01");
// エラー: StringはUUIDに変換できません
// エラー: StringはInstantに変換できません
```

### テンプレートの型安全（jte vs 他のエンジン）

ここが型安全がvoltaにとって最大の実用的な違いを生む場所です：

```
  従来のテンプレート（Thymeleaf、FreeMarker）:
  ─────────────────────────────────────────────
  テンプレート: <h1>${user.nmae}</h1>          ← タイポ
  コンパイル:   ✓（テンプレートはチェックされない）
  実行時:      ✗ ページレンダリング時にエラー
  発見:        ユーザーが「ページが壊れている」と報告

  型安全テンプレート（jte）:
  ─────────────────────────
  テンプレート: <h1>${user.nmae}</h1>          ← タイポ
  コンパイル:   ✗ エラー: レコードUserにシンボル"nmae"が見つかりません
  実行時:      到達しない
  発見:        開発者がビルド中にすぐエラーを確認
```

### 型安全のコスト

型安全は無料ではありません。以下が必要です：

- 型を明示的に宣言する（ただしJavaの`var`が冗長さを減らす）
- 構造化データ用のデータクラス/レコードを定義する
- 型変換を明示的に処理する（例：`UUID.fromString(str)`）
- コンパイルを待つ（ただしインクリメンタルコンパイルは高速）

voltaのようなサーバーサイドアプリケーションでは、型エラーがセキュリティバイパスにつながる可能性があるため、このトレードオフは圧倒的に価値があります。

---

## volta-auth-proxy ではどう使われている？

### コードベース全体でのJavaの型システム

voltaはJavaの型システムを使ってあらゆるレイヤーでバグを防いでいます：

```java
// ルートパラメータの抽出 -- 型安全
UUID memberId = UUID.fromString(ctx.pathParam("memberId"));
// パスパラメータが有効なUUIDでなければ、即座に例外がスローされる。
// 間違った型への暗黙的変換なし。

// ロール比較 -- 型安全なenum
public enum Role { OWNER, ADMIN, MEMBER, VIEWER }

if (role.isAtLeast(Role.ADMIN)) { ... }
// ロールを"admin"のような文字列と誤って比較できない
// ロール名をスペルミスできない -- コンパイラが捕捉する
```

### 型安全なjteテンプレート

voltaの[jte](jte.md)テンプレートは期待するデータ型を宣言します：

```java
// login.jte
@param LoginPage page

<h1>Welcome to ${page.tenantName()}</h1>
<input type="hidden" value="${page.csrfToken()}">
```

`LoginPage`レコードに`tenantName()`メソッドがなければ、テンプレートはコンパイルされません。メソッド名を変更すると、コンパイラがそれを参照するすべてのテンプレートにフラグを立てます。

### 型安全な設定

voltaは生の文字列マップの代わりに型付き設定オブジェクトを使用しています：

```java
// 型安全な設定
public record VoltaConfig(
    int port,
    String googleClientId,
    String googleClientSecret,
    Duration sessionTimeout,
    Duration jwtExpiry
) {}

// 使用 -- コンパイラが正しい型を保証
var config = new VoltaConfig(
    7070,
    env("GOOGLE_CLIENT_ID"),
    env("GOOGLE_CLIENT_SECRET"),
    Duration.ofHours(8),
    Duration.ofMinutes(5)
);
```

`port`を`"seven thousand"`に、`sessionTimeout`を整数に誤って設定することはできません。

### 型安全なデータベース結果

voltaがデータベースから読み取る際、結果を型付きレコードにマッピングします：

```java
public record UserRow(UUID id, String email, String displayName, Instant createdAt) {}

UserRow user = db.queryOne(
    "SELECT id, email, display_name, created_at FROM users WHERE id = ?",
    userId,
    rs -> new UserRow(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getString("display_name"),
        rs.getTimestamp("created_at").toInstant()
    )
);
```

コンパイラがすべてのフィールドが正しい型であることを保証します。不一致は即座に捕捉されます。

---

## よくある間違いと攻撃

### 間違い1：どこでもObjectやMapを使う

JavaはObject`や`Map<String, Object>`を使って型安全をバイパスできます：

```java
// 型安全でない -- コンパイラチェックをすべて失う
Map<String, Object> user = new HashMap<>();
user.put("name", "Taro");
user.put("age", "thirty");  // intの代わりにString -- エラーなし！

// 型安全 -- コンパイラが誤用を捕捉
record User(String name, int age) {}
new User("Taro", "thirty");  // ✗ コンパイルエラー
```

voltaはデータの受け渡しに`Map<String, Object>`を避け、型付きレコードとクラスを使用しています。

### 間違い2：コンパイラ警告を無視する

Javaのコンパイラは未チェックの型キャスト、生の型、その他の型安全違反の可能性について警告します。`@SuppressWarnings("unchecked")`でこれらの警告を抑制すると、型安全の目的が台無しになります。

### 間違い3：チェックなしのキャスト

```java
// 危険 -- 実行時にClassCastException
String name = (String) ctx.attribute("userId");  // userIdは実際にはUUID

// 安全 -- 最初から正しい型を使う
UUID userId = ctx.attribute("userId");  // 適切に型付け
```

### 攻撃1：動的言語の型混乱

[JavaScript](javascript.md)（型安全でない）では`"0" == false`は`true`、`"" == 0`は`true`です。これらの型強制ルールが、認証チェックが失敗すべき時に通ってしまうセキュリティ脆弱性を引き起こしてきました。Javaの型システムはこのバグのカテゴリ全体を防ぎます。

### 間違い4：ジェネリクスを使わない

```java
// 型安全でない -- Listは何でも含められる
List roles = new ArrayList();
roles.add("ADMIN");
roles.add(42);         // エラーなし、だが後で壊れる
String role = (String) roles.get(1);  // ClassCastException!

// 型安全 -- ListはRoleだけを含められる
List<Role> roles = new ArrayList<>();
roles.add(Role.ADMIN);
roles.add(42);         // ✗ コンパイルエラー
```

---

## さらに学ぶ

- [compile.md](compile.md) -- 型安全を強制するプロセス。
- [jte.md](jte.md) -- voltaの型安全なテンプレートエンジン。
- [java.md](java.md) -- voltaが使う型安全な言語。
- [javascript.md](javascript.md) -- 動的型付けの言語（対比）。
- [template.md](template.md) -- 型安全がレンダリングバグを防ぐ場所。
- [variable.md](variable.md) -- 型安全な言語では変数に型がある。
