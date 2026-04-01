# クロスオリジン

[English version](cross-origin.md)

---

## 一言で言うと？

クロスオリジンとは、ある[ドメイン](domain.ja.md)のウェブページが別のドメインの[サーバー](server.ja.md)と通信しようとすることで、[ブラウザ](browser.ja.md)には許可するかどうかの厳格なルールがあります。

---

## 隣のレストランに出前を頼む

レストラン A に座っていますが、食べたい料理は向かいのレストラン B のメニューにあります：

| シナリオ | ウェブでの同等物 |
|---|---|
| レストラン A のウェイターが自分の店のメニューを持ってくる | 同一オリジンリクエスト（デフォルトで許可） |
| レストラン A のウェイターにレストラン B の料理を取りに行かせる | クロスオリジンリクエスト（デフォルトでブロック） |
| レストラン B が「レストラン A のお客さんに出前 OK です」と言う | [CORS](cors.ja.md) ヘッダーがリクエストを許可 |
| レストラン B が何も言わない | ブラウザがレスポンスをブロック |

**オリジン**は3つの要素の組み合わせです：

```
  https://app.example.com:443
  ──┬──   ───────┬───────  ─┬─
  スキーム     ドメイン     ポート

  いずれか1つでも異なれば別オリジン：
  http://app.example.com        ← スキームが違う
  https://api.example.com       ← ドメインが違う
  https://app.example.com:8080  ← ポートが違う
```

---

## なぜ必要なの？

クロスオリジン制限（同一オリジンポリシー）がなければ：

- 悪意あるサイト（`evil.com`）があなたの [Cookie](cookie.ja.md) を使って `your-bank.com` にリクエストできる
- レスポンス（残高、取引履歴）が `evil.com` の JavaScript で読み取れる
- 訪問するすべてのサイトが、あなたがログインしている他のすべてのサイトと密かにやり取りできる
- [セッション](session.ja.md)ハイジャックが簡単にできてしまう

同一オリジンポリシーは、ウェブプラットフォームで最も重要なセキュリティメカニズムの1つです。[CORS](cors.ja.md) は、必要なときにそれを制御しながら緩和する方法です。

---

## volta-auth-proxy でのクロスオリジン

volta はいくつかの方法でクロスオリジンリクエストに対応します：

**volta でクロスオリジンが発生する場所：**

```
  https://app.acme.example.com    （アプリフロントエンド）
           │
           │ JavaScript の fetch():
           │ https://auth.example.com/auth/refresh
           │
           ▼
  別オリジン！（サブドメインが異なる）
  ブラウザがまずプリフライト OPTIONS リクエストを送信
  volta が適切な CORS ヘッダーで応答する必要がある
```

**volta の CORS 設定：**

volta はクロスオリジンリクエストを許可するオリジンを明示的に設定します：

| ヘッダー | volta の値 | 目的 |
|---|---|---|
| `Access-Control-Allow-Origin` | 許可された特定のオリジン | `*` ではない -- 信頼された[ドメイン](domain.ja.md)のみ |
| `Access-Control-Allow-Credentials` | `true` | クロスオリジンで [Cookie](cookie.ja.md) の送信を許可 |
| `Access-Control-Allow-Methods` | `GET, POST, OPTIONS` | 許可される [HTTP](http.ja.md) メソッド |
| `Access-Control-Allow-Headers` | `Content-Type, Authorization` | クライアントが送れる[ヘッダー](header.ja.md) |

**volta が `Access-Control-Allow-Origin: *` を使えない理由：**

オリジンを `*`（全員許可）にすると、ブラウザはリクエストに [Cookie](cookie.ja.md) を付けることを拒否します。volta は `/auth/refresh` でセッション Cookie が必要なので、正確なオリジンを指定する必要があります。

**ForwardAuth による回避：**

[ForwardAuth](forwardauth.ja.md) を使う場合、クロスオリジンは問題になりません：

- [ブラウザ](browser.ja.md)は `app.acme.example.com` と通信（アプリと同一オリジン）
- [リバースプロキシ](reverse-proxy.ja.md)が内部で volta と通信（サーバー間、ブラウザ不関与）
- ブラウザの観点ではクロスオリジンリクエストは発生しない

これが、直接 API 呼び出しに対する ForwardAuth パターンの利点の1つです。

---

## 具体的な例

アプリが volta にクロスオリジンリクエストを行う場合の流れ：

1. ユーザーが `https://app.acme.example.com` にいる（ログイン済み、セッション [Cookie](cookie.ja.md) あり）
2. アプリの JavaScript が [JWT](jwt.ja.md) をリフレッシュする必要がある：
   ```javascript
   fetch('https://auth.example.com/auth/refresh', {
     method: 'POST',
     credentials: 'include'  // ← クロスオリジンで Cookie を送信
   })
   ```
3. [ブラウザ](browser.ja.md)がクロスオリジンを検出（`app.acme.example.com` から `auth.example.com`）
4. ブラウザが**プリフライト** OPTIONS リクエストを送信：
   ```
   OPTIONS /auth/refresh HTTP/1.1
   Host: auth.example.com
   Origin: https://app.acme.example.com
   Access-Control-Request-Method: POST
   ```
5. volta が CORS ヘッダー付きで応答：
   ```
   HTTP/1.1 204 No Content
   Access-Control-Allow-Origin: https://app.acme.example.com
   Access-Control-Allow-Methods: POST
   Access-Control-Allow-Credentials: true
   Access-Control-Max-Age: 3600
   ```
6. ブラウザが確認：オリジンは一致？認証情報は許可？POST は許可？ -- すべて OK
7. ブラウザがセッション Cookie 付きで実際の POST リクエストを送信
8. volta がセッションを検証し、新しい JWT を返す
9. ブラウザが確認：レスポンスの `Access-Control-Allow-Origin` は一致？ -- はい
10. JavaScript が新しい JWT を含むレスポンスを受け取る

ステップ5で volta が正しい CORS ヘッダーを返さなければ、ブラウザはステップ6でリクエストをブロックし、JavaScript にはネットワークエラーが返ります。

---

## さらに学ぶために

- [CORS](cors.ja.md) -- クロスオリジンアクセスを制御するメカニズム
- [ドメイン](domain.ja.md) -- オリジンのドメイン部分を定義するもの
- [Cookie](cookie.ja.md) -- クロスオリジンリクエストと Cookie の関係
- [CSRF](csrf.ja.md) -- クロスオリジンリクエストの挙動を悪用する攻撃
- [SameSite](samesite.ja.md) -- クロスオリジンの Cookie 送信を制限する Cookie 属性
- [ForwardAuth](forwardauth.ja.md) -- クロスオリジン問題を完全に回避するパターン
- [ブラウザ](browser.ja.md) -- クロスオリジン制限を適用する実行者
