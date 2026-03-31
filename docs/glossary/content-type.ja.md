# Content-Type

[English version](content-type.md)

---

## これは何？

`Content-Type` は、送信されるデータの種類を受信者に伝える HTTP ヘッダーです。ブラウザがフォームを送信する時は「これはフォームデータです」と言い、API がレスポンスを返す時は「これは JSON です」と言います。これがないと、受信者はバイト列の意味を推測しなければなりません。

一般的な Content-Type：

| Content-Type | 内容 | 用途 |
|-------------|------|------|
| `application/json` | JSON データ | API のリクエストとレスポンス |
| `text/html` | HTML ページ | ブラウザページ |
| `application/x-www-form-urlencoded` | フォームデータ（key=value&key=value） | HTML フォーム送信 |
| `multipart/form-data` | 混合データ（テキスト + ファイル） | ファイルアップロード |

---

## なぜ重要？

Content-Type は**機能面**と**セキュリティ面**の両方で重要です。

**機能面：** サーバーはリクエストボディの解析方法を知る必要があります。JSON ボディには `JSON.parse()`、フォームボディには URL デコード、マルチパートボディには境界パースが必要です。Content-Type が間違っていると、パースが壊れます。

**セキュリティ面：** Content-Type はブラウザとサーバーのリクエスト処理方法に影響します：
- CSRF 攻撃は通常 `application/x-www-form-urlencoded` を使います（ブラウザが HTML フォームからこれを送信するため）
- 変更エンドポイントに `application/json` を要求すると、多くの CSRF 攻撃をブロックできます（ブラウザは通常の HTML フォームから JSON を送れないため）
- `Accept` ヘッダー（Content-Type の関連）は、サーバーが JSON と HTML のどちらを返すか判断するのに役立ちます

---

## 簡単な例

```
# API に JSON を送信
POST /api/v1/tenants/abc/invitations
Content-Type: application/json

{"email": "alice@example.com", "role": "MEMBER"}

# 従来の HTML フォーム送信
POST /login
Content-Type: application/x-www-form-urlencoded

username=alice&password=secret&_csrf=token123
```

---

## volta-auth-proxy では

volta は `isJsonOrXhr()` 関数を通じて、セキュリティ上重要な方法で Content-Type を使用しています：

```java
private static boolean isJsonOrXhr(Context ctx) {
    String accept = ctx.header("Accept");
    String contentType = ctx.header("Content-Type");
    String xrw = ctx.header("X-Requested-With");
    return (accept != null && accept.toLowerCase().contains("application/json"))
            || (contentType != null && contentType.toLowerCase().contains("application/json"))
            || "XMLHttpRequest".equalsIgnoreCase(xrw);
}
```

この関数は **CSRF 対策**に使われます：JSON でも XHR でもない POST/DELETE/PATCH リクエストは CSRF トークンを含む必要があります。これが機能する理由：
- HTML フォームは `application/x-www-form-urlencoded` か `multipart/form-data` しか送れない
- HTML フォームは `Accept: application/json` のようなカスタムヘッダーを設定できない
- つまり `Content-Type: application/json` のリクエストは JavaScript から来たもの（CORS の対象）であり、クロスサイトフォームからではない

volta は `Accept` ヘッダーも使い、`wantsJson()` でレスポンス形式を決定します。クライアントが `Accept: application/json` を送ると、volta は JSON エラーを返します。そうでなければ HTML エラーページをレンダリングします。これにより、同じエンドポイントがブラウザと API クライアントの両方をスムーズに処理できます。

---

## 関連項目

- [bearer-scheme.md](bearer-scheme.md) -- 認証に使われる別の HTTP ヘッダー
- [idempotency.md](idempotency.md) -- 異なる Content-Type の POST リクエストに注意が必要な理由
