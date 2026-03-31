# private と public の違い（Cache-Control）

[English / 英語](private-vs-public.md)

---

## これは何？

`private` と `public` は Cache-Control のディレクティブで、「誰がキャッシュしていいか」を制御します。`public` はチェーン上のどのキャッシュでも保存可能 -- ユーザーのブラウザ、Cloudflare のような CDN、企業のプロキシ、何でも OK です。`private` はエンドユーザーのブラウザだけが保存可能 -- 共有キャッシュは保存してはいけないという意味です。

---

## なぜ重要？

レスポンスがサーバーからブラウザに届くまでに、CDN のエッジノード、リバースプロキシ、企業のファイアウォールなど複数の中継者を通過することがあります。それぞれがレスポンスをキャッシュする可能性があります。レスポンスにユーザー固有のデータ（「こんにちは、Alice さん」やダッシュボードの個人設定）が含まれていたら、共有の中継者がキャッシュしてはいけません。さもないと、Bob が CDN から Alice のキャッシュページを受け取るかもしれません。

`private` ディレクティブは、すべての共有キャッシュに「保存するな」と伝えます。ユーザー自身のブラウザだけが保存できます。

---

## 簡単な例

```
                          CDN         企業プロキシ          ブラウザ
                         (共有)        (共有)            (プライベート)

public, max-age=3600      保存する       保存する          保存する
private, max-age=3600     スキップ       スキップ          保存する
no-store                  スキップ       スキップ          スキップ
```

**public** を使う場面: CSS ファイル、JavaScript バンドル、マーケティングページ、画像 -- すべてのユーザーに同じ内容のもの。

**private** を使う場面: ユーザーダッシュボード、アカウント設定、個人データを含む API レスポンス -- ユーザー固有のもの。

**no-store** を使う場面: ログインページ、認証コールバック、トークンや認証情報を含むもの。

---

## volta-auth-proxy での使い方

volta は全訪問者に同一の静的アセットには `public` を使います：

```java
ctx.header("Cache-Control", "public, max-age=60, stale-while-revalidate=86400");
```

すべての認証エンドポイントとユーザー固有のページには `private` を `no-store` と組み合わせます：

```java
ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
```

ここでの `private` は追加のセーフティネットです。`no-store` だけでも既にキャッシュを防ぎますが、`private` を加えることで、共有キャッシュが `no-store` を誤解した場合でも、少なくともこのレスポンスがユーザー間で共有されるべきでないと伝わります。

この2段階のアプローチで、volta はパフォーマンス（キャッシュされた静的アセット）とセキュリティ（キャッシュされない認証データ）を両立させています。

関連: [cache-control.md](cache-control.md), [data-leakage-via-cache.md](data-leakage-via-cache.md)
