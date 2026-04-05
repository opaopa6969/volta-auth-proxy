# Embedding a SPA Console into volta-auth-proxy

volta-auth-proxy に React (or any SPA) 管理コンソールを埋め込む手順。

> **TL;DR**: SPA を `/console/` サブパスでホスティングする。
> 別サブドメインにすると Cookie/CORS 地獄。同一ドメイン・サブパスが正解。

## なぜサブパスか？

| 方式 | Cookie | CORS | CSRF | 難易度 |
|------|--------|------|------|--------|
| **同一ドメイン `/console/`** | 自動で送られる | 不要 | 不要 (same-origin) | 簡単 |
| 別サブドメイン `console.example.com` | `Domain=.example.com` 必須 | 必須 | 要対策 | 複雑 |
| 完全別ドメイン | 送れない | 必須 | 要対策 | 非常に複雑 |

## 手順

### 1. SPA をビルド

```bash
cd volta-auth-console

# vite.config.js で base を設定
# base: '/console/'

npm run build
# → dist/ に静的ファイルが生成される
```

**vite.config.js**:
```js
export default defineConfig({
  base: '/console/',
  plugins: [react(), tailwindcss()],
})
```

### 2. ビルド済みファイルを auth-proxy に配置

```bash
cp -r dist/* volta-auth-proxy/src/main/resources/public/console/
```

ディレクトリ構造:
```
src/main/resources/public/
├── console/
│   ├── index.html          ← SPA エントリポイント
│   ├── assets/
│   │   ├── index-XXXX.js   ← バンドル済み JS
│   │   └── index-XXXX.css  ← バンドル済み CSS
│   └── favicon.svg
├── css/                     ← auth-proxy 自体の CSS
└── js/                      ← auth-proxy 自体の JS
```

### 3. Javalin にルーティング追加

```java
// SPA: /console/ で index.html を返す
app.get("/console/", ctx -> {
    try (var is = Main.class.getResourceAsStream("/public/console/index.html")) {
        if (is != null) {
            ctx.contentType("text/html");
            ctx.result(is.readAllBytes());
        }
    }
});

// /console → /console/ にリダイレクト
app.get("/console", ctx -> ctx.redirect("/console/"));

// SPA fallback: /console/users, /console/audit 等の
// クライアントサイドルートで index.html を返す
// 重要: app.get ではなく app.after を使う（静的ファイルを横取りしない）
app.after("/console/*", ctx -> {
    if (ctx.status().getCode() == 404) {
        try (var is = Main.class.getResourceAsStream("/public/console/index.html")) {
            if (is != null) {
                ctx.status(200);
                ctx.contentType("text/html");
                ctx.result(is.readAllBytes());
            }
        }
    }
});
```

**注意点**:
- `app.get("/console/*")` ではなく `app.after("/console/*")` を使う
  - `app.get` は静的ファイル (`/console/assets/*.js`) も横取りして HTML を返してしまう
  - `app.after` なら Javalin の静的ファイルハンドラが先に処理し、404 の場合のみ SPA fallback
- `ctx.status()` は `HttpStatus` 型。`ctx.status().getCode()` で int 取得
- `/console/` の GET ルートは明示的に登録する（ディレクトリリダイレクトループ回避）

### 4. Jackson JSR310 モジュール（必須）

API で `java.time.Instant` 等を返す場合、Jackson はデフォルトでシリアライズできない。

**pom.xml**:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>2.18.4</version>
</dependency>
```

**Main.java**:
```java
ObjectMapper objectMapper = new ObjectMapper()
    .registerModule(new JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

Javalin app = Javalin.create(config -> {
    config.jsonMapper(new JavalinJackson(objectMapper, false));
    // ...
});
```

### 5. SPA 側の API 呼び出し

SPA は同一ドメインなので、相対パスで API を叩くだけ:

```js
const BASE = '/api/v1';

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    credentials: 'include',  // Cookie 自動送信
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}
```

**ポイント**:
- `credentials: 'include'` で session cookie が自動送信される
- CORS ヘッダー不要（same-origin）
- CSRF トークン不要（same-origin + SameSite=Lax）

### 6. API レスポンス形式

volta-auth-proxy のリスト API は全て `{"items": [...]}` 形式:

```js
// NG: 配列を期待
const users = await fetch('/api/v1/admin/users').then(r => r.json());
users.length // undefined!

// OK: items を展開
const data = await fetch('/api/v1/admin/users').then(r => r.json());
const users = data.items;
users.length // 1
```

ヘルパー:
```js
function items(path) {
  return request(path).then(d => d.items || d);
}
```

### 7. Cloudflare 利用時の注意

- **開発中のキャッシュ**: CF が壊れたレスポンスをキャッシュすると、修正後も古い内容が返る
  - `cf-cache-status: HIT` + `content-length: 0` → Purge Everything が必要
  - 開発中は Page Rule で `Cache Level: Bypass` を設定するか、頻繁に Purge
- **Vite dev サーバー**: Cloudflare tunnel 経由では動かない（WebSocket/HMR 非対応）
  - 必ず `npm run build` → 静的ファイル配信

### 8. Cookie 重複の罠

Cookie domain を変更した場合（例: `auth.example.com` → `.example.com`）、
ブラウザに古い Cookie と新しい Cookie が両方残る。

```
__volta_session  auth.example.com   (古い、無効)
__volta_session  .example.com       (新しい、有効)
```

ブラウザは古い方を優先送信する場合がある。
**対策**: domain 変更後は古い Cookie を削除するか、ユーザーに Cookie クリアを促す。

## デプロイフロー

```bash
# 1. SPA ビルド
cd volta-auth-console
npm run build

# 2. auth-proxy に配置
cp -r dist/* ../volta-auth-proxy/src/main/resources/public/console/

# 3. auth-proxy リビルド＆再起動
cd ../volta-auth-proxy
set -a && source .env && set +a
mvn clean compile exec:java -q

# 4. Cloudflare キャッシュパージ（必要に応じて）
# Dashboard → Caching → Purge Everything
```

## チェックリスト

- [ ] `vite.config.js` の `base` が `/console/` に設定されている
- [ ] `dist/*` が `src/main/resources/public/console/` にコピーされている
- [ ] Javalin に `/console/` の GET ルートが登録されている
- [ ] `app.after("/console/*")` で SPA fallback が設定されている
- [ ] `jackson-datatype-jsr310` が pom.xml に追加されている
- [ ] `JavaTimeModule` が ObjectMapper に登録されている
- [ ] Javalin の `jsonMapper` に ObjectMapper が設定されている
- [ ] SPA の API 呼び出しが `/api/v1/...` の相対パスを使っている
- [ ] Cloudflare キャッシュがパージされている
