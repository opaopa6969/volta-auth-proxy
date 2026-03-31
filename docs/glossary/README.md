# volta-auth-proxy 認証用語集

[English](README.md) | [日本語](README.md)

> 認証って「分かっている感」をみんな出しているけど、そんなことない。
> 恥ずかしがらずに全部調べよう。AI 時代は教育が大事！

認証・セキュリティの専門用語を、volta-auth-proxy での使われ方と一緒に解説します。
各用語をクリックすると詳細記事に飛びます。

---

## 認証プロトコル

| 用語 | 一言 | 詳細 |
|------|------|------|
| [OIDC](oidc.md) | 「この人は誰？」を安全に確認する仕組み | OpenID Connect。Google ログインの裏側 |
| [OAuth 2.0](oauth2.md) | 「この人に何を許可する？」を決める仕組み | OIDC の土台。認可のプロトコル |
| [SAML](saml.md) | 企業向けの古い SSO 規格 | Active Directory 連携で使う。Phase 3 |
| [SSO](sso.md) | 一回ログインしたら全部使える | Single Sign-On。volta の ForwardAuth がこれを実現 |
| [SCIM](scim.md) | 社員名簿を自動同期する仕組み | Okta/Azure AD からユーザーを自動追加/削除。Phase 4 |

## トークン・鍵

| 用語 | 一言 | 詳細 |
|------|------|------|
| [JWT](jwt.md) | 「この人は山田太郎で ADMIN」を詰めた署名付きデータ | JSON Web Token。volta の心臓部 |
| [JWK / JWKS](jwks.md) | JWT の署名を検証するための公開鍵 | JSON Web Key (Set)。`/.well-known/jwks.json` |
| [RS256](rs256.md) | JWT の署名方式（公開鍵暗号） | RSA + SHA-256。volta はこれだけ使う |
| [HS256](hs256.md) | JWT の署名方式（共有秘密鍵）— volta では禁止 | なぜ危険か、なぜ volta は使わないか |
| [kid](kid.md) | 「どの鍵で署名したか」のID | Key ID。鍵ローテーション時に必須 |
| [alg](alg.md) | 「どの暗号方式で署名したか」 | Algorithm。`none` を受け入れると全壊する話 |
| [Claims](claims.md) | JWT の中身（ペイロード） | iss, aud, sub, exp とカスタム claims |
| [Bearer Token](bearer.md) | 「このトークンを持ってる人は認証済み」 | Authorization ヘッダの Bearer スキーム |
| [id_token](id-token.md) | 「この人は誰か」の情報が入った JWT | OIDC で Google から返ってくるもの |
| [Access Token](access-token.md) | 「この人に何を許可するか」のトークン | OAuth 2.0 で API アクセスに使う |
| [Refresh Token](refresh-token.md) | 期限切れトークンを更新するためのトークン | volta では Cookie セッション + 短命 JWT で代用 |

## JWT の Claims（中身）

| 用語 | 一言 | 詳細 |
|------|------|------|
| [iss](iss.md) | 「誰が発行したか」 | Issuer。volta では `"volta-auth"` |
| [aud](aud.md) | 「誰に向けて発行したか」 | Audience。volta では `["volta-apps"]` |
| [sub](sub.md) | 「誰のトークンか」 | Subject。ユーザー ID |
| [exp](exp.md) | 「いつ期限切れか」 | Expiration。volta では発行から 5 分 |
| [amr](amr.md) | 「どうやって認証したか」 | Authentication Methods References。MFA 対応で使う |

## セキュリティ対策

| 用語 | 一言 | 詳細 |
|------|------|------|
| [PKCE](pkce.md) | 認証コードを横取りされないための仕組み | Proof Key for Code Exchange。モバイルで必須 |
| [state パラメータ](state.md) | ログイン中に攻撃者が割り込むのを防ぐ | CSRF 防止。ランダム値を往復させる |
| [nonce](nonce.md) | 同じトークンの使い回しを防ぐ | リプレイ攻撃防止。一度使ったら無効 |
| [CSRF](csrf.md) | 他のサイトから勝手にフォーム送信される攻撃 | Cross-Site Request Forgery と対策 |
| [XSS](xss.md) | 悪意のあるスクリプトが実行される攻撃 | Cross-Site Scripting。Cookie を盗まれる |
| [CORS](cors.md) | 別ドメインからの API 呼び出しを制御 | Cross-Origin Resource Sharing |
| [Session Fixation](session-fixation.md) | 攻撃者が用意したセッション ID を使わせる攻撃 | 対策: ログイン時に ID 再生成 |
| [Rate Limiting](rate-limiting.md) | 大量リクエストを制限する | ブルートフォース防止。volta は Caffeine ベース |
| [MFA / 2FA](mfa.md) | パスワード以外にもう一つ確認する | Multi-Factor / Two-Factor Authentication |
| [TOTP](totp.md) | 30 秒ごとに変わるワンタイムパスワード | Google Authenticator の仕組み |
| [WebAuthn / FIDO2](webauthn.md) | 指紋やセキュリティキーで認証 | パスキーの裏側の技術 |

## Cookie・セッション

| 用語 | 一言 | 詳細 |
|------|------|------|
| [Cookie](cookie.md) | ブラウザが覚えてくれる小さなデータ | セッション管理の基盤 |
| [HttpOnly](httponly.md) | JavaScript から Cookie を読めなくする | XSS 対策の基本 |
| [SameSite](samesite.md) | 別サイトからの Cookie 送信を制御 | CSRF 対策。Lax / Strict / None |
| [Secure](secure-flag.md) | HTTPS でだけ Cookie を送る | HTTP では Cookie が盗まれる |
| [Session](session.md) | 「この人はログイン済み」を覚えておく仕組み | Cookie + サーバー側の状態管理 |

## 暗号

| 用語 | 一言 | 詳細 |
|------|------|------|
| [RSA](rsa.md) | 公開鍵と秘密鍵のペアで暗号化・署名 | 非対称暗号。JWT 署名に使用 |
| [AES-256-GCM](aes.md) | データを暗号化する方式 | 対称暗号。volta は署名鍵の保存に使用 |
| [HMAC](hmac.md) | データが改ざんされていないか確認 | Hash-based Message Authentication Code |
| [base64url](base64url.md) | バイナリデータを URL セーフな文字列に | JWT や招待コードのエンコーディング |

## アーキテクチャ

| 用語 | 一言 | 詳細 |
|------|------|------|
| [ForwardAuth](forwardauth.md) | リバースプロキシが「この人 OK？」と聞く仕組み | Traefik の認証委譲パターン |
| [IdP](idp.md) | 「この人は誰か」を証明してくれるサービス | Identity Provider。Google, Okta, Keycloak 等 |
| [Tenant](tenant.md) | SaaS での「お客さんの区画」 | マルチテナントの基本概念 |
| [RBAC](rbac.md) | ロール（役割）で権限を制御 | Role-Based Access Control |
| [Client Credentials](client-credentials.md) | サーバー同士が認証する仕組み | M2M (Machine-to-Machine) 認証。Phase 2 |
| [Backchannel Logout](backchannel-logout.md) | サーバー間で「この人ログアウトしたよ」を伝える | RP-Initiated Logout の裏側 |
| [Content Negotiation](content-negotiation.md) | 「JSON が欲しい？HTML が欲しい？」を判定 | Accept ヘッダで応答形式を変える |

---

## volta-auth-proxy 固有の用語

| 用語 | 説明 |
|------|------|
| `X-Volta-*` ヘッダ | ForwardAuth で App に渡される identity 情報 |
| `volta_v` | JWT claims のスキーマバージョン |
| `volta_tid` | JWT 内のテナント ID |
| `volta_roles` | JWT 内のロール配列 |
| `volta-sdk-js` | ブラウザ用 SDK。401 自動リフレッシュ |
| `volta-sdk` | Java 用 SDK。JWT 検証ミドルウェア |
| `volta-config.yaml` | App 登録ファイル |
| `VOLTA_SERVICE_TOKEN` | M2M 用静的トークン（Phase 1） |
