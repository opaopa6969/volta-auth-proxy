# バニラJavaScript

[English version](vanilla-javascript.md)

---

## これは何？

バニラJavaScriptは、フレームワーク、ライブラリ、ビルドツールなしのプレーンなJavaScriptです。React、Vue、Angular、jQuery、webpack、npmは使いません。ブラウザがネイティブに理解するJavaScriptだけを直接書いて、そのまま配信します。「バニラ」は「プレーン」「飾りなし」を意味します。バニラアイスクリームのように、トッピングなしのベースフレーバーです。

自炊とミールキットの違いのようなものです。ミールキット（フレームワーク）は計量済みの材料、ステップごとの説明書、専用ツールを提供します。自炊は自分で材料を選び、基本的な調理器具を使い、すべてのディテールを制御します。ミールキットは便利ですが柔軟性がありません。キットの内容そのものを作ります。自炊はより考える必要がありますが、完全な自由を得られます。

volta-auth-proxyのフロントエンドSDK（volta.js）はバニラJavaScriptで書かれています。npm依存関係なしに認証フローを処理する約150行のプレーンJSです。

---

## なぜ重要なのか？

フレームワークには見えにくいコストがあります：

```
  フレームワークアプローチ：
  ┌──────────────────────────────────────────────┐
  │  認証コード：             ~50行               │
  │  + React：               ~140 KB（minified）  │
  │  + React DOM：           ~120 KB              │
  │  + 認証ライブラリ：       ~45 KB               │
  │  + バンドラー（webpack）：ビルドステップ必要    │
  │  + node_modules：        200+パッケージ        │
  │  ──────────────────────────────────────────   │
  │  合計：305+ KB、200+依存関係、                 │
  │       複雑なビルドパイプライン                  │
  └──────────────────────────────────────────────┘

  バニラアプローチ（volta.js）：
  ┌──────────────────────────────────────────────┐
  │  volta.js：              ~150行、~4 KB        │
  │  依存関係：              0                    │
  │  ビルドステップ：         なし                 │
  │  node_modules：          なし                 │
  │  ──────────────────────────────────────────   │
  │  合計：4 KB、依存関係0、                       │
  │       <script>タグだけ                        │
  └──────────────────────────────────────────────┘
```

バニラJSの主な利点：

- **依存関係ゼロ**：npmパッケージからのサプライチェーンリスクなし
- **ビルドステップなし**：`<script>`タグで動作、webpack/vite不要
- **極小サイズ**：メガバイトではなくキロバイト
- **フレームワーク非依存**：React、Vue、Svelte、プレーンHTMLで動作
- **長寿命**：追従すべきフレームワークバージョンなし
- **監査可能**：150行なら完全に読んで理解できる

---

## どう動くのか？

### 最新ブラウザAPIで十分

モダンブラウザのバニラJavaScriptには必要なものがすべてあります：

```javascript
// Fetch API（axiosは不要）
const response = await fetch('/auth/me', {
    credentials: 'include'  // Cookieを送信
});
const user = await response.json();

// DOM操作（jQueryは不要）
document.getElementById('user-name').textContent = user.name;

// イベント処理（フレームワーク不要）
document.getElementById('logout-btn')
    .addEventListener('click', () => {
        fetch('/auth/logout', { method: 'POST', credentials: 'include' });
    });

// テンプレートリテラル（JSXは不要）
container.innerHTML = `
    <div class="user-card">
        <h2>${user.name}</h2>
        <p>${user.email}</p>
    </div>
`;
```

### volta.jsの構造

```
  volta.js (~150行)：
  ┌──────────────────────────────────────────────┐
  │                                               │
  │  const Volta = {                              │
  │                                               │
  │    // ログイン状態を確認                       │
  │    async me() { ... }                         │
  │                                               │
  │    // ログインページにリダイレクト              │
  │    login() { ... }                            │
  │                                               │
  │    // ログアウト                               │
  │    async logout() { ... }                     │
  │                                               │
  │    // セッションリフレッシュ                    │
  │    async refresh() { ... }                    │
  │                                               │
  │    // 401自動リトライ付きfetch                 │
  │    async fetch(url, options) { ... }          │
  │                                               │
  │    // 初期化：セッション確認、                  │
  │    // 自動リフレッシュ設定                     │
  │    async init(config) { ... }                 │
  │                                               │
  │  };                                           │
  │                                               │
  └──────────────────────────────────────────────┘
```

### 任意のフレームワークでvolta.jsを使う

```html
<!-- プレーンHTML -->
<script src="/volta.js"></script>
<script>
  Volta.init({ onLogin: showDashboard, onLogout: showLanding });
</script>
```

```javascript
// React（インポートして使うだけ）
useEffect(() => {
    Volta.init({ onLogin: setUser, onLogout: () => setUser(null) });
}, []);

// Vue（同じパターン）
onMounted(() => {
    Volta.init({ onLogin: (u) => user.value = u });
});

// Svelte（同じパターン）
onMount(() => {
    Volta.init({ onLogin: (u) => user = u });
});
```

volta.jsはバニラなので、アダプターやラッパーライブラリなしにどこでも動作します。

---

## volta-auth-proxyではどう使われている？

### volta.js：フロントエンドSDK

volta.jsはvolta-auth-proxyの公式クライアントサイドSDKです。以下を処理します：

1. **セッション確認**：`Volta.me()`が`/auth/me`を呼んで現在の[セッション](session.md)を確認
2. **ログインリダイレクト**：`Volta.login()`が[OAuth2](oauth2.md)ログインフローにリダイレクト
3. **ログアウト**：`Volta.logout()`が`/auth/logout`を呼んで[セッション](session.md)と[Cookie](cookie.md)を破棄
4. **自動リフレッシュ**：有効期限前にセッションを自動リフレッシュ
5. **401リトライ**：`Volta.fetch()`が`fetch()`を401での自動[リトライ](retry.md)付きでラップ

### volta.jsの認証処理フロー

```
  ┌──────────────────────────────────────────────┐
  │  Volta.init()                                 │
  │  │                                            │
  │  ├── /auth/meを呼ぶ                            │
  │  │   ├── 200 → ログイン済み                    │
  │  │   │        → onLogin(user)を呼ぶ            │
  │  │   │        → 自動リフレッシュタイマー開始     │
  │  │   │                                        │
  │  │   └── 401 → 未ログイン                      │
  │  │            → onLogout()を呼ぶ               │
  │  │                                            │
  │  └── Volta.fetch()にリトライロジックを設定       │
  │      │                                        │
  │      ├── /api/somethingを呼ぶ                  │
  │      ├── 401の場合 → /auth/refreshを試行       │
  │      │             → 元のリクエストをリトライ    │
  │      └── まだ401 → onLogout()を呼ぶ            │
  └──────────────────────────────────────────────┘
```

### voltaがフレームワークよりバニラJSを選んだ理由

| 考慮事項 | フレームワークSDK | バニラvolta.js |
|---------|-----------------|---------------|
| npmサプライチェーンリスク | 高（100+依存関係） | **ゼロ**（依存関係0） |
| フレームワークロックイン | あり（React専用、Vue専用など） | **なし**（どこでも動作） |
| バンドルサイズ | 50-300 KB | **~4 KB** |
| ビルドが必要 | はい（webpack、viteなど） | **いいえ**（`<script>`タグ） |
| 監査可能性 | 困難（数千行） | **容易**（~150行） |
| メンテナンス負担 | フレームワーク更新のたびに対応 | **最小**（ブラウザAPIは安定） |

これはvoltaの哲学に一致します：出荷するものを理解し、依存関係を最小化し、制御を保持する。

### volta.jsとCookie

volta.jsはvolta-auth-proxyが設定する[Cookie](cookie.md)（HttpOnly、Secure、SameSite）に依存します。JavaScriptコードはCookieを直接読み取ったり操作したりしません。`credentials: 'include'`でリクエストを送り、ブラウザにCookieを自動的に処理させるだけです：

```javascript
// volta.jsはこれをしない：
document.cookie = "session=...";  // 絶対にやらない

// volta.jsはこれをする：
fetch('/auth/me', { credentials: 'include' });
// ブラウザが自動的にvolta cookieを含める
```

---

## よくある間違いと攻撃

### 間違い1：「バニラJS＝構造なし」

バニラはスパゲッティコードを意味しません。volta.jsは明確な関数境界を持つクリーンなモジュールパターンを使用しています。バニラはフレームワークなし、組織なしではありません。

### 間違い2：すべてを再発明する

バニラJSは自分でHTTPクライアントをゼロから書くべきということではありません。ブラウザAPI（`fetch`、`URL`、`crypto.subtle`）を使ってください。十分にテストされ高性能です。

### 間違い3：古いブラウザを考慮しない

モダンバニラJSは`async/await`、`fetch`、テンプレートリテラルなどを使います。IE11サポートが必要ならポリフィルが必要です。volta.jsはモダンブラウザのみを対象としています（IE11非対応）。

### 間違い4：DOM操作パターンの混在

1つのアプローチ（テンプレートリテラル、`createElement`、`textContent`）を選んで一貫性を保ってください。volta.jsは最小限のDOM操作を使用します。主にAPIコールです。

### 攻撃：npm経由のサプライチェーン攻撃

侵害されたnpmパッケージがトークンを窃取、暗号マイナーを注入、データを外部送信する可能性。volta.jsはnpm依存関係がゼロで、この攻撃面全体を排除しています。

### 攻撃：XSSトークン窃取

攻撃者がページ上でJavaScriptを実行できれば（XSS）、トークンを窃取できる可能性。防御：voltaはJavaScriptが読み取れないHttpOnly Cookieを使用。セッショントークンはクライアント側コードに公開されません。

---

## さらに学ぶために

- [cookie.md](cookie.md) -- volta.jsがブラウザCookieとどう連携するか。
- [session.md](session.md) -- volta.jsが管理するセッションライフサイクル。
- [oauth2.md](oauth2.md) -- volta.jsが開始するログインフロー。
- [retry.md](retry.md) -- volta.jsが失敗したリクエストをどうリトライするか。
- [header.md](header.md) -- volta.jsリクエストで使われるHTTPヘッダー。
