# キャッシュによるデータ漏洩

[English / 英語](data-leakage-via-cache.md)

---

## これは何？

キャッシュによるデータ漏洩とは、機密情報 -- ユーザー名、メールアドレス、セッショントークン、個人設定 -- がブラウザキャッシュ、CDN キャッシュ、プロキシキャッシュに保存され、見るべきでない人に後から公開されてしまうことです。データはある特定のユーザー向けでしたが、キャッシュ層がコピーを保持し、別の人に提供してしまいます（あるいは共有マシンでアクセス可能にしてしまいます）。

---

## なぜ重要？

キャッシュベースのデータ漏洩が特に危険なのは、無音であることです。エラーメッセージもログエントリもアラームもありません。間違った人が正しい人のデータをそのまま見てしまいます。実際のシナリオ：

- **共有パソコン**: ユーザー A がログアウトし、ユーザー B が「戻る」を押すとユーザー A のダッシュボードが見える（[browser-back-button-cache.md](browser-back-button-cache.md) 参照）
- **CDN キャッシュ汚染**: CDN が認証済み API レスポンスをキャッシュし、未認証ユーザーに配信
- **企業プロキシキャッシュ**: 企業のプロキシが社員データを含むページをキャッシュし、別の社員に配信
- **ディスクフォレンジック**: ログアウト後もキャッシュされた HTML ファイルがディスクに残り、復元できる

---

## 簡単な例

適切なキャッシュヘッダがないログインダッシュボードのレスポンス：

```http
HTTP/1.1 200 OK
Content-Type: text/html

<h1>ようこそ、alice@example.com さん</h1>
<p>API キー: sk-abc123...</p>
```

ブラウザはこれをディスクキャッシュに保存します。後で同じマシンで：

1. Alice がログアウト
2. 攻撃者が `chrome://cache` を開くか、ディスク上のキャッシュフォルダを閲覧
3. キャッシュされた HTML から Alice の API キーを発見

適切なヘッダがあれば防げます：

```http
HTTP/1.1 200 OK
Content-Type: text/html
Cache-Control: no-store, no-cache, must-revalidate, private
Pragma: no-cache

<h1>ようこそ、alice@example.com さん</h1>
<p>API キー: sk-abc123...</p>
```

これでブラウザはレスポンスをディスクに保存しません。

---

## volta-auth-proxy での使い方

volta は一貫した戦略でキャッシュベースのデータ漏洩を防いでいます：

**1. すべての認証ページで `setNoStore()` を使用：**

ユーザー固有のコンテンツを配信するすべてのエンドポイントが以下を呼びます：

```java
ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
ctx.header("Pragma", "no-cache");
```

ログインページ、コールバック、セッション一覧、招待ページ、ユーザーデータを返すすべての API エンドポイントが対象です。

**2. 短命の JWT（5分）：**

万一 JWT がキャッシュされても、300秒で期限切れです（`JWT_TTL_SECONDS=300`）。キャッシュされたトークンを見つけた攻撃者が使える時間はごくわずかです。

**3. セッション Cookie は HttpOnly：**

セッション Cookie（`__volta_session`）は `HttpOnly; SameSite=Lax` で設定され、HTTPS では `Secure` も付きます。JavaScript からは読めず、クロスサイトリクエストでも送信されないため、キャッシュされたページ内容にCookieが現れる可能性を減らします。

**4. サーバー側のセッション検証：**

ブラウザがキャッシュページを表示しても、ユーザーが何かアクションを起こせばサーバー側のセッションチェックが実行されます。セッションが取り消されていれば（ログアウト済み）、リクエストは失敗しログインにリダイレクトされます。

関連: [browser-back-button-cache.md](browser-back-button-cache.md), [no-store-vs-no-cache.md](no-store-vs-no-cache.md), [token-theft.md](token-theft.md)
