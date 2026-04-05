# Postmortem: volta-auth-console 統合 (2026-04-05)

## 概要

volta-auth-proxy に React 管理コンソール (volta-auth-console) を統合する作業で、
複数のインフラ・ブラウザ・Java の問題に遭遇した。最終的に全て解決し、
`https://auth.unlaxer.org/console/` で管理コンソールが動作するようになった。

## タイムライン

| 時刻 | 問題 | 原因 | 解決 |
|------|------|------|------|
| 17:00 | auth-console を `auth-console.unlaxer.org` で配信しようとした | Vite dev サーバーは Cloudflare tunnel 経由で動かない（WebSocket/HMR 非対応） | `npm run build` → `npx serve dist` で静的配信に切替 |
| 17:10 | `Failed to fetch` — API 呼び出し失敗 | Cookie domain が `auth.unlaxer.org` のみで `auth-console.unlaxer.org` に送られない | Cookie domain を `.unlaxer.org` に変更（COOKIE_DOMAIN env） |
| 17:15 | CORS エラー | volta-auth-proxy に CORS 設定なし | `Access-Control-Allow-Origin` を `.unlaxer.org` サブドメインに対して返すように before フィルタ追加 |
| 17:20 | Cookie domain 変更後もまだ `Failed to fetch` | cross-origin + Cookie + CORS の組み合わせが複雑すぎる | **方針転換**: auth-console を `auth.unlaxer.org/console/` のサブパスで配信。同一ドメインなら Cookie/CORS 問題なし |
| 17:25 | `/console/` が 302 リダイレクトループ | Javalin の staticFiles が `/console/` をディレクトリとして扱い、自身にリダイレクト | `app.get("/console/", ...)` で明示的に index.html を返す GET ルートを追加 |
| 17:30 | 画面真っ白 (assets が 0 bytes) | SPA fallback の `app.get("/console/*", ...)` が assets リクエストも横取り | `app.get` → `app.after` に変更。404 の場合のみ index.html を返す |
| 17:32 | コンパイルエラー `ctx.status() == 404` | Javalin 6 では `ctx.status()` が `HttpStatus` 型、`int` と比較不可 | `ctx.status().getCode() == 404` に修正 |
| 17:35 | 画面真っ白 (JS が `text/plain` で配信) | Cloudflare が以前の 0 bytes レスポンスをキャッシュしていた (`cf-cache-status: HIT`) | Cloudflare ダッシュボードで **Purge Everything** |
| 17:40 | `Unauthorized` — ログイン済みなのに認証失敗 | `__volta_session` Cookie が2つ存在（古い `auth.unlaxer.org` ドメインと新しい `.unlaxer.org` ドメイン）。ブラウザが古い方を優先 | DevTools → Cookies で古い Cookie を手動削除 |
| 17:45 | Dashboard が全て `?` 表示 | API レスポンスが `{"items": [...]}` だがフロントが配列を期待 | `api.js` に `items()` ヘルパー追加、`.items` を展開 |
| 17:50 | `/api/v1/admin/users` が 500 | Jackson が `java.time.Instant` をシリアライズできない（`jackson-datatype-jsr310` 未登録） | pom.xml に `jackson-datatype-jsr310` 追加、`ObjectMapper` に `JavaTimeModule` 登録、Javalin に `jsonMapper` 設定 |
| 17:55 | コンパイルエラー `JavalinJackson` コンストラクタ | Javalin 6 は `JavalinJackson(ObjectMapper, boolean)` シグネチャ | 第2引数 `false` を追加 |
| 18:00 | **全画面動作確認完了** | — | — |

## 根本原因の分類

### 1. Cookie / ドメイン問題
- **別サブドメイン間の Cookie 共有** には `Domain=.parent.org` が必須
- Cookie が複数存在するとブラウザの優先順位で予期しない動作になる
- **教訓**: 管理コンソールは **同一ドメインのサブパス** で配信するのが最もシンプル

### 2. Cloudflare キャッシュ
- 開発中に壊れたレスポンスがキャッシュされ、修正後も古い内容が返る
- `cf-cache-status: HIT` + `content-length: 0` が証拠
- **教訓**: 開発中は Cache-Control ヘッダーを no-cache にするか、頻繁に Purge する

### 3. Javalin SPA ホスティング
- `app.get("/path/*")` は静的ファイルの配信を横取りする
- `app.after` を使えば 404 の場合のみフォールバックできる
- ディレクトリパスはリダイレクトループの原因になる
- **教訓**: SPA fallback は `after` フィルタで 404 判定

### 4. Jackson + Java Time
- Jackson はデフォルトで `java.time.*` をシリアライズできない
- `jackson-datatype-jsr310` + `JavaTimeModule` の登録が必要
- Javalin の `ctx.json()` は内部 Jackson を使うので `jsonMapper` の設定が必要
- **教訓**: API で Java records を返す場合は JSR310 モジュールを最初から入れておく

## 影響範囲

- volta-auth-proxy: Main.java, SqlStore.java, pom.xml に変更
- volta-auth-console: 新規作成 (React SPA)
- Cloudflare: キャッシュパージ2回

## 予防策

1. **SPA をサブパスでホスティングするパターンをテンプレート化**
2. **Jackson JSR310 モジュールをデフォルト依存に追加**
3. **開発中の Cloudflare キャッシュ無効化ルールを設定**
4. **API レスポンス形式のドキュメント整備** (`{"items": [...]}` パターン)
