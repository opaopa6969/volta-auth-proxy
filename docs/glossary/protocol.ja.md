# プロトコル

[English version](protocol.md)

---

## 一言で言うと？

プロトコルとは、コンピュータ同士が通信するときに互いを理解するための取り決め（ルール集）です。ウェブページなら HTTP、安全なウェブページなら HTTPS などがあります。

---

## 同じ言語を話すということ

2人が会話するには、言語といくつかの基本ルール（順番に話す、遮らない等）に合意する必要があります。コンピュータも同じです：

| 人間の会話 | コンピュータのプロトコル |
|---|---|
| 言語（日本語、英語） | プロトコル名（[HTTP](http.ja.md), HTTPS, TCP） |
| 文法ルール | メッセージの形式と構造 |
| 「こんにちは」→「こんにちは、元気？」 | リクエスト → レスポンス |
| 違う言語なら大声で話しても通じない | プロトコルが合わなければデータを増やしても通じない |

日常的に出会うプロトコル：

| プロトコル | 何をするか | 日常の例 |
|---|---|---|
| [HTTP](http.ja.md) | ウェブページ転送（暗号化なし） | ブログを読む |
| HTTPS | ウェブページ転送（[SSL/TLS](ssl-tls.ja.md) で暗号化） | オンラインバンキング |
| TCP | 確実なデータ配送 | HTTP の土台 |
| DNS | [ドメイン](domain.ja.md)名を IP に変換 | `google.com` と入力 |
| SMTP | メール送信 | Gmail で「送信」を押す |
| [OAuth2](oauth2.ja.md) | 認可の委譲 | 「Google でサインイン」 |
| [OIDC](oidc.ja.md) | OAuth2 上の本人確認 | volta へのログイン |

---

## なぜ必要なの？

合意されたプロトコルがなければ：

- すべてのウェブサイトが独自の通信方法を発明し、[ブラウザ](browser.ja.md)はどれも理解できない
- 暗号化が標準化されない -- 各サイトが独自実装する（そして間違える）
- API が互換性なし -- リクエストの送り方やレスポンスの解析方法が標準化されない
- セキュリティ研究者が共通の脆弱性を見つけられない -- 共通のものがないから

プロトコルは、インターネットを動かす目に見えない契約です。

---

## volta-auth-proxy でのプロトコル

volta は複数のプロトコルを使い、また適用します：

| プロトコル | volta での使用箇所 |
|---|---|
| **HTTPS** | [ブラウザ](browser.ja.md)と[リバースプロキシ](reverse-proxy.ja.md)間の全通信は暗号化必須 |
| **[HTTP](http.ja.md)** | リバースプロキシと volta 間の内部通信（[Docker ネットワーク](network-isolation.ja.md)内、暗号化不要） |
| **[OAuth2](oauth2.ja.md)** | volta が Google にログインを委譲する認可フレームワーク |
| **[OIDC](oidc.ja.md)** | OAuth2 上のアイデンティティ層、volta にユーザーが誰かを伝える |
| **[JWT](jwt.ja.md) (RFC 7519)** | volta がアプリにユーザー情報を渡すトークン形式 |
| **JWKS (RFC 7517)** | volta が `/.well-known/jwks.json` で公開鍵を公開する方法 |

volta でのプロトコル適用：

- **本番では HTTPS 必須** -- [Cookie](cookie.ja.md) に `Secure` フラグが設定され、平文 HTTP では送信されない
- **JWT は RS256 のみ** -- volta は他のアルゴリズムで署名された JWT を拒否（`alg:none` や HS256 混同攻撃を防止）
- **State + Nonce + [PKCE](pkce.ja.md)** -- volta は [OIDC](oidc.ja.md) のセキュリティプロトコルを完全に遵守し、ハッピーパスだけではない

---

## 具体的な例

volta で保護されたアプリにログインする際に関与するプロトコル：

1. **HTTPS** -- [ブラウザ](browser.ja.md)が `https://app.acme.example.com` に接続（暗号化）
2. **HTTP** -- [リバースプロキシ](reverse-proxy.ja.md)が内部で volta に `http://volta:8080/auth/verify` を問い合わせ（プライベート[ネットワーク](network.ja.md)、暗号化不要）
3. **HTTP 302** -- volta が[ログイン](login.ja.md)ページへの[リダイレクト](redirect.ja.md)で応答
4. **[OAuth2](oauth2.ja.md) 認可コードフロー** -- volta が `client_id`、`redirect_uri`、`state`、`code_challenge`（[PKCE](pkce.ja.md)）で認可 URL を構築
5. **HTTPS** -- ブラウザが `https://accounts.google.com`（Google の認証サーバー）に接続
6. **[OIDC](oidc.ja.md)** -- Google がユーザーの ID を含む `id_token`（[JWT](jwt.ja.md)）を返す
7. **HTTPS** -- volta が Google のトークンエンドポイントに認可コードを交換するため呼び出し
8. **[JWT](jwt.ja.md) RS256** -- volta がアプリ用の署名付きトークンを作成
9. **HTTP [ヘッダー](header.ja.md)** -- volta が `X-Volta-JWT`、`X-Volta-User-Id` 等をアプリに渡す

各ステップが異なるプロトコルを使うか、別のプロトコルの上に構築されています。玉ねぎの層のように -- OIDC は OAuth2 の上に、OAuth2 は HTTP の上に、HTTP は TCP の上に構築されています。

---

## さらに学ぶために

- [HTTP](http.ja.md) -- 基本のウェブプロトコル
- [SSL/TLS](ssl-tls.ja.md) -- HTTP を HTTPS にする暗号化プロトコル
- [OAuth2](oauth2.ja.md) -- volta が使う認可プロトコル
- [OIDC](oidc.ja.md) -- OAuth2 上に構築されたアイデンティティプロトコル
- [JWT](jwt.ja.md) -- volta が使うトークン形式プロトコル
- [ネットワーク](network.ja.md) -- プロトコルが動作する場所
