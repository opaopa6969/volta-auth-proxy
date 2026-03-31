# トークン窃取

[English / 英語](token-theft.md)

---

## これは何？

トークン窃取は、攻撃者が他のユーザーの有効な認証トークン（JWT やセッション Cookie など）を入手することです。盗んだトークンで攻撃者は被害者になりすませます -- API 呼び出し、データアクセス、正規ユーザーとしてのアクション実行が可能です。攻撃者はユーザーのパスワードを必要としません。トークンが身元証明そのものだからです。

---

## なぜ重要？

現代のウェブアプリケーションにおいてトークンは王国の鍵です。盗まれたトークンはさらなる認証なしに即座のアクセスを与えます。盗まれたパスワード（2FA で軽減できる可能性がある）と異なり、盗まれたトークンは認証が既に完了しているためログイン時のセキュリティチェックをすべてバイパスします。被害の大きさはトークンのスコープと寿命に依存します -- 長寿命の管理者トークンは短寿命の読み取り専用トークンよりはるかに危険です。

---

## 簡単な例

トークンが盗まれる一般的な方法：

| 攻撃ベクトル | 仕組み |
|--------------|-------------|
| **XSS（クロスサイトスクリプティング）** | 注入された JavaScript が `document.cookie` や `localStorage` を読み、トークンを攻撃者に送信 |
| **ネットワーク盗聴** | 暗号化されていない HTTP では、同じネットワーク上の誰でも通信中のトークンを読める |
| **ログ露出** | サーバーログ、エラーメッセージ、分析ツールにトークンが誤って記録される |
| **ブラウザ拡張機能** | 悪意のある拡張機能が Cookie やリクエストヘッダを読み取る |
| **リファラー漏洩** | URL 内のトークンが Referer ヘッダ経由でサードパーティサイトに送信される |

---

## volta-auth-proxy での使い方

volta はトークン窃取に対して複数の防御層を適用しています：

**HttpOnly Cookie**：

```java
String cookie = AuthService.SESSION_COOKIE + "=" + sessionId
    + "; Path=/; Max-Age=" + sessionTtlSeconds
    + "; HttpOnly; SameSite=Lax";
if (ctx.req().isSecure()) {
    cookie += "; Secure";
}
```

- **HttpOnly**: JavaScript が `document.cookie` でセッション Cookie を読めない。XSS ベースの Cookie 窃取を完全にブロック
- **Secure**: Cookie は HTTPS でのみ送信（検出時）。ネットワーク盗聴を防止
- **SameSite=Lax**: クロスサイトリクエストで Cookie が送信されない。CSRF やクロスサイトのトークン漏洩の攻撃面を削減

**短い JWT 有効期限**：

JWT は5分で期限切れ（`JWT_TTL_SECONDS=300`）。JWT が盗まれても、使える時間枠は非常に狭い。セッションは長め（デフォルト8時間）だが、サーバー側にあるため取り消し可能。

**サーバー側のセッション取り消し**：

セッションは Cookie だけでなくデータベースに保存されます。窃取が疑われる場合、管理者がセッションを取り消すと Cookie は即座に無効化。volta はユーザーごとの同時セッションも5つに制限し、上限超過時は最も古いものを自動取り消し。

**URL にトークンなし**：

volta の OIDC フローは認可コードフローを使うため、トークンはサーバー間で移動し、ブラウザの URL を通過しません。セッション ID は Cookie にあり（URL パラメータではない）、ブラウザ履歴、リファラーヘッダ、サーバーアクセスログに現れません。

**localStorage にトークンなし**：

volta はセッションに Cookie を使い、localStorage や sessionStorage は使いません。これは意図的です -- `localStorage` はページ上で実行されるあらゆる JavaScript（XSS ペイロードを含む）からアクセスできますが、`HttpOnly` Cookie はアクセスできません。

関連: [replay-attack.md](replay-attack.md), [open-redirect.md](open-redirect.md), [data-leakage-via-cache.md](data-leakage-via-cache.md)
