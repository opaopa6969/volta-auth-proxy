# PKCE (Proof Key for Code Exchange)

[English version](pkce.md)

---

## これは何？

PKCE（「ピクシー」と読む）は、OAuth 2.0の認可コードフローに対するセキュリティ拡張です。認可コードが使われる前に誰かに横取りされる特定の攻撃を防ぎます。フロー開始前にクライアント側で秘密を作り、コードをトークンに交換する際にその秘密を知っていることを証明する仕組みです。

封印した封筒を自分宛てに郵送するようなものです。ログインプロセスを始める前に、秘密の言葉を作って封筒に入れ封印します。ログインが完了して認可コードを受け取ったら、封筒を開けて秘密の言葉を見せ、プロセスを開始した本人であることを証明します。途中でコードを横取りした人は封筒を開けられないので、コードは使い物になりません。

---

## なぜ重要なのか？

### PKCEが解決する問題

標準的なOAuth 2.0認可コードフローでは、以下のことが起きます：

```
  1. あなたのアプリがユーザーをGoogleにリダイレクト
  2. Googleがユーザーを認証
  3. GoogleがCODEをURLに含めてあなたのアプリにリダイレクトバック

     http://localhost:7070/callback?code=認可コード&state=...

  4. あなたのアプリがCODEをトークンに交換（サーバー間通信）
```

脆弱性はステップ3にあります。認可コードはURL内のユーザーのブラウザを経由します。モバイルデバイスやSPA（シングルページアプリケーション）では、このコードが以下によって傍受される可能性があります：

- **同じデバイス上の悪意あるアプリ**（同じリダイレクトURIスキームに登録している）
- **ブラウザ拡張機能**（URLパラメータを読める）
- **ブラウザ履歴**（完全なURLを記録）
- **ネットワーク中間者**（HTTPSでほとんど防げますが）

PKCEがなければ、攻撃者がコードを手に入れるとトークンに交換してユーザーになりすませます。コードは持参人払い小切手のようなもので、提示した人がトークンを受け取れます。

### PKCEがないと何が壊れるか？

**モバイルアプリ**の場合、PKCEなしのOAuthはIETF（標準化団体）によって安全でないとされています。デバイス上の複数のアプリが同じカスタムURLスキーム（例：`myapp://callback`）のハンドラとして登録できます。OSが間違ったアプリにリダイレクトを配信する可能性があります。

**SPA**（シングルページアプリケーション）の場合、client_secretがありません。アプリはすべてブラウザ内で動作するため、どんな「秘密」もソースコードで見えてしまいます。PKCEがなければ、コード交換を保護するのはclient_secretだけですが、パブリッククライアントにはそれが存在しません。

**サーバーサイドアプリ**（voltaのような）の場合、client_secretが保護を提供するためPKCEは厳密には必要ありません。しかしvoltaは多層防御として使用しています。もしclient_secretが何らかの方法で漏洩しても、PKCEがフローを保護し続けます。

---

## どう動くのか？

### ステップバイステップ

PKCEはフローに2つの新しいパラメータを追加します：**code_verifier**と**code_challenge**です。

```
  ステップ1：ログイン開始前
  ══════════════════════════

  アプリが「code_verifier」というランダム文字列を生成
  （43-128文字、URLセーフなランダムバイト）

    code_verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

  次に、verifierをハッシュして「code_challenge」を作成：

    code_challenge = BASE64URL(SHA256(code_verifier))
                   = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

  challengeは一方向ハッシュ：verifier -> challengeはできるが、
  challenge -> verifierはできない。


  ステップ2：ログイン開始（認可リクエスト）
  ══════════════════════════════════════════

  アプリがcode_challengeと一緒にGoogleにリダイレクト：

    GET https://accounts.google.com/o/oauth2/v2/auth
      ?response_type=code
      &client_id=YOUR_CLIENT_ID
      &redirect_uri=http://localhost:7070/callback
      &scope=openid email profile
      &state=ランダムな値
      &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
      &code_challenge_method=S256

  Googleが認可セッションと一緒にcode_challengeを保存。


  ステップ3：ユーザーがGoogleで認証
  ══════════════════════════════════

  （通常と同じ -- ユーザーがアカウントを選択、承認）


  ステップ4：Googleがコードと一緒にリダイレクトバック
  ══════════════════════════════════════════════════

    http://localhost:7070/callback?code=認可コード&state=ランダムな値

    >>> 危険ゾーン：コードがURLに含まれている。傍受される可能性あり。<<<


  ステップ5：コードをトークンに交換
  ══════════════════════════════════

  アプリがコードと元のcode_verifierを送信：

    POST https://oauth2.googleapis.com/token
      code=認可コード
      &client_id=YOUR_CLIENT_ID
      &client_secret=YOUR_CLIENT_SECRET
      &redirect_uri=http://localhost:7070/callback
      &grant_type=authorization_code
      &code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk

  Google側：
    1. 先ほど保存したcode_challengeを検索
    2. SHA256(code_verifier)を計算して新しいchallengeを得る
    3. 比較：新しいchallengeが保存されたものと一致するか？
    4. 一致 -> トークンを発行
       不一致 -> 拒否（verifierを持たない誰かがコードを横取りした）
```

### なぜ攻撃者は勝てないか

```
  攻撃者が持っているもの：              攻撃者に必要なもの：
  ┌──────────────────────────┐          ┌──────────────────────────┐
  │ - 認可コード              │          │ - 認可コード              │
  │   (URLから傍受)           │          │   (持っている) ✓           │
  │                          │          │ - code_verifier           │
  │                          │          │   (持っていない)           │
  └──────────────────────────┘          └──────────────────────────┘

  code_verifierはアプリの外に出ない。
  アプリのメモリ内で生成された。
  Googleのトークンエンドポイントに直接送られる（サーバー間通信）。
  攻撃者が見たのはcode_challenge（ハッシュ値）だけで、
  SHA-256は一方向関数 -- 逆算できない。
```

### S256 vs plain

PKCEはcode_challengeを作る2つの方法をサポートしています：

| 方法 | 動作 | セキュリティ |
|------|------|------------|
| **S256** | `challenge = BASE64URL(SHA256(verifier))` | 安全。challengeからverifierを逆算できない。 |
| **plain** | `challenge = verifier` | 危険。challengeがverifierそのもの。認可リクエスト（ステップ2）が見えればverifierが分かる。 |

**常にS256を使ってください。** `plain`はSHA-256を計算できないシステムとの後方互換性のために存在しますが、そんなシステムは極めてまれです。voltaはS256のみを使用します。

---

## volta-auth-proxyではどう使われているか？

voltaは`OidcService.java`と`SecurityUtils.java`でPKCEを実装しています。

### Code verifierの生成

```java
// SecurityUtils.java
String verifier = SecurityUtils.randomUrlSafe(32);
// 32バイトの暗号学的に安全なランダムバイトをBase64URLエンコード
// 結果：43文字のURLセーフなランダムテキスト
```

### Code challengeの作成

```java
// SecurityUtils.java
public static String pkceChallenge(String verifier) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
}
```

### 保存と取得

voltaはcode_verifierをstateやnonceと一緒に`oidc_flows`データベーステーブルに保存します：

```java
// OidcService.java - createAuthorizationUrl()
store.saveOidcFlow(new OidcFlowRecord(
    state,       // CSRF防止
    nonce,       // リプレイ防止
    verifier,    // PKCE防止
    returnTo,    // ログイン後のリダイレクト先
    inviteCode,  // 招待コード（招待経由の参加時）
    expiresAt    // 10分間の有効期限
));
```

Googleがリダイレクトバックすると、voltaはverifierを取得してGoogleのトークンエンドポイントに送ります：

```java
// OidcService.java - exchangeCode()
String body = "code=" + enc(code)
    + "&client_id=" + enc(config.googleClientId())
    + "&client_secret=" + enc(config.googleClientSecret())
    + "&redirect_uri=" + enc(config.googleRedirectUri())
    + "&grant_type=authorization_code"
    + "&code_verifier=" + enc(codeVerifier);  // <-- PKCE verifier
```

### なぜvoltaはclient_secretがあるのにPKCEを使うのか

voltaはサーバーサイドアプリケーションです。コード交換時に保護を提供する`client_secret`があります。ではなぜPKCEも使うのか？

1. **多層防御：** もしclient_secretが漏洩しても（環境変数の設定ミス、ログへの露出など）、PKCEがフローを保護し続けます。
2. **ベストプラクティス：** GoogleはパブリッククライアントだけでなくすべてのOAuthクライアントにPKCEを推奨しています。
3. **将来への備え：** voltaが将来モバイルクライアントやSPAを直接サポートする場合、PKCEは既に組み込まれています。
4. **コストゼロ：** PKCEが追加するのはSHA-256の計算1回とパラメータ1つ。パフォーマンスへの影響はゼロです。

---

## よくある間違いと攻撃

### 間違い1：S256の代わりにplainを使う

`plain`ではcode_challengeがcode_verifierと同じです。攻撃者が認可リクエスト（ステップ2）を見られれば、コード交換に必要なものがすべて手に入ります。常にS256を使ってください。

### 間違い2：code_verifierの再利用

code_verifierは認可リクエストごとにユニークでなければなりません。再利用すると、前のフローからverifierを学んだ攻撃者が次のフローで使えます。

### 間違い3：code_verifierを予測可能な場所に保存する

モバイルでは、code_verifierを共有設定やその他のアプリからアクセス可能な場所に保存しないでください。フローの間だけメモリに保持してください。

### 攻撃：認可コードの傍受（PKCEが防ぐもの）

Androidでは、悪意あるアプリがリダイレクトURIと一致するカスタムURLスキームを登録できます。Googleがリダイレクトバックする際：

```
  Googleがリダイレクト: myapp://callback?code=認可コード

  Androidが問い合わせ：「myapp://をどのアプリで処理しますか？」

  悪意あるアプリもmyapp://を登録していた場合、
  OSが本物のアプリではなく悪意あるアプリにリダイレクトを配信する可能性。

  PKCEなし：悪意あるアプリがコードを交換 -> ゲームオーバー。
  PKCEあり：悪意あるアプリはコードを持っているがverifierがない
            -> トークン交換失敗 -> データは安全。
```

---

## さらに学ぶために

- [RFC 7636 - Proof Key for Code Exchange](https://tools.ietf.org/html/rfc7636) -- 公式PKCE仕様。
- [OAuth 2.0 for Native Apps (RFC 8252)](https://tools.ietf.org/html/rfc8252) -- PKCEがモバイルで必須な理由。
- [OAuth 2.0 for Browser-Based Apps](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps) -- SPA向けのPKCE。
- [oidc.md](oidc.md) -- PKCEが保護するOIDCフロー全体。
- [csrf.md](csrf.md) -- PKCEと併用されるstateパラメータ。
