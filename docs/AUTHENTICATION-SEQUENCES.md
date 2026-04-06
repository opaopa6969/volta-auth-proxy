# 認証シーケンス完全ガイド — volta-auth-proxy

> **対象読者**: 認証の仕組みを学びたい人（中学生 OK）
> **前提知識**: ブラウザでウェブサイトを見たことがある
>
> この文書を読むと、ウェブサイトの「ログイン」が裏側でどう動いてるか分かるようになる。

---

## 登場人物

```
👤 User         あなた。ブラウザを操作する人
🌐 Browser      Chrome, Safari など。あなたの代わりに通信する
☁️ Cloudflare   インターネット上のガードマン。世界中にサーバーがある
🚦 Traefik      サーバー内の交通整理係。「このリクエストはどのサービスに送る？」を判断
🔐 Auth-Proxy   認証の番人。「あなたは誰？」を確認する
📦 Console      サービス管理画面 (volta-console)。ログインしないと使えない
🍪 Cookie       ブラウザが保存する小さなメモ。「この人はログイン済み」という証明書
```

### 基本ルール

1. **ブラウザがサーバーに「リクエスト」を送る** → サーバーが「レスポンス」を返す
2. **Cookie はブラウザが自動で送る** — 同じドメインへのリクエストに自動で付く
3. **302 はリダイレクト** — 「こっちに行って」とサーバーが言う。ブラウザが自動で従う
4. **200 は成功** — 「はい、どうぞ」
5. **401 は認証エラー** — 「あなた誰？」
6. **ヘッダー** — リクエスト/レスポンスの「封筒」。本文 (body) の前に書かれる情報

---

## Pattern 1: 初回アクセス（ログインしてない）

> **状況**: あなたが初めて `https://console.unlaxer.org` にアクセスする

```
👤 User: ブラウザのアドレスバーに console.unlaxer.org と入力

🌐 Browser → ☁️ Cloudflare
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET / HTTP/2                                 │
   │ Host: console.unlaxer.org                    │
   │ Cookie: (なし — 初めてだから)                   │
   └─────────────────────────────────────────────┘

   ☁️ Cloudflare: 「console.unlaxer.org ね。トンネル経由で転送」
      ※ Cloudflare Tunnel: 暗号化された専用トンネルでサーバーに届ける

☁️ Cloudflare → 🚦 Traefik
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET / HTTP/1.1                               │
   │ Host: console.unlaxer.org                    │
   │ Cookie: (なし)                                │
   │ X-Forwarded-For: 39.110.107.214  ← あなたのIP │
   │ X-Forwarded-Proto: https                      │
   └─────────────────────────────────────────────┘

   🚦 Traefik: 「console.unlaxer.org のルーターに ForwardAuth が設定されてる。
              まず Auth-Proxy に聞いてみよう」

🚦 Traefik → 🔐 Auth-Proxy  (ForwardAuth)
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /auth/verify HTTP/1.1                    │
   │ X-Forwarded-Host: console.unlaxer.org        │
   │ X-Forwarded-Uri: /                           │
   │ X-Forwarded-Proto: http                      │
   │ Cookie: (なし)                                │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の判断:
      1. Cookie に __volta_session がある？ → ない
      2. → ログインしてない！ログイン画面に飛ばそう
      3. return_to = http://console.unlaxer.org/ を作成

🔐 Auth-Proxy → 🚦 Traefik
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/1.1 302 Found                           │
   │ Location: https://auth.unlaxer.org/login     │
   │   ?return_to=http%3A%2F%2Fconsole.unlaxer.org%2F │
   └─────────────────────────────────────────────┘

   🚦 Traefik: 「Auth-Proxy が 302 を返した。ブラウザにそのまま返す」

🚦 Traefik → ☁️ Cloudflare → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 302 Found                             │
   │ Location: https://auth.unlaxer.org/login     │
   │   ?return_to=http%3A%2F%2Fconsole.unlaxer.org%2F │
   └─────────────────────────────────────────────┘

   🌐 Browser: 「302 だ。Location の URL に自動で移動しよう」

🌐 Browser → ☁️ → 🚦 → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /login?return_to=http%3A%2F%2F            │
   │     console.unlaxer.org%2F HTTP/1.1          │
   │ Host: auth.unlaxer.org                        │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy: 「ログインページを返す」

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 200 OK                                │
   │ Content-Type: text/html                      │
   │                                              │
   │ <html>                                       │
   │   <h1>ログイン</h1>                           │
   │   <button>🔑 パスキーでログイン</button>        │
   │   <a>Google でログイン</a>                     │
   │ </html>                                      │
   └─────────────────────────────────────────────┘

👤 User: ログイン画面が表示された！
```

**ポイント:**
- ブラウザは 302 を受けると、自動で Location の URL に移動する
- `return_to` パラメータで「ログイン後にどこに戻るか」を覚えている
- ForwardAuth は「認証チェックを別のサービスに委託する仕組み」

---

## Pattern 2: Google ログイン

> **状況**: ログイン画面で「Google でログイン」をクリック

```
👤 User: [Google でログイン] をクリック

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /login?start=1&provider=GOOGLE            │
   │     &return_to=http%3A%2F%2Fconsole...       │
   │ Host: auth.unlaxer.org                        │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. ランダムな state 値を生成（CSRF 防止用）
      2. ランダムな nonce 値を生成（トークン偽造防止用）
      3. PKCE の code_verifier と code_challenge を生成
      4. これらを DB に保存（後で検証に使う）
      5. Google の認証 URL を組み立てる

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 302 Found                             │
   │ Location: https://accounts.google.com/       │
   │   o/oauth2/v2/auth                           │
   │   ?client_id=xxxxxxxxx.apps.googleusercontent.com │
   │   &redirect_uri=https://auth.unlaxer.org/callback │
   │   &response_type=code                        │
   │   &scope=openid email profile                │
   │   &state=abc123xyz   ← CSRF 防止             │
   │   &nonce=def456uvw   ← トークン偽造防止       │
   │   &code_challenge=sha256hash  ← PKCE          │
   │   &code_challenge_method=S256                 │
   └─────────────────────────────────────────────┘

   🌐 Browser: 「Google に移動！」

🌐 Browser → 🔵 Google
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /o/oauth2/v2/auth?client_id=xxx...       │
   │ Host: accounts.google.com                     │
   └─────────────────────────────────────────────┘

   🔵 Google: 「アカウントを選択してください」

👤 User: 自分の Google アカウントを選択

   🔵 Google の処理:
      1. ユーザーの同意を確認
      2. 一時的な「認証コード」(code) を発行
      3. redirect_uri にコードを付けてリダイレクト

🔵 Google → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 302 Found                             │
   │ Location: https://auth.unlaxer.org/callback  │
   │   ?code=4/0AfJohXn...   ← 認証コード（1回だけ使える） │
   │   &state=abc123xyz       ← 最初に送った値と同じか検証 │
   └─────────────────────────────────────────────┘

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /callback?code=4/0AfJohXn...             │
   │     &state=abc123xyz                          │
   │ Host: auth.unlaxer.org                        │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理（超重要！）:
      1. state を DB と照合 → 一致するか確認（CSRF 防止）
      2. code を Google のトークンエンドポイントに送る（サーバー間通信）

🔐 Auth-Proxy → 🔵 Google  (サーバー間、ブラウザを介さない)
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /token HTTP/1.1                          │
   │ Host: oauth2.googleapis.com                   │
   │ Content-Type: application/x-www-form-urlencoded │
   │                                               │
   │ code=4/0AfJohXn...                            │
   │ &client_id=xxx                                │
   │ &client_secret=yyy  ← サーバーだけが知る秘密    │
   │ &redirect_uri=https://auth.unlaxer.org/callback │
   │ &grant_type=authorization_code                │
   │ &code_verifier=pkce_verifier  ← PKCE 検証     │
   └─────────────────────────────────────────────┘

🔵 Google → 🔐 Auth-Proxy
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/1.1 200 OK                              │
   │ Content-Type: application/json               │
   │                                              │
   │ {                                            │
   │   "access_token": "ya29.a0AfB...",           │
   │   "id_token": "eyJhbGciOi...",  ← JWT        │
   │   "token_type": "Bearer",                    │
   │   "expires_in": 3600                         │
   │ }                                            │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理（続き）:
      3. id_token (JWT) を検証
         - Google の公開鍵で署名を検証
         - nonce が一致するか確認
         - email, name を取り出す
      4. ユーザーを DB に upsert（なければ作成、あれば更新）
      5. テナント（ワークスペース）を解決
      6. セッション ID を生成 (UUID)
      7. セッションを DB に保存
      8. デバイスチェック（新しいデバイスなら通知メール）

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/2 302 Found                                     │
   │ Location: https://console.unlaxer.org/               │
   │ Set-Cookie: __volta_session=6b2bd453-fecf-40ae...    │
   │   ; Path=/                                           │
   │   ; Domain=.unlaxer.org  ← 全サブドメインで有効      │
   │   ; Max-Age=28800        ← 8時間有効                 │
   │   ; HttpOnly             ← JavaScript からは読めない  │
   │   ; SameSite=Lax         ← 同じサイトからのみ送信     │
   └─────────────────────────────────────────────────────┘

   🌐 Browser の処理:
      1. 🍪 Cookie を保存！ (名前: __volta_session, 値: 6b2bd453...)
      2. Location (console.unlaxer.org) に自動で移動
      3. 次回から .unlaxer.org への全リクエストに Cookie を付ける

👤 User: console.unlaxer.org にログインした状態で表示される！
```

**ポイント:**
- `client_secret` はサーバーだけが知る秘密。ブラウザには絶対に送らない
- PKCE (ピクシー) は「認証コードを横取りされても使えないようにする仕組み」
- Cookie の `HttpOnly` はセキュリティのため。XSS 攻撃で Cookie を盗めない
- Cookie の `Domain=.unlaxer.org` で、`auth.unlaxer.org` でも `console.unlaxer.org` でも同じ Cookie が使える

---

## Pattern 3: Passkey（パスキー）ログイン

> **状況**: ログイン画面で「🔑 パスキーでログイン」をクリック
> **前提**: 事前にパスキーを登録済み

```
👤 User: [🔑 パスキーでログイン] をクリック

🌐 Browser (JavaScript):
   fetch('/auth/passkey/start', {
     method: 'POST',
     headers: {'Accept': 'application/json'}
   })

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /auth/passkey/start HTTP/1.1            │
   │ Host: auth.unlaxer.org                        │
   │ Accept: application/json  ← CSRF スキップ用   │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. ランダムな challenge (32 bytes) を生成
      2. セッションに challenge を保存

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/1.1 200 OK                              │
   │ Content-Type: application/json               │
   │                                              │
   │ {                                            │
   │   "challenge": "DBcGpprn5AP-wSKx...",        │
   │   "rpId": "unlaxer.org",                     │
   │   "userVerification": "preferred",            │
   │   "timeout": 300000                           │
   │ }                                            │
   └─────────────────────────────────────────────┘

   🌐 Browser (JavaScript):
      navigator.credentials.get({
        publicKey: {
          challenge: <上のchallenge>,
          rpId: "unlaxer.org",
          userVerification: "preferred"
        }
      })

   🌐 Browser: 「OS の生体認証を起動！」

👤 User: 指紋 / 顔認証 / PIN を入力
   ※ ここでの認証情報はサーバーに送られない！
   ※ デバイス内で秘密鍵を使って challenge に署名するだけ

🌐 Browser (JavaScript): 署名完了
   {
     id: "ABcd1234...",           ← credential ID
     response: {
       authenticatorData: "...",  ← 認証器の情報
       clientDataJSON: "...",     ← ブラウザが作った検証データ
       signature: "...",          ← 秘密鍵で署名した証明
       userHandle: "8f68b1c7..." ← ユーザー ID (UUID)
     }
   }

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /auth/passkey/finish HTTP/1.1           │
   │ Host: auth.unlaxer.org                        │
   │ Content-Type: application/json               │
   │                                              │
   │ {                                            │
   │   "id": "ABcd1234...",                       │
   │   "response": {                              │
   │     "clientDataJSON": "eyJ0eXBlI...",        │
   │     "authenticatorData": "SZYN5Y...",         │
   │     "signature": "MEUCIQD..."               │
   │   }                                          │
   │ }                                            │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. credential ID で DB からパスキーを検索
      2. 保存してある公開鍵で signature を検証
      3. challenge が一致するか確認
      4. origin (auth.unlaxer.org) が正しいか確認
      5. rpId (unlaxer.org) が正しいか確認
      6. sign_count が増えてるか確認（クローン検知）
      7. ユーザーとテナントを解決
      8. セッションを作成 (mfaVerified = true)
         ※ パスキー = 生体認証 = MFA と同等！
      9. デバイスチェック

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/1.1 200 OK                                     │
   │ Set-Cookie: __volta_session=new-session-uuid...      │
   │   ; Path=/; Domain=.unlaxer.org; Max-Age=28800      │
   │   ; HttpOnly; SameSite=Lax                          │
   │ Content-Type: application/json                      │
   │                                                     │
   │ {                                                   │
   │   "ok": true,                                       │
   │   "redirect_to": "https://console.unlaxer.org/"     │
   │ }                                                   │
   └─────────────────────────────────────────────────────┘

   🌐 Browser (JavaScript):
      location.href = "https://console.unlaxer.org/"

👤 User: パスキーでログインできた！
```

**ポイント:**
- パスキーでは**パスワードが一切使われない**
- 秘密鍵はデバイス内にあり、サーバーには**公開鍵**だけが保存されてる
- 生体認証（指紋/顔）はデバイス内で完結。サーバーに送られない
- `rpId` (Relying Party ID) はドメイン。`unlaxer.org` にパスキーを登録すると、`*.unlaxer.org` の全サブドメインで使える
- パスキーログインは MFA と同等なので、TOTP チャレンジをスキップする

---

## Pattern 4: MFA（二要素認証）チャレンジ

> **状況**: Google ログイン後、TOTP (二要素認証) が有効なユーザー

```
   ※ Pattern 2 の Google ログイン完了後の続き

🔐 Auth-Proxy: セッション作成時:
   session = {
     id: "6b2bd453...",
     userId: "8f68b1c7...",
     mfaVerifiedAt: null  ← まだ MFA を通してない！
   }

   ※ 次に console.unlaxer.org にアクセスすると...

🌐 Browser → ☁️ → 🚦 Traefik → 🔐 Auth-Proxy (/auth/verify)
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /auth/verify HTTP/1.1                    │
   │ X-Forwarded-Host: console.unlaxer.org        │
   │ X-Forwarded-Uri: /                           │
   │ Cookie: __volta_session=6b2bd453...          │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の判断:
      1. Cookie がある → セッションを DB から取得
      2. セッションは有効
      3. しかし mfaVerifiedAt = null → MFA 未完了！
      4. MFA チャレンジに飛ばす

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/2 302 Found                                     │
   │ Location: https://auth.unlaxer.org/mfa/challenge     │
   │   ?return_to=http%3A%2F%2Fconsole.unlaxer.org%2F    │
   └─────────────────────────────────────────────────────┘

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /mfa/challenge?return_to=...             │
   │ Host: auth.unlaxer.org                        │
   │ Cookie: __volta_session=6b2bd453...          │
   └─────────────────────────────────────────────┘

🔐 Auth-Proxy → 🌐 Browser
   レスポンス: MFA 入力フォーム (HTML)
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 200 OK                                │
   │                                              │
   │ <h1>MFA 認証</h1>                            │
   │ <p>TOTP コードを入力してください。</p>         │
   │ <form>                                       │
   │   <input name="code" maxlength="6">          │
   │   <button>確認</button>                      │
   │ </form>                                      │
   └─────────────────────────────────────────────┘

👤 User: 認証アプリ (Google Authenticator 等) に表示された
         6桁のコード (例: 482917) を入力して「確認」

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /auth/mfa/verify HTTP/1.1               │
   │ Host: auth.unlaxer.org                        │
   │ Cookie: __volta_session=6b2bd453...          │
   │ Content-Type: application/json               │
   │                                              │
   │ { "code": 482917 }                           │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. セッションからユーザー ID を取得
      2. DB からユーザーの TOTP 秘密鍵を取得
      3. 秘密鍵 + 現在時刻 → 正しいコードを計算
      4. 入力されたコード (482917) と比較
      5. 一致！ → セッションの mfaVerifiedAt = now()

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/1.1 200 OK                              │
   │ Content-Type: application/json               │
   │                                              │
   │ { "ok": true, "redirect_to": "/select-tenant" } │
   └─────────────────────────────────────────────┘

   🌐 Browser (JavaScript):
      // URL パラメータの return_to を優先
      var returnTo = params.get('return_to');
      location.href = returnTo;  // → console.unlaxer.org

👤 User: MFA 認証完了！console.unlaxer.org が表示される！
```

**ポイント:**
- TOTP (Time-based One-Time Password) は「秘密鍵 + 現在時刻」から計算する 6 桁の数字
- 30 秒ごとに変わる。サーバーも同じ計算をするから一致する
- パスキーでログインした場合は MFA スキップ（パスキー = MFA と同等）

---

## Pattern 5: 認証済みアクセス（通常のページ表示）

> **状況**: ログイン + MFA 完了後に `console.unlaxer.org` にアクセス

```
🌐 Browser → ☁️ Cloudflare → 🚦 Traefik
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET / HTTP/1.1                               │
   │ Host: console.unlaxer.org                    │
   │ Cookie: __volta_session=6b2bd453...          │
   │   ← ブラウザが自動で付ける！                    │
   └─────────────────────────────────────────────┘

   🚦 Traefik: 「ForwardAuth チェック」

🚦 Traefik → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /auth/verify HTTP/1.1                    │
   │ X-Forwarded-Host: console.unlaxer.org        │
   │ X-Forwarded-Uri: /                           │
   │ Cookie: __volta_session=6b2bd453...          │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の判断:
      1. Cookie → セッション取得 → 有効 ✅
      2. mfaVerifiedAt → 設定済み ✅
      3. ユーザー情報をヘッダーに詰める

🔐 Auth-Proxy → 🚦 Traefik
   レスポンス:
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/1.1 200 OK                                     │
   │ X-Volta-User-Id: 8f68b1c7-2ef9-4709...             │
   │ X-Volta-Email: opaopa6969@gmail.com                 │
   │ X-Volta-Roles: OWNER                                │
   │ X-Volta-Display-Name: hisayuki ookubo               │
   │ X-Volta-Tenant-Id: a7b6ab43-737b-4f37...           │
   │ X-Volta-Tenant-Slug: opaopa6969-8f68b1             │
   │ X-Volta-JWT: eyJraWQiOi...（RS256 署名付き JWT）     │
   └─────────────────────────────────────────────────────┘

   🚦 Traefik: 「200 だ！ OK。ヘッダーをリクエストに付けて Console に転送」

🚦 Traefik → 📦 Console (nginx)
   リクエスト:
   ┌─────────────────────────────────────────────────────┐
   │ GET / HTTP/1.1                                      │
   │ Host: console.unlaxer.org                           │
   │ X-Volta-User-Id: 8f68b1c7...                       │
   │ X-Volta-Email: opaopa6969@gmail.com                 │
   │ X-Volta-Roles: OWNER                                │
   │ X-Volta-Display-Name: hisayuki ookubo               │
   │   ← Auth-Proxy が付けたヘッダーが Traefik を経由して届く │
   └─────────────────────────────────────────────────────┘

   📦 Console (nginx): 「/ は SPA の HTML を返す」

📦 Console → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 200 OK                                │
   │ Content-Type: text/html                      │
   │                                              │
   │ <html>                                       │
   │   <div id="root"></div>                      │
   │   <script src="/assets/index-xxx.js"></script> │
   │ </html>                                      │
   └─────────────────────────────────────────────┘

👤 User: ダッシュボードが表示された！
```

**ポイント:**
- Console は X-Volta-* ヘッダーで「誰がアクセスしてるか」を知る
- Console 自身は認証処理を一切しない。全部 Auth-Proxy + Traefik に任せてる
- これが ForwardAuth の最大の利点: アプリが認証コードを持たなくていい

---

## Pattern 6: API リクエスト（SPA からの非同期通信）

> **状況**: ダッシュボードが表示された後、JavaScript がサービス一覧を取得

```
🌐 Browser (JavaScript):
   axios.get('/api/auth/me')
   // ブラウザが自動で Cookie を付ける

🌐 Browser → ☁️ → 🚦 Traefik → ForwardAuth (Pattern 5 と同じ) → 📦 Console (nginx)

📦 Console (nginx):
   location /api/ {
     proxy_pass http://backend:5000/api/;
     proxy_set_header X-Volta-Email $http_x_volta_email;
     proxy_set_header X-Volta-Roles $http_x_volta_roles;
     ...
   }
   ※ nginx が X-Volta-* ヘッダーを backend に転送

📦 Console (nginx) → 📦 Console (backend Node.js)
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /api/auth/me HTTP/1.1                    │
   │ X-Volta-Email: opaopa6969@gmail.com          │
   │ X-Volta-Roles: OWNER                         │
   │ X-Volta-Display-Name: hisayuki ookubo        │
   └─────────────────────────────────────────────┘

   📦 Backend の処理:
      1. X-Volta-Email ヘッダーがある → ForwardAuth 認証済み
      2. OWNER → admin にマッピング
      3. ユーザー情報を返す

📦 Backend → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/1.1 200 OK                              │
   │ Content-Type: application/json               │
   │                                              │
   │ {                                            │
   │   "user": {                                  │
   │     "username": "opaopa6969@gmail.com",      │
   │     "role": "admin",                         │
   │     "displayName": "hisayuki ookubo"         │
   │   }                                          │
   │ }                                            │
   └─────────────────────────────────────────────┘

🌐 Browser (JavaScript): ユーザー情報をストアに保存 → UI を更新
```

**ポイント:**
- SPA (Single Page Application) は最初に HTML を1回だけ取得し、その後は JSON の API 通信だけ
- nginx が X-Volta-* ヘッダーを backend に転送するのが重要（これが抜けてて Bug #2 になった）
- backend は X-Volta-* ヘッダーを「信頼」する。偽造できない理由: Traefik の ForwardAuth が必ず Auth-Proxy を通してヘッダーを設定するから

---

## Pattern 7: ログアウト

> **状況**: ダッシュボードで「Logout」ボタンをクリック

```
👤 User: [Logout] をクリック

🌐 Browser (JavaScript):
   window.location.href = 'https://auth.unlaxer.org/auth/logout';

🌐 Browser → ☁️ → 🚦 → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /auth/logout HTTP/1.1                    │
   │ Host: auth.unlaxer.org                        │
   │ Cookie: __volta_session=6b2bd453...          │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. Cookie からセッション ID を取得
      2. DB のセッションを無効化 (revoke)
      3. Cookie を削除（Max-Age=0 で上書き）

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/2 302 Found                                     │
   │ Location: https://auth.unlaxer.org/login             │
   │ Set-Cookie: __volta_session=;                        │
   │   Path=/; Domain=.unlaxer.org;                       │
   │   Max-Age=0  ← 即座に期限切れ = 削除                  │
   │   ; HttpOnly; SameSite=Lax                           │
   └─────────────────────────────────────────────────────┘

   🌐 Browser:
      1. 🍪 Cookie を削除！（Max-Age=0 だから）
      2. ログイン画面に移動

👤 User: ログアウト完了。ログイン画面に戻った。
```

**ポイント:**
- Cookie の削除は「同じ名前の Cookie を Max-Age=0 で上書き」することで実現
- サーバー側でもセッションを無効化（DB の revoke）。Cookie を消すだけでは不十分
- ログアウト後に console.unlaxer.org にアクセスすると、Pattern 1 に戻る

---

## Pattern 8: パスキー登録

> **状況**: `/settings/security` でパスキーを新規登録

```
👤 User: [+ パスキーを追加] をクリック

🌐 Browser (JavaScript):
   fetch('/api/v1/users/{userId}/passkeys/register/start', {
     method: 'POST',
     credentials: 'include',
     headers: {'Accept': 'application/json'}
   })

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /api/v1/users/.../passkeys/register/start │
   │ Cookie: __volta_session=6b2bd453...          │
   │ Accept: application/json                      │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. セッション確認（ログイン済みか）
      2. ランダムな challenge (32 bytes) を生成
      3. 既存のパスキー一覧を取得（重複防止用）

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ {                                            │
   │   "challenge": "IDPx9fFIGjGN...",            │
   │   "rp": {                                    │
   │     "id": "unlaxer.org",                     │
   │     "name": "volta-auth"                     │
   │   },                                         │
   │   "user": {                                  │
   │     "id": "OGY2OGIx...",  ← Base64(userId)   │
   │     "name": "opaopa6969@gmail.com",          │
   │     "displayName": "hisayuki ookubo"         │
   │   },                                         │
   │   "pubKeyCredParams": [                      │
   │     { "type": "public-key", "alg": -7 },     │  ← ES256
   │     { "type": "public-key", "alg": -257 }    │  ← RS256
   │   ],                                         │
   │   "excludeCredentials": [...]  ← 登録済みキー  │
   │ }                                            │
   └─────────────────────────────────────────────┘

   🌐 Browser (JavaScript):
      navigator.credentials.create({
        publicKey: <上のオプション>
      })

   🌐 Browser: 「OS の生体認証を起動して新しい鍵ペアを作成！」

👤 User: 指紋 / 顔認証 / PIN を入力

   🌐 Browser / OS の処理:
      1. 新しい秘密鍵 + 公開鍵のペアを生成
      2. 秘密鍵をデバイスに安全に保存
         （iCloud Keychain や Google Password Manager に同期される場合もある）
      3. 公開鍵 + attestation（証明書）をサーバーに送る

👤 User: パスキーの名前を入力 (例: "MacBook Pro")

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /api/v1/users/.../passkeys/register/finish │
   │ Cookie: __volta_session=6b2bd453...          │
   │ Content-Type: application/json               │
   │                                              │
   │ {                                            │
   │   "id": "ABcd1234...",                       │
   │   "name": "MacBook Pro",                     │
   │   "response": {                              │
   │     "clientDataJSON": "eyJ0eXBlI...",        │
   │     "attestationObject": "o2Nm..."           │
   │       ← 公開鍵が含まれてる                     │
   │   }                                          │
   │ }                                            │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. challenge が一致するか検証
      2. attestation を webauthn4j で検証
      3. 公開鍵を抽出して DB に保存:
         - credential_id (バイナリ)
         - public_key (バイナリ)
         - sign_count: 0
         - backup_eligible: true/false (synced か device-bound か)
         - name: "MacBook Pro"

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/1.1 201 Created                         │
   │ { "ok": true, "id": "passkey-uuid" }         │
   └─────────────────────────────────────────────┘

👤 User: 「🔑 パスキー「MacBook Pro」を登録しました！」が表示
```

**ポイント:**
- 秘密鍵は**絶対にサーバーに送られない**。サーバーには公開鍵だけ
- 公開鍵 = 「鍵穴」、秘密鍵 = 「鍵」のイメージ。鍵穴だけ渡して、鍵は自分で持つ
- `backup_eligible` フラグで、パスキーが iCloud/Google に同期されてるか分かる

---

## Pattern 9: 招待受諾

> **状況**: メールで招待リンクを受け取り、クリック

```
👤 User: メールの招待リンクをクリック
   https://auth.unlaxer.org/invite/abc123def

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /invite/abc123def HTTP/1.1               │
   │ Host: auth.unlaxer.org                        │
   │ Cookie: __volta_session=6b2bd453...          │
   │   ← ログイン済みなら Cookie がある             │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. 招待コード abc123def を DB で検索
      2. 招待は有効か（期限切れ？使用済み？）
      3. Cookie → ログイン状態を確認

🔐 Auth-Proxy → 🌐 Browser
   レスポンス (ログイン済みの場合):
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 200 OK                                │
   │                                              │
   │ <h1>ワークスペース招待</h1>                    │
   │ <p>hisayuki さんが MyWorkspace に招待してます</p>│
   │ <p>ロール: MEMBER</p>                         │
   │ <form method="post" action="/invite/abc123def/accept"> │
   │   <input type="hidden" name="_csrf" value="token123"> │
   │   <button>参加する</button>                   │
   │ </form>                                      │
   └─────────────────────────────────────────────┘

👤 User: [参加する] をクリック

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /invite/abc123def/accept HTTP/1.1       │
   │ Host: auth.unlaxer.org                        │
   │ Cookie: __volta_session=6b2bd453...          │
   │ Content-Type: application/x-www-form-urlencoded │
   │                                              │
   │ _csrf=token123                               │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. CSRF トークンを検証（偽造リクエスト防止）
      2. 招待の email とログイン中の email が一致するか確認
      3. 一致 → membership を DB に作成
      4. 新しいセッションを発行（テナント切り替え）

🔐 Auth-Proxy → 🌐 Browser
   レスポンス:
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 200 OK                                │
   │ Set-Cookie: __volta_session=new-session...   │
   │                                              │
   │ <h1>🎉 MyWorkspace に参加しました</h1>        │
   │ <p>ロール: MEMBER</p>                         │
   │ <a href="/console/">ダッシュボードへ</a>       │
   │ <a href="/settings/security">パスキー設定</a> │
   └─────────────────────────────────────────────┘

👤 User: 参加完了！
```

---

## Pattern 10: 招待 email mismatch → アカウント切り替え

> **状況**: alice@example.com でログイン中だが、bob@example.com 宛の招待を開いた

```
👤 User: 招待リンクをクリック → [参加する] をクリック

🌐 Browser → 🔐 Auth-Proxy
   POST /invite/abc123def/accept
   Cookie: __volta_session=... (alice のセッション)

   🔐 Auth-Proxy の判断:
      1. 招待の email: bob@example.com
      2. ログイン中の email: alice@example.com
      3. 不一致！ → アカウント切り替え画面を表示

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 200 OK                                │
   │                                              │
   │ <div class="alert">                          │
   │   現在 alice@example.com でログイン中ですが、   │
   │   この招待は別のアカウント宛です。              │
   │ </div>                                       │
   │ <form method="post" action="/auth/switch-account"> │
   │   <input type="hidden" name="_csrf" value="..."> │
   │   <input type="hidden" name="return_to"       │
   │     value="/invite/abc123def">                │
   │   <button>アカウントを切り替える</button>       │
   │ </form>                                      │
   └─────────────────────────────────────────────┘

   ※ なぜ GET パラメータ (?switch=1) ではなく POST フォームか？
   → <img src="/login?switch=1"> で強制ログアウト攻撃が可能だから！
   → POST + CSRF トークンなら攻撃者が勝手にリクエストを送れない

👤 User: [アカウントを切り替える] をクリック

🌐 Browser → 🔐 Auth-Proxy
   POST /auth/switch-account
   _csrf=token & return_to=/invite/abc123def

   🔐 Auth-Proxy の処理:
      1. CSRF 検証
      2. return_to を検証（/invite/ で始まるか）
      3. 現在のセッションを無効化
      4. Cookie を削除

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/2 302 Found                                     │
   │ Location: https://auth.unlaxer.org/login             │
   │   ?return_to=/invite/abc123def                       │
   │ Set-Cookie: __volta_session=; Max-Age=0              │
   └─────────────────────────────────────────────────────┘

   🌐 Browser: Cookie 削除 → ログイン画面へ

👤 User: bob@example.com でログイン → 招待受諾
```

---

## Pattern 11: 新デバイス検知

> **状況**: いつもは Chrome on Windows でログインしてるが、今日は Safari on macOS でログイン

```
   ※ Google ログインまたはパスキーログインの最後のステップで:

🔐 Auth-Proxy の処理 (checkDeviceAndNotify):
   1. User-Agent から browser + OS を判定:
      "Safari/macOS" (fingerprint)
   2. known_devices テーブルを検索:
      SELECT * FROM known_devices
      WHERE user_id = '8f68b1c7...' AND fingerprint = 'Safari/macOS'
   3. 見つからない → 新しいデバイス！
   4. known_devices に登録:
      INSERT INTO known_devices (user_id, fingerprint, label, last_ip)
      VALUES ('8f68b1c7...', 'Safari/macOS', 'Safari on macOS', '39.110.107.214')
   5. 既存デバイスが 1 つ以上ある → 通知メール送信
      (初回ログインなら通知しない — 全デバイスが「新しい」から)
   6. outbox_events テーブルに通知を保存:
      INSERT INTO outbox_events (event_type, payload)
      VALUES ('notification.new_device', '{
        "to": "opaopa6969@gmail.com",
        "displayName": "hisayuki ookubo",
        "device": "Safari on macOS",
        "ip": "39.110.107.214",
        "timestamp": "2026-04-06T11:20:00Z"
      }')

   ※ メール送信は同期ではない！outbox に入れるだけ
   ※ OutboxWorker が 15 秒ごとに outbox を見て SMTP で送信

📧 OutboxWorker → SMTP サーバー
   メール:
   ┌─────────────────────────────────────────────┐
   │ 件名: [unlaxer.org] 新しいデバイスからのログイン │
   │                                              │
   │ hisayuki ookubo さん、                        │
   │                                              │
   │ 新しいデバイスからのログインがありました。       │
   │                                              │
   │   デバイス: Safari on macOS                   │
   │   IP: 39.110.107.214                         │
   │   日時: 2026-04-06 20:20 (JST)               │
   │                                              │
   │ 心当たりがない場合は、以下からセッションを確認:  │
   │   https://auth.unlaxer.org/settings/sessions │
   └─────────────────────────────────────────────┘

👤 User: メールが届く（心当たりがあるのでOK）
```

**ポイント:**
- デバイスの識別は「ブラウザの種類 + OS の種類」で判定（バージョンは無視）
- IP アドレスは判定に使わない（VPN やモバイルで頻繁に変わるから）
- メール送信は outbox パターン。SMTP が一時的にダウンしても自動リトライ

---

## Pattern 12: Magic Link（メールでログイン）

> **状況**: Google も Passkey も使えない環境でログインする

```
👤 User: メールアドレスを入力して「ログインリンクを送信」

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ POST /auth/magic-link/send HTTP/1.1          │
   │ Content-Type: application/json               │
   │                                              │
   │ { "email": "opaopa6969@gmail.com" }          │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. ランダムな token (48 bytes, URL-safe) を生成
      2. magic_links テーブルに保存:
         token, email, expires_at = now() + 10分
      3. outbox に通知を追加

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────┐
   │ { "ok": true, "message": "Login link sent" } │
   └─────────────────────────────────────────────┘

📧 OutboxWorker → ユーザーのメール
   ┌─────────────────────────────────────────────┐
   │ 以下のリンクをクリックしてログイン:             │
   │                                              │
   │ https://auth.unlaxer.org/auth/magic-link/    │
   │   verify?token=xY7kL9mN...                   │
   │                                              │
   │ このリンクは 10 分間有効です。                  │
   └─────────────────────────────────────────────┘

👤 User: メールのリンクをクリック

🌐 Browser → 🔐 Auth-Proxy
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /auth/magic-link/verify?token=xY7kL9mN... │
   │ Host: auth.unlaxer.org                        │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の処理:
      1. token で magic_links テーブルを検索
      2. 有効期限内か？ → OK
      3. 使用済みか？ → まだ使ってない
      4. used_at = now() に更新（1回だけ使える）
      5. email でユーザーを検索（なければ作成）
      6. テナント解決
      7. セッション作成 + Cookie 発行

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/2 302 Found                                     │
   │ Location: /console/                                  │
   │ Set-Cookie: __volta_session=new-session...           │
   │   ; Path=/; Domain=.unlaxer.org; Max-Age=28800      │
   │   ; HttpOnly; SameSite=Lax                          │
   └─────────────────────────────────────────────────────┘

👤 User: ログインできた！
```

**ポイント:**
- Magic Link はパスワードもパスキーも不要。メールアドレスだけ
- セキュリティは「メールを読めるのは本人だけ」という前提に依存
- token は 1 回使い切り + 10 分の有効期限。漏洩リスクを最小化

---

## Pattern 13: セッション期限切れ

> **状況**: 8時間前にログインして放置。Cookie は残っているがセッションが期限切れ

```
🌐 Browser → ☁️ → 🚦 Traefik → 🔐 Auth-Proxy (/auth/verify)
   リクエスト:
   ┌─────────────────────────────────────────────┐
   │ GET /auth/verify HTTP/1.1                    │
   │ Cookie: __volta_session=old-session-uuid...  │
   └─────────────────────────────────────────────┘

   🔐 Auth-Proxy の判断:
      1. Cookie がある → セッション ID を取得
      2. DB でセッションを検索
      3. expires_at < now() → 期限切れ！
      4. → ログイン画面にリダイレクト

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/2 302 Found                                     │
   │ Location: https://auth.unlaxer.org/login             │
   │   ?return_to=http%3A%2F%2Fconsole.unlaxer.org%2F    │
   └─────────────────────────────────────────────────────┘

   🌐 Browser: ログイン画面に移動

👤 User: もう一度ログインする（Pattern 2 or 3 に戻る）
```

**ポイント:**
- Cookie の Max-Age とセッションの有効期限は同じ (28800 秒 = 8 時間)
- Cookie が残っていても、サーバー側のセッションが期限切れなら認証失敗
- これが「ステートフルセッション」の利点: サーバーがセッションを強制無効化できる

---

## Pattern 14: テナント選択（マルチテナント）

> **状況**: ユーザーが 2 つ以上のワークスペースに所属していて、ログイン後にどちらを使うか選ぶ

```
   ※ OIDC ログイン完了後、テナントが複数ある場合

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────┐
   │ HTTP/2 302 Found                             │
   │ Location: /select-tenant                     │
   │ Set-Cookie: __volta_session=temp-session...  │
   └─────────────────────────────────────────────┘

🌐 Browser → 🔐 Auth-Proxy
   GET /select-tenant

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────┐
   │ <h1>ワークスペースを選択</h1>                 │
   │                                              │
   │ <button data-tenant-id="tenant-1">           │
   │   My Company (role: OWNER) [前回]             │
   │ </button>                                    │
   │                                              │
   │ <button data-tenant-id="tenant-2">           │
   │   Side Project (role: MEMBER)                │
   │ </button>                                    │
   └─────────────────────────────────────────────┘

👤 User: [My Company] をクリック

🌐 Browser (JavaScript) → 🔐 Auth-Proxy
   POST /api/v1/tenants/tenant-1/switch
   Cookie: __volta_session=temp-session...

   🔐 Auth-Proxy の処理:
      1. ユーザーが tenant-1 のメンバーか確認
      2. セッションのテナント情報を更新
      3. 新しいセッションを発行

🔐 Auth-Proxy → 🌐 Browser
   ┌─────────────────────────────────────────────────────┐
   │ HTTP/1.1 200 OK                                     │
   │ Set-Cookie: __volta_session=new-session-with-tenant  │
   │                                                     │
   │ { "ok": true, "tenantId": "tenant-1" }              │
   └─────────────────────────────────────────────────────┘

   🌐 Browser (JavaScript):
      location.href = return_to || '/console/';

👤 User: 選んだワークスペースでダッシュボードが表示
```

**ポイント:**
- マルチテナント = 1 人のユーザーが複数のワークスペースに所属できる
- テナント選択後のセッションにはテナント ID が含まれる
- ForwardAuth の X-Volta-Tenant-Id ヘッダーで、どのテナントのデータにアクセスしてるか判別

---

## 全体のフロー図

```
                    ┌─────────────────────────────┐
                    │         👤 User              │
                    │     (ブラウザ操作)            │
                    └──────────┬──────────────────┘
                               │
                    ┌──────────▼──────────────────┐
                    │        🌐 Browser            │
                    │  Cookie: __volta_session     │
                    │  JavaScript: fetch API       │
                    └──────────┬──────────────────┘
                               │ HTTPS
                    ┌──────────▼──────────────────┐
                    │     ☁️ Cloudflare             │
                    │  DDoS 防御 + CF Tunnel        │
                    └──────────┬──────────────────┘
                               │ HTTP (トンネル内)
                    ┌──────────▼──────────────────┐
                    │      🚦 Traefik              │
                    │  ルーティング + ForwardAuth    │
                    │                              │
                    │  1. /auth/verify に問い合わせ  │
                    │  2. 200 → X-Volta-* 付けて転送 │
                    │  3. 302 → ブラウザにリダイレクト │
                    └──────┬──────────┬────────────┘
                           │          │
              ┌────────────▼──┐  ┌────▼──────────────┐
              │  🔐 Auth-Proxy │  │  📦 Console        │
              │               │  │  (nginx + backend) │
              │ ログイン画面   │  │                    │
              │ Google OAuth  │  │ X-Volta-* ヘッダー  │
              │ Passkey       │  │ で認証済みユーザーを │
              │ MFA           │  │ 識別                │
              │ セッション管理 │  │                    │
              │ 招待           │  │ 自身は認証処理を    │
              │ Magic Link    │  │ 一切しない          │
              └───────────────┘  └────────────────────┘
```

---

## 用語集

| 用語 | 説明 |
|------|------|
| **Cookie** | ブラウザが保存する小さなデータ。サーバーが「覚えておいて」と渡す |
| **Session** | サーバー側で管理する「ログイン状態」。Cookie の値で紐づく |
| **JWT** | JSON Web Token。署名付きの情報カード。改ざんできない |
| **OAuth2** | 「Google に聞いてこの人が誰か教えて」という仕組み |
| **OIDC** | OAuth2 の拡張。「この人のメールと名前も教えて」 |
| **PKCE** | OAuth2 のセキュリティ強化。認証コードの横取りを防ぐ |
| **TOTP** | 時間ベースのワンタイムパスワード。30 秒ごとに変わる 6 桁 |
| **MFA** | 多要素認証。「知ってること + 持ってるもの」の 2 つで確認 |
| **Passkey** | パスワード不要の認証。デバイスの生体認証で秘密鍵を使う |
| **WebAuthn** | Passkey の技術規格。W3C が標準化 |
| **ForwardAuth** | リバースプロキシが「この人通していい？」を別サービスに聞く仕組み |
| **CSRF** | Cross-Site Request Forgery。偽のリクエストを送りつける攻撃 |
| **XSS** | Cross-Site Scripting。悪意のある JavaScript を実行させる攻撃 |
| **302 Redirect** | 「こっちに行って」。ブラウザが自動で従う |
| **SPA** | Single Page Application。最初に 1 回だけ HTML を取得し、以降は API 通信 |
| **outbox パターン** | メール等の非同期処理を DB に保存して、別プロセスが実行する仕組み |
