# volta-auth-proxy Glossary / 認証用語集

[English](#english) | [日本語](#japanese)

> "Everyone acts like they understand authentication, but they don't.
> Don't be shy -- look everything up. In the AI era, education matters!"
>
> 認証って「分かっている感」をみんな出しているけど、そんなことない。
> 恥ずかしがらずに全部調べよう。AI 時代は教育が大事！

**86 articles** covering authentication, security, and architecture concepts
used in volta-auth-proxy. Each article is available in English and Japanese.

**86 記事** -- volta-auth-proxy で使われる認証・セキュリティ・アーキテクチャ用語を
英語・日本語の両方で解説します。

---

<a id="english"></a>

## English Index

### Authentication Protocols / 認証プロトコル

| Term | EN | JA | Description |
|------|----|----|-------------|
| OIDC | [EN](oidc.md) | [JA](oidc.ja.md) | OpenID Connect -- prove "who is this person?" safely |
| OAuth 2.0 | [EN](oauth2.md) | [JA](oauth2.ja.md) | Authorization framework -- "what is this person allowed to do?" |
| SAML | -- | -- | Enterprise SSO standard (see [SSO](sso.md) for context) |
| SSO | [EN](sso.md) | [JA](sso.ja.md) | Single Sign-On -- log in once, access everything |
| SCIM | -- | -- | User provisioning protocol (Phase 4 roadmap) |
| Authorization Code Flow | [EN](authorization-code-flow.md) | [JA](authorization-code-flow.ja.md) | The primary OAuth 2.0 / OIDC flow for web apps |
| Consent Screen | [EN](consent-screen.md) | [JA](consent-screen.ja.md) | The "Allow this app?" dialog shown by the IdP |
| Discovery Document | [EN](discovery-document.md) | [JA](discovery-document.ja.md) | `.well-known/openid-configuration` metadata endpoint |
| Redirect URI | [EN](redirect-uri.md) | [JA](redirect-uri.ja.md) | Where the IdP sends the user after authentication |
| Scopes | [EN](scopes.md) | [JA](scopes.ja.md) | Permission boundaries requested during OAuth / OIDC |

### Tokens and Keys / トークン・鍵

| Term | EN | JA | Description |
|------|----|----|-------------|
| JWT | [EN](jwt.md) | [JA](jwt.ja.md) | JSON Web Token -- signed data carrying identity claims |
| JWK / JWKS | [EN](jwks.md) | [JA](jwks.ja.md) | Public keys for JWT signature verification (`/.well-known/jwks.json`) |
| RS256 | [EN](rs256.md) | [JA](rs256.ja.md) | RSA + SHA-256 signature algorithm -- volta's only allowed algorithm |
| HS256 | [EN](hs256.md) | [JA](hs256.ja.md) | HMAC + SHA-256 -- forbidden in volta (shared secret risk) |
| Bearer Token / Bearer Scheme | [EN](bearer-scheme.md) | [JA](bearer-scheme.ja.md) | `Authorization: Bearer <token>` HTTP scheme |
| id_token | [EN](id-token.md) | [JA](id-token.ja.md) | OIDC identity token -- "who is this person?" as a JWT |
| Access Token | [EN](access-token.md) | [JA](access-token.ja.md) | OAuth 2.0 token for API access authorization |
| Refresh Token | [EN](refresh-token.md) | [JA](refresh-token.ja.md) | Long-lived token to obtain new access tokens |

### JWT Claims / JWT の Claims

| Term | EN | JA | Description |
|------|----|----|-------------|
| JWT Header | [EN](jwt-header.md) | [JA](jwt-header.ja.md) | The `alg` and `kid` metadata section of a JWT |
| JWT Payload | [EN](jwt-payload.md) | [JA](jwt-payload.ja.md) | The claims section: iss, aud, sub, exp, and custom claims |
| JWT Signature | [EN](jwt-signature.md) | [JA](jwt-signature.ja.md) | Cryptographic proof that the JWT was not tampered with |
| JWT vs Session | [EN](jwt-vs-session.md) | [JA](jwt-vs-session.ja.md) | Trade-offs between stateless JWTs and server-side sessions |
| JWT Decode How-to | [EN](jwt-decode-howto.md) | [JA](jwt-decode-howto.ja.md) | Practical guide to inspecting and decoding JWTs |

### Security Measures / セキュリティ対策

| Term | EN | JA | Description |
|------|----|----|-------------|
| PKCE | [EN](pkce.md) | [JA](pkce.ja.md) | Proof Key for Code Exchange -- prevents auth code interception |
| state parameter | [EN](state.md) | [JA](state.ja.md) | CSRF protection via random round-trip value |
| nonce | [EN](nonce.md) | [JA](nonce.ja.md) | One-time value preventing token replay attacks |
| CSRF | [EN](csrf.md) | [JA](csrf.ja.md) | Cross-Site Request Forgery and how to prevent it |
| XSS | [EN](xss.md) | [JA](xss.ja.md) | Cross-Site Scripting -- stealing cookies via injected scripts |
| CORS | [EN](cors.md) | [JA](cors.ja.md) | Cross-Origin Resource Sharing -- controlling cross-domain API calls |
| Session Fixation | [EN](session-fixation.md) | [JA](session-fixation.ja.md) | Attack where attacker pre-sets the session ID |
| Rate Limiting | [EN](rate-limiting.md) | [JA](rate-limiting.ja.md) | Throttling excessive requests (Caffeine-based in volta) |
| MFA / 2FA | [EN](mfa.md) | [JA](mfa.ja.md) | Multi-Factor Authentication -- password + one more thing |
| TOTP | [EN](totp.md) | [JA](totp.ja.md) | Time-based One-Time Password (Google Authenticator) |
| WebAuthn / FIDO2 | [EN](webauthn.md) | [JA](webauthn.ja.md) | Fingerprint / security key / passkey authentication |

### Cookies and Sessions / Cookie・セッション

| Term | EN | JA | Description |
|------|----|----|-------------|
| Cookie | [EN](cookie.md) | [JA](cookie.ja.md) | Small browser-stored data -- foundation of session management |
| HttpOnly | [EN](httponly.md) | [JA](httponly.ja.md) | Cookie flag blocking JavaScript access (XSS defense) |
| SameSite | [EN](samesite.md) | [JA](samesite.ja.md) | Cookie attribute controlling cross-site sending (CSRF defense) |
| Session | [EN](session.md) | [JA](session.ja.md) | Server-side "this user is logged in" state |
| Sliding Window Expiry | [EN](sliding-window-expiry.md) | [JA](sliding-window-expiry.ja.md) | Session timeout that resets on each activity |
| Absolute Timeout | [EN](absolute-timeout.md) | [JA](absolute-timeout.ja.md) | Hard session expiration regardless of activity |
| Concurrent Session Limit | [EN](concurrent-session-limit.md) | [JA](concurrent-session-limit.ja.md) | Restricting how many active sessions a user can have |
| Session Storage Strategies | [EN](session-storage-strategies.md) | [JA](session-storage-strategies.ja.md) | In-memory vs Redis vs DB session backends |
| Session Hijacking | [EN](session-hijacking.md) | [JA](session-hijacking.ja.md) | Stealing an active session token |

### Cryptography / 暗号

| Term | EN | JA | Description |
|------|----|----|-------------|
| Public-Key Cryptography | [EN](public-key-cryptography.md) | [JA](public-key-cryptography.ja.md) | Asymmetric encryption with public/private key pairs |
| Digital Signature | [EN](digital-signature.md) | [JA](digital-signature.ja.md) | Proving data authenticity and integrity |
| Hash Function (SHA-256) | [EN](hash-function.md) | [JA](hash-function.ja.md) | One-way function producing a fixed-size digest |
| Encryption at Rest | [EN](encryption-at-rest.md) | [JA](encryption-at-rest.ja.md) | Encrypting stored data (signing keys, secrets) |
| Key Rotation | [EN](key-rotation.md) | [JA](key-rotation.ja.md) | Periodically replacing cryptographic keys |

### Architecture / アーキテクチャ

| Term | EN | JA | Description |
|------|----|----|-------------|
| ForwardAuth | [EN](forwardauth.md) | [JA](forwardauth.ja.md) | Reverse proxy delegates "is this user OK?" to volta |
| IdP | [EN](idp.md) | [JA](idp.ja.md) | Identity Provider -- Google, Okta, Keycloak, etc. |
| OIDC Provider | [EN](oidc-provider.md) | [JA](oidc-provider.ja.md) | Google, Okta, Keycloak as identity "certifiers" |
| Tenant | [EN](tenant.md) | [JA](tenant.ja.md) | Customer partition in a SaaS multi-tenant system |
| RBAC | [EN](rbac.md) | [JA](rbac.ja.md) | Role-Based Access Control |
| Client Credentials | [EN](client-credentials.md) | [JA](client-credentials.ja.md) | Machine-to-machine (M2M) OAuth 2.0 grant |
| Backchannel Logout | [EN](backchannel-logout.md) | [JA](backchannel-logout.ja.md) | Server-to-server "this user logged out" notification |
| Zero Trust | [EN](zero-trust.md) | [JA](zero-trust.ja.md) | "Never trust, always verify" security model |
| IAM | [EN](iam.md) | [JA](iam.ja.md) | Identity and Access Management -- the umbrella term |
| IDaaS | [EN](idaas.md) | [JA](idaas.ja.md) | Identity as a Service -- cloud-hosted auth (Auth0, Clerk, Okta) |

### Multi-tenancy / マルチテナント

| Term | EN | JA | Description |
|------|----|----|-------------|
| Row-Level Security | [EN](row-level-security.md) | [JA](row-level-security.ja.md) | Database-enforced tenant data isolation |
| Tenant Resolution | [EN](tenant-resolution.md) | [JA](tenant-resolution.ja.md) | Determining which tenant a request belongs to |
| Tenant Lifecycle | [EN](tenant-lifecycle.md) | [JA](tenant-lifecycle.ja.md) | Provisioning, suspending, and deleting tenants |
| Cross-Tenant Access | [EN](cross-tenant-access.md) | [JA](cross-tenant-access.ja.md) | Controlled access across tenant boundaries |
| Free Email Domains | [EN](free-email-domains.md) | [JA](free-email-domains.ja.md) | Handling gmail.com etc. in tenant-by-domain resolution |

### HTTP / Cache / HTTPキャッシュ

| Term | EN | JA | Description |
|------|----|----|-------------|
| Cache-Control | [EN](cache-control.md) | [JA](cache-control.ja.md) | HTTP header controlling caching behavior |
| no-store vs no-cache | [EN](no-store-vs-no-cache.md) | [JA](no-store-vs-no-cache.ja.md) | "Never store" vs "revalidate before use" |
| private vs public | [EN](private-vs-public.md) | [JA](private-vs-public.ja.md) | Browser-only cache vs shared/CDN cache |
| Browser Back Button Cache | [EN](browser-back-button-cache.md) | [JA](browser-back-button-cache.ja.md) | Why back-button can show stale authenticated pages |
| Data Leakage via Cache | [EN](data-leakage-via-cache.md) | [JA](data-leakage-via-cache.ja.md) | Sensitive data exposed through improper caching |

### Attack Patterns / 攻撃パターン

| Term | EN | JA | Description |
|------|----|----|-------------|
| Brute Force | [EN](brute-force.md) | [JA](brute-force.ja.md) | Exhaustive password guessing attack |
| Credential Stuffing | [EN](credential-stuffing.md) | [JA](credential-stuffing.ja.md) | Using leaked credentials from other breaches |
| Replay Attack | [EN](replay-attack.md) | [JA](replay-attack.ja.md) | Re-sending a captured valid request/token |
| Open Redirect | [EN](open-redirect.md) | [JA](open-redirect.ja.md) | Exploiting unchecked redirect URLs for phishing |
| Token Theft | [EN](token-theft.md) | [JA](token-theft.ja.md) | Stealing JWTs or session tokens |

### Protocol Concepts / プロトコル概念

| Term | EN | JA | Description |
|------|----|----|-------------|
| Content-Type | [EN](content-type.md) | [JA](content-type.ja.md) | HTTP header declaring the body's media type |
| Pagination | [EN](pagination.md) | [JA](pagination.ja.md) | Splitting large result sets across pages |
| Idempotency | [EN](idempotency.md) | [JA](idempotency.ja.md) | Same request, same result -- safe to retry |
| API Versioning | [EN](api-versioning.md) | [JA](api-versioning.ja.md) | Evolving APIs without breaking clients |

### Business & Industry / ビジネス・業界用語

| Term | EN | JA | Description |
|------|----|----|-------------|
| MAU | [EN](mau.md) | [JA](mau.ja.md) | Monthly Active Users -- how SaaS companies count users and charge |
| Vendor Lock-in | [EN](vendor-lock-in.md) | [JA](vendor-lock-in.ja.md) | When switching providers becomes impractical |
| Self-Hosting | [EN](self-hosting.md) | [JA](self-hosting.ja.md) | Running software on your own servers |
| Auth0 | [EN](auth0.md) | [JA](auth0.ja.md) | Popular IDaaS provider (and why volta is different) |
| Keycloak | [EN](keycloak.md) | [JA](keycloak.ja.md) | Open-source IAM server by Red Hat |
| Configuration Hell | [EN](config-hell.md) | [JA](config-hell.ja.md) | When software has too many settings |

### volta Internals / volta 内部技術

| Term | EN | JA | Description |
|------|----|----|-------------|
| Fat JAR | [EN](fat-jar.md) | [JA](fat-jar.ja.md) | Single executable Java file with all dependencies |
| Flyway | [EN](flyway.md) | [JA](flyway.ja.md) | Database migration tool -- auto-migrates on startup |
| HikariCP | [EN](hikaricp.md) | [JA](hikaricp.ja.md) | Database connection pool ("phone lines" for the DB) |
| Caffeine | [EN](caffeine-cache.md) | [JA](caffeine-cache.ja.md) | In-memory cache for rate limiting and session caching |
| jte | [EN](jte.md) | [JA](jte.ja.md) | Java Template Engine -- type-safe HTML generation |

---

## volta-auth-proxy Specific Terms / volta-auth-proxy 固有の用語

| Term / 用語 | Description / 説明 |
|------|------|
| `X-Volta-*` headers | Identity information passed to apps via ForwardAuth |
| `volta_v` | JWT claims schema version |
| `volta_tid` | Tenant ID in JWT claims |
| `volta_roles` | Role array in JWT claims |
| `volta-sdk-js` | Browser SDK with automatic 401 refresh |
| `volta-sdk` | Java SDK with JWT verification middleware |
| `volta-config.yaml` | App registration configuration file |
| `VOLTA_SERVICE_TOKEN` | Static M2M token (Phase 1) |

---

<a id="japanese"></a>

## 日本語 索引

### 認証プロトコル

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| OIDC | [EN](oidc.md) | [JA](oidc.ja.md) | OpenID Connect -- 「この人は誰？」を安全に確認する仕組み |
| OAuth 2.0 | [EN](oauth2.md) | [JA](oauth2.ja.md) | 「この人に何を許可する？」を決める認可フレームワーク |
| SAML | -- | -- | 企業向けの古い SSO 規格（[SSO](sso.md) を参照） |
| SSO | [EN](sso.md) | [JA](sso.ja.md) | Single Sign-On -- 一回ログインしたら全部使える |
| SCIM | -- | -- | ユーザー自動プロビジョニング（Phase 4 ロードマップ） |
| Authorization Code Flow | [EN](authorization-code-flow.md) | [JA](authorization-code-flow.ja.md) | Web アプリ向けの主要な OAuth 2.0 / OIDC フロー |
| Consent Screen | [EN](consent-screen.md) | [JA](consent-screen.ja.md) | IdP が表示する「このアプリを許可しますか？」ダイアログ |
| Discovery Document | [EN](discovery-document.md) | [JA](discovery-document.ja.md) | `.well-known/openid-configuration` メタデータエンドポイント |
| Redirect URI | [EN](redirect-uri.md) | [JA](redirect-uri.ja.md) | 認証後に IdP がユーザーを送るコールバック URL |
| Scopes | [EN](scopes.md) | [JA](scopes.ja.md) | OAuth / OIDC で要求する権限範囲 |

### トークン・鍵

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| JWT | [EN](jwt.md) | [JA](jwt.ja.md) | JSON Web Token -- 署名付きの認証データ。volta の心臓部 |
| JWK / JWKS | [EN](jwks.md) | [JA](jwks.ja.md) | JWT の署名を検証するための公開鍵 (`/.well-known/jwks.json`) |
| RS256 | [EN](rs256.md) | [JA](rs256.ja.md) | RSA + SHA-256 署名方式 -- volta 唯一の許可アルゴリズム |
| HS256 | [EN](hs256.md) | [JA](hs256.ja.md) | HMAC + SHA-256 -- volta では禁止（共有秘密鍵のリスク） |
| Bearer Token / Bearer Scheme | [EN](bearer-scheme.md) | [JA](bearer-scheme.ja.md) | `Authorization: Bearer <token>` HTTP スキーム |
| id_token | [EN](id-token.md) | [JA](id-token.ja.md) | OIDC の「この人は誰か」情報が入った JWT |
| Access Token | [EN](access-token.md) | [JA](access-token.ja.md) | OAuth 2.0 で API アクセスに使うトークン |
| Refresh Token | [EN](refresh-token.md) | [JA](refresh-token.ja.md) | 期限切れトークンを更新するためのトークン |

### JWT の Claims

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| JWT Header | [EN](jwt-header.md) | [JA](jwt-header.ja.md) | `alg` と `kid` のメタデータ部分 |
| JWT Payload | [EN](jwt-payload.md) | [JA](jwt-payload.ja.md) | Claims 部分: iss, aud, sub, exp, カスタム claims |
| JWT Signature | [EN](jwt-signature.md) | [JA](jwt-signature.ja.md) | JWT が改ざんされていないことの暗号的証明 |
| JWT vs Session | [EN](jwt-vs-session.md) | [JA](jwt-vs-session.ja.md) | ステートレス JWT とサーバーサイドセッションのトレードオフ |
| JWT Decode How-to | [EN](jwt-decode-howto.md) | [JA](jwt-decode-howto.ja.md) | JWT の中身を確認・デコードする実践ガイド |

### セキュリティ対策

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| PKCE | [EN](pkce.md) | [JA](pkce.ja.md) | 認証コードを横取りされないための仕組み |
| state パラメータ | [EN](state.md) | [JA](state.ja.md) | CSRF 防止 -- ランダム値を往復させる |
| nonce | [EN](nonce.md) | [JA](nonce.ja.md) | 同じトークンの使い回しを防ぐ一回限りの値 |
| CSRF | [EN](csrf.md) | [JA](csrf.ja.md) | Cross-Site Request Forgery -- 他サイトから勝手に送信 |
| XSS | [EN](xss.md) | [JA](xss.ja.md) | Cross-Site Scripting -- 悪意のスクリプトで Cookie を盗む |
| CORS | [EN](cors.md) | [JA](cors.ja.md) | Cross-Origin Resource Sharing -- 別ドメインの API 呼び出し制御 |
| Session Fixation | [EN](session-fixation.md) | [JA](session-fixation.ja.md) | 攻撃者が用意したセッション ID を使わせる攻撃 |
| Rate Limiting | [EN](rate-limiting.md) | [JA](rate-limiting.ja.md) | 大量リクエスト制限（volta は Caffeine ベース） |
| MFA / 2FA | [EN](mfa.md) | [JA](mfa.ja.md) | パスワード以外にもう一つ確認する多要素認証 |
| TOTP | [EN](totp.md) | [JA](totp.ja.md) | 30 秒ごとに変わるワンタイムパスワード |
| WebAuthn / FIDO2 | [EN](webauthn.md) | [JA](webauthn.ja.md) | 指紋やセキュリティキーで認証（パスキー） |

### Cookie・セッション

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Cookie | [EN](cookie.md) | [JA](cookie.ja.md) | ブラウザが覚えてくれる小さなデータ |
| HttpOnly | [EN](httponly.md) | [JA](httponly.ja.md) | JavaScript から Cookie を読めなくするフラグ |
| SameSite | [EN](samesite.md) | [JA](samesite.ja.md) | 別サイトからの Cookie 送信を制御 (Lax/Strict/None) |
| Session | [EN](session.md) | [JA](session.ja.md) | 「この人はログイン済み」を覚えておく仕組み |
| Sliding Window Expiry | [EN](sliding-window-expiry.md) | [JA](sliding-window-expiry.ja.md) | アクティビティごとにリセットされるセッションタイムアウト |
| Absolute Timeout | [EN](absolute-timeout.md) | [JA](absolute-timeout.ja.md) | アクティビティに関係なく強制的にセッション切れ |
| Concurrent Session Limit | [EN](concurrent-session-limit.md) | [JA](concurrent-session-limit.ja.md) | ユーザーが持てる同時セッション数の制限 |
| Session Storage Strategies | [EN](session-storage-strategies.md) | [JA](session-storage-strategies.ja.md) | インメモリ vs Redis vs DB のセッション保存方式 |
| Session Hijacking | [EN](session-hijacking.md) | [JA](session-hijacking.ja.md) | 有効なセッショントークンを盗む攻撃 |

### 暗号

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| 公開鍵暗号 | [EN](public-key-cryptography.md) | [JA](public-key-cryptography.ja.md) | 公開鍵と秘密鍵のペアで暗号化・署名 |
| Digital Signature | [EN](digital-signature.md) | [JA](digital-signature.ja.md) | データの真正性と完全性を証明する署名 |
| Hash Function (SHA-256) | [EN](hash-function.md) | [JA](hash-function.ja.md) | 固定長ダイジェストを生成する一方向関数 |
| Encryption at Rest | [EN](encryption-at-rest.md) | [JA](encryption-at-rest.ja.md) | 保存データの暗号化（署名鍵やシークレット） |
| Key Rotation | [EN](key-rotation.md) | [JA](key-rotation.ja.md) | 暗号鍵を定期的に入れ替える運用 |

### アーキテクチャ

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| ForwardAuth | [EN](forwardauth.md) | [JA](forwardauth.ja.md) | リバースプロキシが「この人 OK？」と volta に聞く仕組み |
| IdP | [EN](idp.md) | [JA](idp.ja.md) | Identity Provider -- Google, Okta, Keycloak 等 |
| OIDC プロバイダ | [EN](oidc-provider.md) | [JA](oidc-provider.ja.md) | Google、Okta、Keycloak 等の ID 「認証者」 |
| Tenant | [EN](tenant.md) | [JA](tenant.ja.md) | SaaS での「お客さんの区画」 |
| RBAC | [EN](rbac.md) | [JA](rbac.ja.md) | ロール（役割）で権限を制御 |
| Client Credentials | [EN](client-credentials.md) | [JA](client-credentials.ja.md) | サーバー同士の M2M 認証 (Phase 2) |
| Backchannel Logout | [EN](backchannel-logout.md) | [JA](backchannel-logout.ja.md) | サーバー間で「この人ログアウトした」を伝える |
| ゼロトラスト | [EN](zero-trust.md) | [JA](zero-trust.ja.md) | 「決して信頼せず、常に検証」セキュリティモデル |
| IAM | [EN](iam.md) | [JA](iam.ja.md) | ID・アクセス管理 -- 包括的な用語 |
| IDaaS | [EN](idaas.md) | [JA](idaas.ja.md) | クラウドホスト型認証サービス（Auth0、Clerk、Okta） |

### マルチテナント

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Row-Level Security | [EN](row-level-security.md) | [JA](row-level-security.ja.md) | DB レベルでテナントデータを分離する仕組み |
| Tenant Resolution | [EN](tenant-resolution.md) | [JA](tenant-resolution.ja.md) | リクエストがどのテナントに属するか判定する方法 |
| Tenant Lifecycle | [EN](tenant-lifecycle.md) | [JA](tenant-lifecycle.ja.md) | テナントの作成・停止・削除の管理 |
| Cross-Tenant Access | [EN](cross-tenant-access.md) | [JA](cross-tenant-access.ja.md) | テナント境界を越えた制御されたアクセス |
| Free Email Domains | [EN](free-email-domains.md) | [JA](free-email-domains.ja.md) | gmail.com 等のドメインベーステナント解決への対応 |

### HTTP キャッシュ

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Cache-Control | [EN](cache-control.md) | [JA](cache-control.ja.md) | キャッシュ動作を制御する HTTP ヘッダ |
| no-store vs no-cache | [EN](no-store-vs-no-cache.md) | [JA](no-store-vs-no-cache.ja.md) | 「絶対保存しない」vs「使う前に再検証」 |
| private vs public | [EN](private-vs-public.md) | [JA](private-vs-public.ja.md) | ブラウザ限定キャッシュ vs CDN 共有キャッシュ |
| Browser Back Button Cache | [EN](browser-back-button-cache.md) | [JA](browser-back-button-cache.ja.md) | 戻るボタンで古い認証済みページが表示される問題 |
| Data Leakage via Cache | [EN](data-leakage-via-cache.md) | [JA](data-leakage-via-cache.ja.md) | 不適切なキャッシュによる機密データ漏洩 |

### 攻撃パターン

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Brute Force | [EN](brute-force.md) | [JA](brute-force.ja.md) | パスワード総当たり攻撃 |
| Credential Stuffing | [EN](credential-stuffing.md) | [JA](credential-stuffing.ja.md) | 他サービスから漏洩した認証情報の使い回し |
| Replay Attack | [EN](replay-attack.md) | [JA](replay-attack.ja.md) | 盗んだ正当なリクエスト/トークンを再送する攻撃 |
| Open Redirect | [EN](open-redirect.md) | [JA](open-redirect.ja.md) | 未チェックのリダイレクト URL を悪用するフィッシング |
| Token Theft | [EN](token-theft.md) | [JA](token-theft.ja.md) | JWT やセッショントークンを盗む攻撃 |

### プロトコル概念

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Content-Type | [EN](content-type.md) | [JA](content-type.ja.md) | HTTP ボディのメディアタイプを宣言するヘッダ |
| Pagination | [EN](pagination.md) | [JA](pagination.ja.md) | 大量の結果をページに分割する方法 |
| Idempotency | [EN](idempotency.md) | [JA](idempotency.ja.md) | 同じリクエストで同じ結果 -- 安全にリトライ可能 |
| API Versioning | [EN](api-versioning.md) | [JA](api-versioning.ja.md) | クライアントを壊さずに API を進化させる方法 |

### ビジネス・業界用語

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| MAU | [EN](mau.md) | [JA](mau.ja.md) | 月間アクティブユーザー -- SaaS のユーザー数と課金の仕組み |
| ベンダーロックイン | [EN](vendor-lock-in.md) | [JA](vendor-lock-in.ja.md) | プロバイダの乗り換えが非現実的になること |
| セルフホスティング | [EN](self-hosting.md) | [JA](self-hosting.ja.md) | 自分のサーバーでソフトウェアを実行すること |
| Auth0 | [EN](auth0.md) | [JA](auth0.ja.md) | 人気の IDaaS プロバイダ（volta との違い） |
| Keycloak | [EN](keycloak.md) | [JA](keycloak.ja.md) | Red Hat のオープンソース IAM サーバー |
| 設定地獄 | [EN](config-hell.md) | [JA](config-hell.ja.md) | ソフトウェアの設定が多すぎる問題 |

### volta 内部技術

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Fat JAR | [EN](fat-jar.md) | [JA](fat-jar.ja.md) | 全依存関係を含む単一実行可能 Java ファイル |
| Flyway | [EN](flyway.md) | [JA](flyway.ja.md) | DB マイグレーションツール -- 起動時に自動実行 |
| HikariCP | [EN](hikaricp.md) | [JA](hikaricp.ja.md) | DB コネクションプール（DB への「電話回線」） |
| Caffeine | [EN](caffeine-cache.md) | [JA](caffeine-cache.ja.md) | レート制限とセッションキャッシュ用インメモリキャッシュ |
| jte | [EN](jte.md) | [JA](jte.ja.md) | Java テンプレートエンジン -- 型安全な HTML 生成 |

---

## volta-auth-proxy 固有の用語

| 用語 | 説明 |
|------|------|
| `X-Volta-*` ヘッダ | ForwardAuth で App に渡される identity 情報 |
| `volta_v` | JWT claims のスキーマバージョン |
| `volta_tid` | JWT 内のテナント ID |
| `volta_roles` | JWT 内のロール配列 |
| `volta-sdk-js` | ブラウザ用 SDK -- 401 自動リフレッシュ |
| `volta-sdk` | Java 用 SDK -- JWT 検証ミドルウェア |
| `volta-config.yaml` | App 登録ファイル |
| `VOLTA_SERVICE_TOKEN` | M2M 用静的トークン（Phase 1） |
