# jte（Java Template Engine）

[English version](jte.md)

---

## これは何？

jte（Java Template Engine）は、テンプレートとアプリケーションのデータを組み合わせてHTMLを生成する、モダンで型安全なJava向けテンプレートエンジンです。

ワープロの差し込み印刷のようなものです。「_____様、ご注文番号_____が準備できました」という空欄付きの手紙テンプレートを書きます。名前と注文番号のリストを入れると、ワープロが一人ずつ空欄を埋めてくれます。jteも同じですが、Webページ用です：プレースホルダー付きのHTMLテンプレートを書くと、jteがリクエストごとに実データ（ユーザー名、テナント情報、エラーメッセージなど）で埋めてくれます。

---

## なぜ重要なのか？

Webアプリケーションは動的コンテンツを含むHTMLを生成する必要があります：ログイン中のユーザー名、チームメンバーの一覧、エラーメッセージなど。これを行う方法は多くあり、その選択は思っている以上に重要です。

### 代替品

| テンプレートエンジン | 言語 | 特徴 |
|-------------------|------|------|
| **jte** | Java | 型安全、高速、モダンな構文 |
| **Thymeleaf** | Java | Spring Bootのデフォルト、「ナチュラルテンプレート」 |
| **FreeMarker** | Java | 古い（2002年）、強力だが冗長、[Keycloak](keycloak.ja.md)が使用 |
| **JSP** | Java | 古代（1999年）、Javaオリジナルのテンプレートシステム |
| **Pug/EJS** | Node.js | Express.jsアプリで人気 |
| **Jinja2** | Python | Flask/Djangoエコシステム |

### voltaが代替品よりjteを選んだ理由

**jte vs FreeMarker（Keycloakの選択）：**

FreeMarkerはKeycloakが使っているものです。動きますが、2002年製で古さが見えます：

```
  FreeMarker（Keycloakテーマ）：
  ┌──────────────────────────────────────┐
  │ <#if user??>                         │  ← 変なnullチェック構文
  │   <h1>Hello ${user.name}</h1>        │
  │ </#if>                               │
  │ <#list items as item>                │
  │   <li>${item.label}</li>             │
  │ </#list>                             │  ← コンパイル時チェックなし
  └──────────────────────────────────────┘

  jte（volta）：
  ┌──────────────────────────────────────┐
  │ @if(user != null)                    │  ← おなじみのJava構文
  │   <h1>Hello ${user.name()}</h1>      │
  │ @endif                               │
  │ @for(var item : items)               │
  │   <li>${item.label()}</li>           │
  │ @endfor                              │  ← コンパイル時の型チェック
  └──────────────────────────────────────┘
```

**jte vs Thymeleaf（Spring Bootのデフォルト）：**

Thymeleafは処理なしでも有効なHTMLになる「ナチュラルテンプレート」を使います：

```html
<!-- Thymeleaf -->
<h1 th:text="${title}">Default Title</h1>
<div th:each="user : ${users}">
  <span th:text="${user.name}">John Doe</span>
</div>
```

エレガントですがコストがあります：エラーは実行時にのみ検出されます。`${user.name}`の代わりに`${usr.name}`とタイプミスしても、ユーザーがそのページにアクセスして初めて気づきます -- ビルド時ではなく。

jteはこれらのエラーをコンパイル時に、デプロイ前に検出します。

---

## jteの仕組み

### 型安全なパラメータ

すべてのjteテンプレートは先頭でパラメータを宣言します。Javaコンパイラが正しい型を渡しているかチェックします：

```html
@param String title
@param java.util.Map<String, String> inviteContext
@param String startUrl
<!doctype html>
<html lang="ja">
<head>
    <meta charset="utf-8">
    <title>${title}</title>
</head>
<body>
<main>
    <h1>ログイン</h1>
    @if(inviteContext != null)
        <p><strong>${inviteContext.get("tenantName")}</strong> に招待されています。</p>
    @endif
    <a class="button" href="${startUrl}">Google でログイン</a>
</main>
</body>
</html>
```

先頭の`@param`行がポイントです。このテンプレートがどんなデータを期待するかをjte（とJavaコンパイラ）に正確に伝えます。Javaコードが`startUrl`を提供せずにこのテンプレートを描画しようとすると、実行時のクラッシュではなくコンパイルエラーが出ます。

### 自動HTMLエスケープ

jteは出力のHTML特殊文字を自動的にエスケープします。つまり：

```
  user.name() が返す値: <script>alert('XSS')</script>

  jteの描画結果: &lt;script&gt;alert('XSS')&lt;/script&gt;

  ブラウザはスクリプトを実行せず、テキストとしてそのまま表示。
```

これによりデフォルトで[XSS](xss.ja.md)攻撃から保護されます。エスケープを明示的にオプトアウトする必要があり（`$unsafe{...}`を使用）、危険な選択がコードレビューで可視化されます。

---

## voltaでのjteの使い方

volta-auth-proxyはすべてのHTMLページにjteを使用しています：

```
src/main/jte/
├── layout/
│   └── base.jte              ← 共有レイアウト（ヘッダー、フッター、CSS）
├── auth/
│   ├── login.jte             ← ログインページ（「Googleでサインイン」）
│   ├── callback.jte          ← OAuthコールバック処理
│   ├── tenant-select.jte     ← テナント選択（ユーザーが複数持つ場合）
│   ├── invite-consent.jte    ← 「この招待を承認しますか？」ページ
│   └── sessions.jte          ← アクティブセッション管理
├── admin/
│   ├── members.jte           ← テナントメンバー管理
│   ├── invitations.jte       ← 招待管理
│   ├── webhooks.jte          ← Webhook設定
│   ├── tenants.jte           ← テナント管理
│   ├── users.jte             ← ユーザー管理
│   ├── audit.jte             ← 監査ログビューア
│   └── idp.jte               ← アイデンティティプロバイダ設定
└── error/
    └── error.jte             ← エラーページ
```

### voltaの哲学で重要な理由

voltaの核心的な原則の一つは、ログインUIを**完全にコントロール**できることです。Auth0では彼らの制限内でカスタマイズします。KeycloakではFreeMarkerテーマと格闘します。voltaでは：

1. `.jte`ファイルを開く
2. HTMLを好きなように編集
3. 自分のCSS、JavaScript、ブランディングを追加
4. voltaを再起動（または開発時にホットリロード）
5. 完了

テーマシステムなし、学ぶべきレイアウト継承ルールなし、「テーマディレクトリでこのファイルをオーバーライド」というダンスなし。データのプレースホルダー付きのHTMLテンプレートです。HTMLを知っていれば、voltaのUIをカスタマイズできます。

---

## シンプルな例

最小限のjteテンプレート：

```html
@param String userName
@param String tenantName
@param java.util.List<String> roles

<!doctype html>
<html>
<body>
    <h1>ようこそ、${userName}さん</h1>
    <p><strong>${tenantName}</strong> 組織に所属しています。</p>

    <h2>あなたのロール：</h2>
    <ul>
    @for(String role : roles)
        <li>${role}</li>
    @endfor
    </ul>
</body>
</html>
```

それを描画するJavaコード：

```java
ctx.render("dashboard.jte", Map.of(
    "userName", "山田太郎",
    "tenantName", "ACME Corp",
    "roles", List.of("ADMIN", "MEMBER")
));
```

出力はクリーンで型チェック済みのHTMLです。`tenantName`を渡し忘れたら、デプロイ前にコンパイラが教えてくれます。

---

## さらに学ぶために

- [jte公式ドキュメント](https://jte.gg/) -- 例付きの完全なjteドキュメント。
- [keycloak.ja.md](keycloak.ja.md) -- FreeMarkerテンプレートがなぜ苦痛か（voltaが避けているもの）。
- [xss.ja.md](xss.ja.md) -- jteの自動エスケープが防ぐ攻撃。
- [config-hell.ja.md](config-hell.ja.md) -- voltaのシンプルさの哲学はテンプレートにも及ぶ。
