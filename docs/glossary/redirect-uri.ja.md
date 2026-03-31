# リダイレクト URI

[English / 英語](redirect-uri.md)

---

## これは何？

リダイレクト URI（コールバック URL とも呼ばれる）は、認証後に ID プロバイダ（例：Google）がユーザーを返す先のアドレスです。「Google でサインイン」をクリックすると、Google は認可コードと共にあなたをどこに返すか知る必要があります。その行き先がリダイレクト URI です。事前に ID プロバイダに登録しておく必要があり、スキーム、ホスト、ポート、パスが**完全に一致**しなければなりません。

---

## なぜ重要？

リダイレクト URI が厳密に検証されないと、攻撃者が ID プロバイダをだまして認可コードを攻撃者が管理する URL に送らせることができます。これを**オープンリダイレクト攻撃**と呼びます。攻撃者がコードを受け取り、トークンと交換し、被害者のアカウントにアクセスします。

Google などのプロバイダは、デベロッパーコンソールで正確なリダイレクト URI を登録することを要求してこれを防止しています。`redirect_uri` パラメータが登録値と一致しない認可リクエストは拒否されます。これはオプションの慎重さではなく、重要なセキュリティ境界です。

---

## 簡単な例

Google Console に登録されたリダイレクト URI：
```
http://localhost:7070/callback
```

認可リクエスト：
```
https://accounts.google.com/o/oauth2/v2/auth
  ?redirect_uri=http://localhost:7070/callback   <-- 完全一致が必須
  &client_id=YOUR_ID
  &response_type=code
  &scope=openid email profile
```

攻撃者が redirect_uri を変更した場合：
```
?redirect_uri=https://evil.com/steal
```

Google は `https://evil.com/steal` が登録されていないためリクエストを拒否。攻撃は失敗します。

---

## volta-auth-proxy での使い方

volta のリダイレクト URI は環境変数 `GOOGLE_REDIRECT_URI` で設定されます：

```
GOOGLE_REDIRECT_URI=http://localhost:7070/callback
```

この値は2箇所で使われます：

1. **認可 URL の構築**（`OidcService.createAuthorizationUrl()`）：Google 認可リクエストの `redirect_uri` パラメータとして
2. **コードの交換**（`OidcService.exchangeCode()`）：トークン交換 POST にも含まれる。Google は初回リクエストとコード交換の両方で redirect_uri が一致することを要求 -- 不一致だと交換が失敗

volta にはログイン後リダイレクトの別の仕組みもあります：`returnTo` パラメータです。OIDC フロー全体の完了後にユーザーが遷移する先で、`ALLOWED_REDIRECT_DOMAINS` ホワイトリストで検証されます（[open-redirect.md](open-redirect.md) 参照）。OIDC リダイレクト URI（`/callback`）とアプリケーションリダイレクト（`returnTo`）は別物です -- 前者は volta と Google の間、後者は volta と下流アプリの間のものです。

関連: [open-redirect.md](open-redirect.md), [authorization-code-flow.md](authorization-code-flow.md)
