# テンプレート

[English version](template.md)

---

## これは何？

テンプレートとは、[ブラウザ](browser.md)に送る前に実際のデータで埋められるプレースホルダーを持った[HTML](html.md)ドキュメントです。すべてのユーザーに対して別々のHTMLページを書く代わりに、1つのテンプレートを書き、誰かがページをリクエストするたびに[サーバー](server.md)が正しいデータを挿入します。

定型文に似ています。定型文には空欄があります：「______様、お客様の口座残高は______です」。文面は一度だけ書かれます。送付するとき、システムが各人の名前と残高を埋めます。テンプレートは空欄のある文面。データが空欄を埋めます。結果は各ユーザーにパーソナライズされたページです。

volta-auth-proxyはテンプレートエンジンとして[jte](jte.md)（Java Template Engine）を使用しています。他の人気のあるテンプレートエンジンにはThymeleaf、[FreeMarker](freemarker.md)、Mustache、Handlebars、EJSなどがあります。

---

## なぜ重要なのか？

テンプレートがなければ、動的なHTMLの生成は以下を意味します：

- [Java](java.md)コードで文字列を連結：`"<h1>" + userName + "</h1>"` -- 読みにくく、エラーが起きやすく、[XSS](xss.md)に脆弱
- あり得るすべての状態に対して別々のHTMLファイルを書く -- 保守不可能
- ビジネスロジックとプレゼンテーションロジックを混在させる -- 関心の分離に違反

テンプレートは3つの問題すべてを解決します：

- **読みやすい** -- テンプレートは小さな挿入のあるHTMLのように見える
- **保守しやすい** -- 1つのテンプレートが無限のバリエーションに対応
- **安全** -- 良いテンプレートエンジンは出力を自動エスケープし、XSSを防止

---

## どう動くのか？

### テンプレートレンダリングサイクル

```
  テンプレートファイル (login.jte)     データ（Javaオブジェクト）
  ─────────────────────────           ──────────────────
  <h1>Welcome to ${tenantName}</h1>   tenantName = "ACME Corp"
  <p>Hello, ${userName}</p>           userName = "Taro Yamada"
           │                                  │
           └──────────┬───────────────────────┘
                      │
                      ▼
               テンプレートエンジン
             （プレースホルダーを埋める）
                      │
                      ▼
          レンダリング済みHTML（ブラウザに送信）
          ──────────────────────────────
          <h1>Welcome to ACME Corp</h1>
          <p>Hello, Taro Yamada</p>
```

### プレースホルダーと式

テンプレートはデータを挿入する場所を特別な構文で示します：

```html
<!-- jte構文 -->
<h1>${tenantName}</h1>              <!-- 単純な値 -->
<p>Role: ${user.roles().get(0)}</p> <!-- メソッド呼び出し -->

<!-- 条件分岐レンダリング -->
@if(user.isAdmin())
    <a href="/admin">管理パネル</a>
@endif

<!-- ループ -->
@for(var member : members)
    <tr>
        <td>${member.name()}</td>
        <td>${member.role()}</td>
    </tr>
@endfor
```

### 自動エスケープ（XSS保護）

良いテンプレートエンジンはHTML特殊文字を自動的にエスケープします：

```
  ユーザー入力:     <script>alert('hacked')</script>
  エスケープなし:   <script>alert('hacked')</script>   ← JavaScriptとして実行される！
  エスケープあり:   &lt;script&gt;alert('hacked')&lt;/script&gt;  ← テキストとして表示
```

jteはデフォルトで自動エスケープします。ユーザーの名前が`<script>alert('xss')</script>`でも、テンプレートは実行可能なコードではなく無害なテキストとしてレンダリングします。

### テンプレートエンジンの比較

| エンジン | 言語 | 型安全？ | 自動エスケープ？ | 使用者 |
|---------|------|---------|-------------|--------|
| [jte](jte.md) | Java | はい | はい | volta-auth-proxy |
| Thymeleaf | Java | いいえ | はい | Spring Boot（一般的） |
| [FreeMarker](freemarker.md) | Java | いいえ | オプション | Keycloak |
| JSP | Java | いいえ | いいえ（デフォルト） | レガシーJavaアプリ |
| EJS | JavaScript | いいえ | いいえ | Express.jsアプリ |
| Jinja2 | Python | いいえ | はい | Flask/Django |

### 型安全テンプレート（jteの利点）

従来のテンプレートエンジンは任意のオブジェクトを受け入れ、データが間違っていると実行時に失敗します。jteのような[型安全](type-safe.md)テンプレートは[コンパイル](compile.md)時にチェックします：

```
  従来型（実行時エラー）:
  ──────────────────────────
  テンプレート: ${user.nmae}        ← タイポ: "name"ではなく"nmae"
  コンパイル:   ✓ エラーなし        ← コンパイラはテンプレートをチェックしない
  実行時:      ✗ 47行目でNullPointerException  ← ユーザーがバグを発見

  型安全 / jte（コンパイルエラー）:
  ─────────────────────────────
  テンプレート: ${user.nmae}        ← タイポ: "name"ではなく"nmae"
  コンパイル:   ✗ エラー: シンボル"nmae"が見つかりません  ← 開発者がバグを発見
  実行時:      到達しない           ← デプロイ前にバグを捕捉
```

---

## volta-auth-proxy ではどう使われている？

### 認証ページ用のjteテンプレート

voltaはサーバーレンダリングページにjteテンプレートを使用しています：

```
  voltaテンプレート (src/main/jte/):
  ├── login.jte           → Google OIDCボタン付きログインページ
  ├── tenant-select.jte   → ログイン後のマルチテナントセレクター
  ├── invite-accept.jte   → 招待承諾ページ
  ├── error.jte           → エラー表示ページ
  └── layout/
      └── main.jte        → 共有レイアウト（ヘッダー、フッター、CSS）
```

### ハンドラーでのテンプレートレンダリング

Javalinハンドラーでのテンプレートレンダリング：

```java
app.get("/auth/login", ctx -> {
    var model = new LoginPage(
        tenantName,     // "ACME Corp"
        googleClientId, // サインインボタン用
        csrfToken       // CSRF対策保護
    );
    ctx.render("login.jte", Map.of("page", model));
});
```

ハンドラーがデータオブジェクトを作成し、テンプレートに渡すと、jteがHTMLをレンダリングします。ハンドラーが手動でHTML文字列を構築することはありません。

### レイアウトテンプレート（コンポジション）

voltaはHTML定型文の繰り返しを避けるためにレイアウトテンプレートを使用しています：

```html
<!-- layout/main.jte -->
<!DOCTYPE html>
<html lang="en">
<head>
    <title>${title}</title>
    <link rel="stylesheet" href="/public/style.css">
</head>
<body>
    <main>
        ${content}
    </main>
</body>
</html>
```

個々のページテンプレートがこのレイアウトを拡張し、固有のコンテンツだけを提供します。ヘッダーやフッターを変更すると、すべてのページに一度に反映されます。

### なぜ認証ページにサーバーレンダリングテンプレートを使うのか？

voltaは認証ページに[SPA](spa.md)ではなくサーバーレンダリングテンプレートを意図的に使用しています：

1. **JavaScript非依存** -- JavaScriptが壊れていたりブロックされていてもログインが動く必要がある
2. **クライアントレンダリングによるXSSなし** -- サーバーが生成したHTMLには注入されたスクリプトを含められない
3. **リダイレクトに適している** -- OIDCフローはHTTPリダイレクトを伴い、サーバーページと自然に連携する
4. **シンプル** -- 認証ページは単純なフォームであり、SPAフレームワークは過剰

---

## よくある間違いと攻撃

### 間違い1：自動エスケープを無効にする

一部のテンプレートエンジンは特別な構文で「生の」HTMLを出力できます（例：jteの`$unsafe{value}`、Bladeの`{!! value !!}`）。ユーザー制御のデータでこれを使うと[XSS](xss.md)脆弱性が開きます。生の出力は信頼された開発者制御のHTMLにのみ使用してください。

### 間違い2：テンプレートにビジネスロジックを入れる

テンプレートはプレゼンテーションだけを扱うべきです。「このユーザーが管理パネルを見られるか？」のようなロジックはテンプレートではなくハンドラーで決定すべきです：

```
  間違い（テンプレートにロジック）:
  @if(db.query("SELECT role FROM users WHERE id = " + userId) == "ADMIN")
      <a href="/admin">管理</a>
  @endif

  正しい（ハンドラーにロジック、テンプレートはレンダリングのみ）:
  // ハンドラー:
  model.showAdminLink = user.isAdmin();

  // テンプレート:
  @if(page.showAdminLink)
      <a href="/admin">管理</a>
  @endif
```

### 攻撃1：サーバーサイドテンプレートインジェクション（SSTI）

ユーザー入力がデータではなくテンプレート自体に挿入されると、攻撃者が任意のコードを実行できます：

```
  脆弱:
  engine.render("Hello " + userInput)
  // userInput = "${Runtime.exec('rm -rf /')}" の場合 → コード実行！

  安全:
  engine.render("hello.jte", Map.of("name", userInput))
  // userInputはデータであり、テンプレートコードではない
```

voltaは常にユーザーデータをテンプレートパラメータとして渡し、テンプレート文字列の一部としては渡しません。

### 間違い3：テンプレートをプリコンパイルしない

テンプレートエンジンは起動時にテンプレートをコンパイル（高速）するか、リクエストごとにコンパイル（低速）できます。jteは[ビルド](build.md)ステップ（`mvn compile`）中のプリコンパイルをサポートしているため、テンプレートは一度だけコンパイルされ、ページ読み込みごとではありません。voltaは本番環境のパフォーマンスのためにプリコンパイル済みテンプレートを使用しています。

---

## さらに学ぶ

- [jte.md](jte.md) -- voltaが使用する具体的なテンプレートエンジン。
- [type-safe.md](type-safe.md) -- テンプレートにとってコンパイル時の型チェックがなぜ重要か。
- [html.md](html.md) -- テンプレートの出力フォーマット。
- [xss.md](xss.md) -- 自動エスケープが防ぐ攻撃。
- [freemarker.md](freemarker.md) -- 代替テンプレートエンジン（Keycloakが使用）。
- [frontend-backend.md](frontend-backend.md) -- テンプレートがバックエンドデータとフロントエンド表示の架け橋。
- [spa.md](spa.md) -- サーバーレンダリングテンプレートの代替。
