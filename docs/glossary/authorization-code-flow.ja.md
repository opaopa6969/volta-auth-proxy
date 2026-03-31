# 認可コードフロー

[English / 英語](authorization-code-flow.md)

---

## これは何？

認可コードフロー（Authorization Code Flow）は、OIDC/OAuth 2.0 を使ってウェブアプリケーションがユーザーをログインさせる最も一般的で最も安全な方法です。ユーザーは ID プロバイダ（例：Google）にリダイレクトされ、そこで認証し、短命の認可**コード**と共に戻されます。アプリケーションはこのコードをサーバー間の直接通信でトークンと交換します。重要な設計原則：「コード」はブラウザを通過するが、「トークン」は通過しない。

---

## なぜ重要？

ブラウザは敵対的な環境です。URL はブラウザ履歴、リファラーヘッダ、プロキシログに残り、ブラウザ拡張機能がそれを読めます。トークンがブラウザを直接通過すると（古い Implicit Flow のように）、傍受される可能性があります。認可コードフローはプロセスを2つに分割して解決します：

1. **フロントチャネル**（ブラウザ）：短命で使い捨てのコードだけが通過
2. **バックチャネル**（サーバー間）：本物のトークンは非公開で交換され、クライアントシークレットがアプリを認証

攻撃者がコードを傍受しても、クライアントシークレットなしでは交換できません（PKCE があればコードベリファイアも必要）。

---

## 簡単な例

```
ブラウザ             volta-auth-proxy            Google
  |                       |                         |
  |--- GET /login ------->|                         |
  |                       |-- state, nonce,         |
  |                       |   PKCE verifier を生成  |
  |<-- 302 リダイレクト ---|                         |
  |                       |                         |
  |--- リダイレクト先に遷移 --------------------------->|
  |                       |                 ログイン画面表示
  |                       |                 ユーザーがサインイン
  |<-------------------------------- 302 + ?code=ABC&state=XYZ
  |                       |                         |
  |--- GET /callback?code=ABC&state=XYZ -->|        |
  |                       |                         |
  |                       |--- POST /token -------->|
  |                       |    (code + secret       |
  |                       |     + code_verifier)    |
  |                       |<-- { id_token, ... } ---|
  |                       |                         |
  |                       |-- id_token を検証       |
  |                       |-- セッション作成        |
  |<-- Set-Cookie + アプリにリダイレクト             |
```

コード `ABC` は単体では無意味です。1回しか交換できず、すぐ期限切れになり、クライアントシークレットと PKCE ベリファイアが必要です。

---

## volta-auth-proxy での使い方

volta は `OidcService` で認可コードフローを実装しています：

**ステップ 1 -- フロー開始**（`createAuthorizationUrl()`）：
- `state`（CSRF 対策）、`nonce`（リプレイ対策）、PKCE の `code_verifier`/`code_challenge` を生成
- これらすべてを `oidc_flows` データベーステーブルに10分の有効期限で保存
- `scope=openid email profile` 付きの Google 認可 URL を返す

**ステップ 2 -- コールバック処理**（`exchangeAndValidate()`）：
- `state` で保存済みフローを検索（使い捨て：即座に消費される）
- `code_verifier` を含めて Google のトークンエンドポイントへのサーバー間 POST でコードを `id_token` と交換
- `id_token` を検証：署名（Google の JWKS による RS256）、発行者、受信者、nonce、有効期限、`email_verified`

**コードがブラウザを通過しても安全な理由**：
- 使い捨て（Google は1回の交換後に無効化）
- 数分で期限切れ
- PKCE（`code_challenge_method=S256`）により、フローを開始した当事者だけが完了できる
- `client_secret` は交換に必要だが、サーバーの外には出ない

関連: [redirect-uri.md](redirect-uri.md), [scopes.md](scopes.md)
