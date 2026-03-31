# OIDC (OpenID Connect)

[English version](oidc.md)

---

## これは何？

OpenID Connect（OIDC）は、あるサービスが「この人は誰か？」を別の信頼できるサービスに確認するための標準的な仕組みです。ウェブサイトで「Googleでログイン」ボタンをクリックしたとき、裏側で動いているのがOIDCです。ウェブサイトはあなたのGoogleパスワードを見ることなく、あなたの身元（名前、メールアドレスなど）を確認できます。

空港でのパスポートチェックに似ています。空港（ウェブサイト）はあなたのことを個人的には知りません。でも、パスポートを発行した政府（Google）を信頼しています。OIDCは「有効なパスポートとはどんなものか」「どうやってチェックするか」の取り決めです。

---

## なぜ重要なのか？

OIDCがなかったら、すべてのウェブサイトが以下を自前で用意する必要があります：

1. ユーザー名/パスワードのシステムを構築する
2. パスワードを安全に保存する（大半のサイトはこれを間違える）
3. パスワードリセット、アカウントロックアウト、総当たり攻撃対策を実装する
4. ユーザーに、また新しいアカウントを作らせる（そして忘れられる）

OIDCがあれば、ウェブサイトはこれらすべてを信頼できるプロバイダー（Google、Microsoftなど）に委任できます。ユーザーは慣れた安全なログイン体験を得られます。開発者は認証の最も難しい部分を避けられます。

もしOIDCが存在しなかったら、インターネットは互換性のないログインシステムの寄せ集めになり、パスワードの使い回し問題は今よりもっとひどくなっているでしょう。

---

## どう動くのか？

OIDCはOAuth 2.0（[oauth2.md](oauth2.md)参照）の上に構築されています。OAuth 2.0は**認可**（「何を許可されているか？」）を扱い、OIDCは**認証**（「あなたは誰か？」）を追加します。OIDCは**id_token**と呼ばれる特別なトークンを追加します。これは「この人はjane@example.comで、Googleが保証します」という署名付きデータです。

### 認可コードフロー（ステップバイステップ）

これが最も一般的で最も安全なOIDCフローです。volta-auth-proxyが使用しているのもこのフローです。

```
  あなた（ブラウザ）          volta-auth-proxy             Google
  ================          ================             ======

  1. 「ログイン」をクリック
  ──────────────────────►  2. 以下を生成：
                               - state（ランダム、CSRF防止用）
                               - nonce（ランダム、リプレイ防止用）
                               - code_verifier + code_challenge（PKCE）
                              DBに保存

                           3. ブラウザをGoogleにリダイレクト：
  ◄────── 302 リダイレクト ──────
       Location: https://accounts.google.com/o/oauth2/v2/auth
               ?response_type=code
               &client_id=YOUR_CLIENT_ID
               &redirect_uri=http://localhost:7070/callback
               &scope=openid email profile
               &state=ランダムな値
               &nonce=ランダムな値
               &code_challenge=チャレンジ値
               &code_challenge_method=S256

  4. ブラウザがリダイレクトに従う
  ──────────────────────────────────────────────────────►
                                                          5. Googleが
                                                            「アカウント選択」
                                                             画面を表示
  6. ユーザーがGoogleアカウントを選択
  ──────────────────────────────────────────────────────►
                                                          7. Googleが
                                                             ユーザーを検証

                                                          8. Googleが
                                                             CODEと一緒に
                                                             リダイレクトバック：
  ◄──────────────────────────────────────────────────────
       Location: http://localhost:7070/callback
               ?code=認可コード
               &state=ランダムな値

  9. ブラウザがvoltaのコールバックにアクセス
  ──────────────────────►
                          10. voltaがcode + stateを受け取る
                              - stateが保存したものと一致するか検証
                              - codeをトークンに交換（サーバー間通信）：

                              POST https://oauth2.googleapis.com/token
                              ──────────────────────────────────────────►
                                body: code=認可コード
                                      &client_id=YOUR_CLIENT_ID
                                      &client_secret=YOUR_SECRET
                                      &redirect_uri=コールバックURL
                                      &grant_type=authorization_code
                                      &code_verifier=元のverifier

                                                          11. Googleが検証：
                                                              - codeが有効か
                                                              - code_verifierが
                                                                challengeと一致か
                                                              - client_secretが
                                                                正しいか

                              ◄──────────────────────────────────────────
                                { "id_token": "eyJhbGci...",
                                  "access_token": "ya29..." }

                          12. voltaがid_tokenを検証：
                              - 署名を確認（RS256、Googleの公開鍵）
                              - 発行者 = accounts.google.com か
                              - audience = 自分のclient_id か
                              - nonceが保存したものと一致するか
                              - email_verified = true か
                              - 期限切れでないか

                          13. 身元情報を抽出：
                              email: jane@example.com
                              name: Jane Smith
                              google_sub: 1234567890

                          14. DBでユーザーを作成または検索
                          15. セッションを作成
                          16. volta JWTを発行

  ◄────── セッションCookieをセット + アプリにリダイレクト ──────
```

ステップが多いですが、それぞれに意味があります：

- **state** -- 攻撃者があなたを別人としてログインさせるのを防ぐ（CSRF -- [csrf.md](csrf.md)参照）
- **nonce** -- 攻撃者が古いid_tokenを再利用するのを防ぐ
- **PKCE**（code_verifier/code_challenge）-- 攻撃者が認可コードを横取りするのを防ぐ（[pkce.md](pkce.md)参照）
- **コード交換はサーバー間で行われる** -- ブラウザにclient_secretが渡ることがない
- **id_tokenは暗号的に署名されている** -- 偽造できない

### OAuth 2.0との関係

```
  ┌─────────────────────────────────────────────┐
  │              OAuth 2.0                       │
  │                                              │
  │  「このユーザーに何を許可する？」              │
  │  （認可）                                     │
  │                                              │
  │  access_tokenを発行して                       │
  │  ユーザーの代わりにAPIを呼べるようにする       │
  │                                              │
  │  ┌───────────────────────────────────────┐   │
  │  │         OpenID Connect (OIDC)         │   │
  │  │                                       │   │
  │  │  「このユーザーは誰？」                │   │
  │  │  （認証）                              │   │
  │  │                                       │   │
  │  │  OAuth 2.0にid_tokenを追加して         │   │
  │  │  身元情報（メール、名前）を提供         │   │
  │  └───────────────────────────────────────┘   │
  └─────────────────────────────────────────────┘
```

OAuth 2.0単体ではユーザーが**誰か**は分かりません。何かをする**許可**が得られるだけです。OIDCはその上にアイデンティティ層を追加し、id_token（JWT -- [jwt.md](jwt.md)参照）を使います。

---

## volta-auth-proxyではどう使われているか？

volta-auth-proxyはGoogleのOIDCエンドポイントに**直接**接続します。KeycloakもAuth0もoauth2-proxyも間に入りません。

### なぜKeycloakを使わないのか？

| アプローチ | 何が起きるか |
|-----------|-------------|
| KeycloakをIdPブローカーとして使う | ブラウザ -> Keycloak -> Google -> Keycloak -> あなたのアプリ。余計なホップが2つ。512MB以上のRAM。起動に30秒。何百もの設定項目。FreeMarkerテーマ地獄。 |
| voltaがGoogleに直接接続 | ブラウザ -> volta -> Google -> volta -> あなたのアプリ。最小限のホップ。約30MBのRAM。約200msで起動。`.env`ファイル1つ。すべてのステップを完全にコントロール。 |

voltaが直接接続を選ぶ理由：

1. **Phase 1ではGoogle OIDCだけで十分。** Keycloakの価値は複数のIdPを束ねることですが、1つしかない段階ではオーバーキルです。
2. **ログインUIを完全にコントロールしたい。** voltaはjteテンプレートを使います。普通のHTMLなので好きなようにスタイルできます。KeycloakだとFreeMarkerテーマを強制されます。
3. **すべての行を理解したい。** 認証はSaaSで最もセキュリティ上重要な部分です。よく理解していないブラックボックスに任せるのはリスクです。

### voltaのOIDC実装

コードは`OidcService.java`にあります。やっていることは：

1. `createAuthorizationUrl()` -- state、nonce、PKCE値を生成します。`oidc_flows`データベーステーブルに保存します。すべてのパラメータを含むGoogleの認可URLを返します。

2. `exchangeAndValidate()` -- Googleがリダイレクトバックしたときに呼ばれます。stateで保存されたフローを検索します。認可コードをid_tokenに交換します（サーバー間通信）。id_tokenの署名、発行者、audience、nonce、email_verifiedを検証します。

3. 検証済みの身元情報（`OidcIdentity`レコード）は`AuthService`に渡され、ユーザー/セッションの作成とvolta JWTの発行が行われます。

### voltaが適用するセキュリティ対策

| 対策 | 目的 |
|------|------|
| `state`パラメータ | ログイン中のCSRF防止 |
| id_token内の`nonce` | リプレイ攻撃防止 |
| PKCE (S256) | 認可コードの横取り防止 |
| `prompt=select_account` | 常にアカウント選択画面を表示（間違ったアカウントでのサイレントログイン防止） |
| 10分間のフロー有効期限 | 古い認可フローの悪用防止 |
| stateの1回限りの使用 | 各stateは1回しか消費できない |
| サーバー側でのトークン交換 | client_secretがブラウザに届かない |
| Google JWKS検証 | id_tokenの署名をGoogleの公開鍵で検証 |

---

## よくある間違いと攻撃

### 間違い1：id_tokenを検証しない

id_tokenを受け取って署名を確認せずにデコードだけする開発者がいます。攻撃者は任意のメールアドレスで偽のid_tokenを作れます。**必ずプロバイダーのJWKSエンドポイントで署名を検証してください。**

### 間違い2：stateパラメータを省略する

stateがないと、攻撃者は「ログインCSRF」攻撃ができます。攻撃者が自分のアカウントでOIDCフローを開始し、あなたにそれを完了させるよう仕向けます。あなたは攻撃者としてログインしてしまい、データが漏洩する可能性があります。詳細は[csrf.md](csrf.md)を参照。

### 間違い3：nonceを無視する

nonce検証がないと、有効なid_tokenを傍受した攻撃者が、後でそれを再利用して元のユーザーになりすませます。

### 間違い4：email_verifiedを確認しない

Googleアカウントには未検証のメールアドレスがありえます。このチェックを省略すると、誰かがあなたのメールでGoogleアカウントを作成してなりすますことができます。

### 攻撃：トークンの差し替え

攻撃者が認可コードを横取りし（PKCEがないモバイルで可能）、あなたより先にid_tokenに交換します。PKCEがこれを防ぎます。[pkce.md](pkce.md)を参照。

---

## さらに学ぶために

- [OpenID Connect Core 1.0仕様](https://openid.net/specs/openid-connect-core-1_0.html) -- 公式仕様。密度が高いですが決定版です。
- [GoogleのOpenID Connectドキュメント](https://developers.google.com/identity/openid-connect/openid-connect) -- Google固有の詳細。
- [OAuth 2.0 Simplified](https://www.oauth.com/) by Aaron Parecki -- OAuth/OIDCの最もとっつきやすいガイド。
- [jwt.md](jwt.md) -- id_token（JWT）が内部でどう動くか。
- [pkce.md](pkce.md) -- なぜPKCEが認可コードフローに必須なのか。
- [csrf.md](csrf.md) -- なぜstateパラメータが重要なのか。
