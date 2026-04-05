# volta-auth-proxy Glossary / 認証用語集

[English](#english) | [日本語](#japanese)

> "Everyone acts like they understand authentication, but they don't.
> Don't be shy -- look everything up. In the AI era, education matters!"
>
> 認証って「分かっている感」をみんな出しているけど、そんなことない。
> 恥ずかしがらずに全部調べよう。AI 時代は教育が大事！

**585 glossary files** (EN 293 + JA 292) covering authentication, security, and [architecture](architecture.md) concepts
used in volta-auth-proxy. Each article is available in English and Japanese.

**585 ファイル**（EN 293 + JA 292） -- volta-auth-proxy で使われる認証・セキュリティ・アーキテクチャ用語を
英語・日本語の両方で解説します。

***

<a id="english"></a>

## English Index

### Authentication Protocols / 認証プロトコル

| Term | EN | JA | Description |
|------|----|----|-------------|
| OIDC | [EN](oidc.md) | [JA](oidc.ja.md) | OpenID Connect -- prove "who is this person?" safely |
| OAuth 2.0 | [EN](oauth2.md) | [JA](oauth2.ja.md) | Authorization [framework](framework.md) -- "what is this person allowed to do?" |
| SAML | -- | -- | Enterprise [SSO](sso.md) standard (see [SSO](sso.md) for context) |
| SSO | [EN](sso.md) | [JA](sso.ja.md) | Single Sign-On -- log in once, access everything |
| SCIM | -- | -- | User [provisioning](provisioning.md) [protocol](protocol.md) (Phase 4 roadmap) |
| [Authorization Code Flow](authorization-code-flow.md) | [EN](authorization-code-flow.md) | [JA](authorization-code-flow.ja.md) | The primary OAuth 2.0 / OIDC flow for web apps |
| [Consent Screen](consent-screen.md) | [EN](consent-screen.md) | [JA](consent-screen.ja.md) | The "Allow this app?" dialog shown by the IdP |
| [Discovery Document](discovery-document.md) | [EN](discovery-document.md) | [JA](discovery-document.ja.md) | `.well-known/openid-configuration` metadata [endpoint](endpoint.md) |
| [Redirect](redirect.md) URI | [EN](redirect-uri.md) | [JA](redirect-uri.ja.md) | Where the IdP sends the user after authentication |
| [Scopes](scopes.md) | [EN](scopes.md) | [JA](scopes.ja.md) | Permission boundaries requested during OAuth / OIDC |

### Tokens and Keys / トークン・鍵

| Term | EN | JA | Description |
|------|----|----|-------------|
| [JWT](jwt.md) | [EN](jwt.md) | [JA](jwt.ja.md) | JSON Web [Token](token.md) -- signed data carrying identity [claim](claim.md)s |
| JWK / JWKS | [EN](jwks.md) | [JA](jwks.ja.md) | Public keys for JWT signature [verification](verification.md) (`/.well-known/jwks.json`) |
| RS256 | [EN](rs256.md) | [JA](rs256.ja.md) | RSA + SHA-256 signature algorithm -- volta's only allowed algorithm |
| HS256 | [EN](hs256.md) | [JA](hs256.ja.md) | HMAC + SHA-256 -- forbidden in volta (shared secret risk) |
| Bearer Token / [Bearer Scheme](bearer-scheme.md) | [EN](bearer-scheme.md) | [JA](bearer-scheme.ja.md) | `Authorization: Bearer <token>` HTTP scheme |
| id\_[token](token.md) | [EN](id-token.md) | [JA](id-token.ja.md) | OIDC identity token -- "who is this person?" as a JWT |
| [Access Token](access-token.md) | [EN](access-token.md) | [JA](access-token.ja.md) | OAuth 2.0 token for [API](api.md) access authorization |
| [Refresh Token](refresh-token.md) | [EN](refresh-token.md) | [JA](refresh-token.ja.md) | Long-lived token to obtain new [access token](access-token.md)s |

### JWT Claims / JWT の Claims

| Term | EN | JA | Description |
|------|----|----|-------------|
| [JWT](jwt.md) [Header](header.md) | [EN](jwt-header.md) | [JA](jwt-header.ja.md) | The `alg` and `kid` metadata section of a JWT |
| JWT [Payload](payload.md) | [EN](jwt-payload.md) | [JA](jwt-payload.ja.md) | The [claim](claim.md)s section: iss, aud, sub, exp, and custom claims |
| JWT Signature | [EN](jwt-signature.md) | [JA](jwt-signature.ja.md) | Cryptographic proof that the JWT was not tampered with |
| JWT vs [Session](session.md) | [EN](jwt-vs-session.md) | [JA](jwt-vs-session.ja.md) | Trade-offs between [state](state.md)less JWTs and [server](server.md)-side [session](session.md)s |
| JWT Decode How-to | [EN](jwt-decode-howto.md) | [JA](jwt-decode-howto.ja.md) | Practical guide to inspecting and decoding JWTs |

### Security Measures / セキュリティ対策

| Term | EN | JA | Description |
|------|----|----|-------------|
| PKCE | [EN](pkce.md) | [JA](pkce.ja.md) | Proof Key for Code Exchange -- prevents auth code interception |
| [state](state.md) parameter | [EN](state.md) | [JA](state.ja.md) | CSRF protection via random round-trip value |
| [nonce](nonce.md) | [EN](nonce.md) | [JA](nonce.ja.md) | One-time value preventing [token](token.md) [replay attack](replay-attack.md)s |
| CSRF | [EN](csrf.md) | [JA](csrf.ja.md) | Cross-Site Request Forgery and how to prevent it |
| [XSS](xss.md) | [EN](xss.md) | [JA](xss.ja.md) | Cross-Site Scripting -- stealing [cookie](cookie.md)s via injected scripts |
| CORS | [EN](cors.md) | [JA](cors.ja.md) | Cross-Origin Resource Sharing -- controlling cross-[domain](domain.md) [API](api.md) calls |
| [Session Fixation](session-fixation.md) | [EN](session-fixation.md) | [JA](session-fixation.ja.md) | Attack where attacker pre-sets the [session](session.md) ID |
| [Rate Limiting](rate-limiting.md) | [EN](rate-limiting.md) | [JA](rate-limiting.ja.md) | Throttling excessive requests (Caffeine-based in volta) |
| [MFA](mfa.md) / 2FA | [EN](mfa.md) | [JA](mfa.ja.md) | Multi-Factor Authentication -- password + one more thing |
| TOTP | [EN](totp.md) | [JA](totp.ja.md) | Time-based One-Time Password (Google Authenticator) |
| WebAuthn / FIDO2 | [EN](webauthn.md) | [JA](webauthn.ja.md) | Fingerprint / security key / passkey authentication |

### Cookies and Sessions / Cookie・セッション

| Term | EN | JA | Description |
|------|----|----|-------------|
| [Cookie](cookie.md) | [EN](cookie.md) | [JA](cookie.ja.md) | Small [browser](browser.md)-stored data -- foundation of [session management](session-management.md) |
| [Http](http.md)Only | [EN](httponly.md) | [JA](httponly.ja.md) | Cookie flag blocking [Java](java.md)Script access ([XSS](xss.md) defense) |
| SameSite | [EN](samesite.md) | [JA](samesite.ja.md) | Cookie attribute controlling cross-site sending (CSRF defense) |
| [Session](session.md) | [EN](session.md) | [JA](session.ja.md) | [Server](server.md)-side "this user is logged in" [state](state.md) |
| [Sliding Window Expiry](sliding-window-expiry.md) | [EN](sliding-window-expiry.md) | [JA](sliding-window-expiry.ja.md) | Session timeout that resets on each activity |
| [Absolute Timeout](absolute-timeout.md) | [EN](absolute-timeout.md) | [JA](absolute-timeout.ja.md) | Hard [session](session.md) expiration regardless of activity |
| [Concurrent Session Limit](concurrent-session-limit.md) | [EN](concurrent-session-limit.md) | [JA](concurrent-session-limit.ja.md) | Restricting how many active sessions a user can have |
| [Session Storage Strategies](session-storage-strategies.md) | [EN](session-storage-strategies.md) | [JA](session-storage-strategies.ja.md) | [In-memory](in-memory.md) vs [Redis](redis.md) vs DB session backends |
| [Session Hijacking](session-hijacking.md) | [EN](session-hijacking.md) | [JA](session-hijacking.ja.md) | Stealing an active session [token](token.md) |

### Cryptography / 暗号

| Term | EN | JA | Description |
|------|----|----|-------------|
| Public-Key Cryptography | [EN](public-key-cryptography.md) | [JA](public-key-cryptography.ja.md) | Asymmetric [encryption](encryption.md) with public/private key pairs |
| [Digital Signature](digital-signature.md) | [EN](digital-signature.md) | [JA](digital-signature.ja.md) | Proving data authenticity and integrity |
| [Hash Function](hash-function.md) (SHA-256) | [EN](hash-function.md) | [JA](hash-function.ja.md) | One-way function producing a fixed-size digest |
| [Encryption](encryption.md) at Rest | [EN](encryption-at-rest.md) | [JA](encryption-at-rest.ja.md) | Encrypting stored data ([signing key](signing-key.md)s, secrets) |
| [Key Rotation](key-rotation.md) | [EN](key-rotation.md) | [JA](key-rotation.ja.md) | Periodically replacing cryptographic keys |

### Architecture / アーキテクチャ

| Term | EN | JA | Description |
|------|----|----|-------------|
| ForwardAuth | [EN](forwardauth.md) | [JA](forwardauth.ja.md) | [Reverse proxy](reverse-proxy.md) delegates "is this user OK?" to volta |
| IdP | [EN](idp.md) | [JA](idp.ja.md) | Identity Provider -- Google, [Okta](okta.md), [Keycloak](keycloak.md), etc. |
| OIDC Provider | [EN](oidc-provider.md) | [JA](oidc-provider.ja.md) | Google, Okta, Keycloak as identity "certifiers" |
| [Tenant](tenant.md) | [EN](tenant.md) | [JA](tenant.ja.md) | Customer partition in a SaaS [multi-tenant](multi-tenant.md) system |
| RBAC | [EN](rbac.md) | [JA](rbac.ja.md) | [Role](role.md)-Based Access Control |
| [Client Credentials](client-credentials.md) | [EN](client-credentials.md) | [JA](client-credentials.ja.md) | Machine-to-machine ([M2M](m2m.md)) OAuth 2.0 grant |
| [Backchannel Logout](backchannel-logout.md) | [EN](backchannel-logout.md) | [JA](backchannel-logout.ja.md) | [Server](server.md)-to-[server](server.md) "this user logged out" notification |
| [Zero Trust](zero-trust.md) | [EN](zero-trust.md) | [JA](zero-trust.ja.md) | "Never trust, always verify" security model |
| [IAM](iam.md) | [EN](iam.md) | [JA](iam.ja.md) | Identity and Access Management -- the umbrella term |
| IDaaS | [EN](idaas.md) | [JA](idaas.ja.md) | Identity as a Service -- cloud-hosted auth ([Auth0](auth0.md), Clerk, Okta) |

### Multi-tenancy / マルチテナント

| Term | EN | JA | Description |
|------|----|----|-------------|
| Row-Level Security | [EN](row-level-security.md) | [JA](row-level-security.ja.md) | [Database](database.md)-enforced [tenant](tenant.md) data isolation |
| [Tenant Resolution](tenant-resolution.md) | [EN](tenant-resolution.md) | [JA](tenant-resolution.ja.md) | Determining which tenant a request belongs to |
| [Tenant Lifecycle](tenant-lifecycle.md) | [EN](tenant-lifecycle.md) | [JA](tenant-lifecycle.ja.md) | [Provisioning](provisioning.md), suspending, and deleting tenants |
| Cross-[Tenant](tenant.md) Access | [EN](cross-tenant-access.md) | [JA](cross-tenant-access.ja.md) | Controlled access across tenant boundaries |
| [Free Email Domains](free-email-domains.md) | [EN](free-email-domains.md) | [JA](free-email-domains.ja.md) | Handling gmail.com etc. in tenant-by-[domain](domain.md) resolution |

### HTTP / Cache / HTTPキャッシュ

| Term | EN | JA | Description |
|------|----|----|-------------|
| Cache-Control | [EN](cache-control.md) | [JA](cache-control.ja.md) | HTTP [header](header.md) controlling caching behavior |
| no-store vs no-cache | [EN](no-store-vs-no-cache.md) | [JA](no-store-vs-no-cache.ja.md) | "Never store" vs "revalidate before use" |
| [private vs public](private-vs-public.md) | [EN](private-vs-public.md) | [JA](private-vs-public.ja.md) | [Browser](browser.md)-only cache vs shared/CDN cache |
| [Browser Back Button Cache](browser-back-button-cache.md) | [EN](browser-back-button-cache.md) | [JA](browser-back-button-cache.ja.md) | Why back-button can show stale authenticated pages |
| Data Leakage via Cache | [EN](data-leakage-via-cache.md) | [JA](data-leakage-via-cache.ja.md) | Sensitive data exposed through improper caching |

### Attack Patterns / 攻撃パターン

| Term | EN | JA | Description |
|------|----|----|-------------|
| [Brute Force](brute-force.md) | [EN](brute-force.md) | [JA](brute-force.ja.md) | Exhaustive password guessing attack |
| [Credential Stuffing](credential-stuffing.md) | [EN](credential-stuffing.md) | [JA](credential-stuffing.ja.md) | Using leaked [credentials](credentials.md) from other breaches |
| [Replay Attack](replay-attack.md) | [EN](replay-attack.md) | [JA](replay-attack.ja.md) | Re-sending a captured valid request/[token](token.md) |
| [Open Redirect](open-redirect.md) | [EN](open-redirect.md) | [JA](open-redirect.ja.md) | Exploiting unchecked [redirect](redirect.md) [URL](url.md)s for phishing |
| [Token Theft](token-theft.md) | [EN](token-theft.md) | [JA](token-theft.ja.md) | Stealing [JWT](jwt.md)s or [session](session.md) tokens |

### Protocol Concepts / プロトコル概念

| Term | EN | JA | Description |
|------|----|----|-------------|
| Content-Type | [EN](content-type.md) | [JA](content-type.ja.md) | HTTP [header](header.md) declaring the body's media type |
| [Pagination](pagination.md) | [EN](pagination.md) | [JA](pagination.ja.md) | Splitting large result sets across pages |
| [Idempotency](idempotency.md) | [EN](idempotency.md) | [JA](idempotency.ja.md) | Same request, same result -- safe to [retry](retry.md) |
| [API](api.md) Versioning | [EN](api-versioning.md) | [JA](api-versioning.ja.md) | Evolving APIs without breaking [client](client.md)s |

### Business & Industry / ビジネス・業界用語

| Term | EN | JA | Description |
|------|----|----|-------------|
| [MAU](mau.md) | [EN](mau.md) | [JA](mau.ja.md) | Monthly Active Users -- how SaaS companies count users and charge |
| Vendor Lock-in | [EN](vendor-lock-in.md) | [JA](vendor-lock-in.ja.md) | When switching providers becomes impractical |
| Self-Hosting | [EN](self-hosting.md) | [JA](self-hosting.ja.md) | Running software on your own [server](server.md)s |
| [Auth0](auth0.md) | [EN](auth0.md) | [JA](auth0.ja.md) | Popular IDaaS provider (and why volta is different) |
| [Keycloak](keycloak.md) | [EN](keycloak.md) | [JA](keycloak.ja.md) | [Open-source](open-source.md) [IAM](iam.md) server by Red Hat |
| Configuration Hell | [EN](config-hell.md) | [JA](config-hell.ja.md) | When software has too many settings |

### volta Internals / volta 内部技術

| Term | EN | JA | Description |
|------|----|----|-------------|
| Fat JAR | [EN](fat-jar.md) | [JA](fat-jar.ja.md) | Single executable [Java](java.md) file with all [dependencies](dependencies.md) |
| [Flyway](flyway.md) | [EN](flyway.md) | [JA](flyway.ja.md) | [Database migration](database-migration.md) tool -- auto-migrates on [startup](startup.md) |
| HikariCP | [EN](hikaricp.md) | [JA](hikaricp.ja.md) | [Database](database.md) [connection pool](connection-pool.md) ("phone lines" for the DB) |
| Caffeine | [EN](caffeine-cache.md) | [JA](caffeine-cache.ja.md) | [In-memory](in-memory.md) cache for [rate limiting](rate-limiting.md) and [session](session.md) caching |
| [jte](jte.md) | [EN](jte.md) | [JA](jte.ja.md) | Java [Template](template.md) Engine -- [type-safe](type-safe.md) HTML generation |

***

## volta-auth-proxy Specific Terms / volta-auth-proxy 固有の用語

| Term / 用語 | Description / 説明 |
|------|------|
| `X-Volta-*` [header](header.md)s | Identity information passed to apps via ForwardAuth |
| `volta_v` | [JWT](jwt.md) [claim](claim.md)s [schema](schema.md) version |
| `volta_tid` | [Tenant](tenant.md) ID in JWT claims |
| `volta_roles` | [Role](role.md) array in JWT claims |
| `volta-sdk-js` | [Browser](browser.md) [SDK](sdk.md) with automatic 401 refresh |
| `volta-sdk` | [Java](java.md) SDK with JWT [verification](verification.md) [middleware](middleware.md) |
| `volta-config.yaml` | App registration configuration file |
| `VOLTA_SERVICE_TOKEN` | Static [M2M](m2m.md) [token](token.md) (Phase 1) |

***

<a id="japanese"></a>

## 日本語 索引

### 認証プロトコル

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| OIDC | [EN](oidc.md) | [JA](oidc.ja.md) | OpenID Connect -- 「この人は誰？」を安全に確認する仕組み |
| OAuth 2.0 | [EN](oauth2.md) | [JA](oauth2.ja.md) | 「この人に何を許可する？」を決める認可フレームワーク |
| SAML | -- | -- | 企業向けの古い [SSO](sso.md) 規格（[SSO](sso.md) を参照） |
| SSO | [EN](sso.md) | [JA](sso.ja.md) | Single Sign-On -- 一回ログインしたら全部使える |
| SCIM | -- | -- | ユーザー自動プロビジョニング（Phase 4 ロードマップ） |
| [Authorization Code Flow](authorization-code-flow.md) | [EN](authorization-code-flow.md) | [JA](authorization-code-flow.ja.md) | Web アプリ向けの主要な OAuth 2.0 / OIDC フロー |
| [Consent Screen](consent-screen.md) | [EN](consent-screen.md) | [JA](consent-screen.ja.md) | IdP が表示する「このアプリを許可しますか？」ダイアログ |
| [Discovery Document](discovery-document.md) | [EN](discovery-document.md) | [JA](discovery-document.ja.md) | `.well-known/openid-configuration` メタデータエンドポイント |
| [Redirect](redirect.md) URI | [EN](redirect-uri.md) | [JA](redirect-uri.ja.md) | 認証後に IdP がユーザーを送るコールバック [URL](url.md) |
| [Scopes](scopes.md) | [EN](scopes.md) | [JA](scopes.ja.md) | OAuth / OIDC で要求する権限範囲 |

### トークン・鍵

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| [JWT](jwt.md) | [EN](jwt.md) | [JA](jwt.ja.md) | JSON Web [Token](token.md) -- 署名付きの認証データ。volta の心臓部 |
| JWK / JWKS | [EN](jwks.md) | [JA](jwks.ja.md) | JWT の署名を検証するための公開鍵 (`/.well-known/jwks.json`) |
| RS256 | [EN](rs256.md) | [JA](rs256.ja.md) | RSA + SHA-256 署名方式 -- volta 唯一の許可アルゴリズム |
| HS256 | [EN](hs256.md) | [JA](hs256.ja.md) | HMAC + SHA-256 -- volta では禁止（共有秘密鍵のリスク） |
| Bearer Token / [Bearer Scheme](bearer-scheme.md) | [EN](bearer-scheme.md) | [JA](bearer-scheme.ja.md) | `Authorization: Bearer <token>` HTTP スキーム |
| id\_[token](token.md) | [EN](id-token.md) | [JA](id-token.ja.md) | OIDC の「この人は誰か」情報が入った JWT |
| [Access Token](access-token.md) | [EN](access-token.md) | [JA](access-token.ja.md) | OAuth 2.0 で [API](api.md) アクセスに使うトークン |
| [Refresh Token](refresh-token.md) | [EN](refresh-token.md) | [JA](refresh-token.ja.md) | 期限切れトークンを更新するためのトークン |

### JWT の Claims

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| [JWT](jwt.md) [Header](header.md) | [EN](jwt-header.md) | [JA](jwt-header.ja.md) | `alg` と `kid` のメタデータ部分 |
| JWT [Payload](payload.md) | [EN](jwt-payload.md) | [JA](jwt-payload.ja.md) | [Claim](claim.md)s 部分: iss, aud, sub, exp, カスタム [claim](claim.md)s |
| JWT Signature | [EN](jwt-signature.md) | [JA](jwt-signature.ja.md) | JWT が改ざんされていないことの暗号的証明 |
| JWT vs [Session](session.md) | [EN](jwt-vs-session.md) | [JA](jwt-vs-session.ja.md) | ステートレス JWT とサーバーサイドセッションのトレードオフ |
| JWT Decode How-to | [EN](jwt-decode-howto.md) | [JA](jwt-decode-howto.ja.md) | JWT の中身を確認・デコードする実践ガイド |

### セキュリティ対策

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| PKCE | [EN](pkce.md) | [JA](pkce.ja.md) | 認証コードを横取りされないための仕組み |
| [state](state.md) パラメータ | [EN](state.md) | [JA](state.ja.md) | CSRF 防止 -- ランダム値を往復させる |
| [nonce](nonce.md) | [EN](nonce.md) | [JA](nonce.ja.md) | 同じトークンの使い回しを防ぐ一回限りの値 |
| CSRF | [EN](csrf.md) | [JA](csrf.ja.md) | Cross-Site Request Forgery -- 他サイトから勝手に送信 |
| [XSS](xss.md) | [EN](xss.md) | [JA](xss.ja.md) | Cross-Site Scripting -- 悪意のスクリプトで [Cookie](cookie.md) を盗む |
| CORS | [EN](cors.md) | [JA](cors.ja.md) | Cross-Origin Resource Sharing -- 別ドメインの [API](api.md) 呼び出し制御 |
| [Session Fixation](session-fixation.md) | [EN](session-fixation.md) | [JA](session-fixation.ja.md) | 攻撃者が用意したセッション ID を使わせる攻撃 |
| [Rate Limiting](rate-limiting.md) | [EN](rate-limiting.md) | [JA](rate-limiting.ja.md) | 大量リクエスト制限（volta は Caffeine ベース） |
| [MFA](mfa.md) / 2FA | [EN](mfa.md) | [JA](mfa.ja.md) | パスワード以外にもう一つ確認する多要素認証 |
| TOTP | [EN](totp.md) | [JA](totp.ja.md) | 30 秒ごとに変わるワンタイムパスワード |
| WebAuthn / FIDO2 | [EN](webauthn.md) | [JA](webauthn.ja.md) | 指紋やセキュリティキーで認証（パスキー） |

### Cookie・セッション

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| [Cookie](cookie.md) | [EN](cookie.md) | [JA](cookie.ja.md) | ブラウザが覚えてくれる小さなデータ |
| [Http](http.md)Only | [EN](httponly.md) | [JA](httponly.ja.md) | [Java](java.md)Script から Cookie を読めなくするフラグ |
| SameSite | [EN](samesite.md) | [JA](samesite.ja.md) | 別サイトからの Cookie 送信を制御 (Lax/Strict/None) |
| [Session](session.md) | [EN](session.md) | [JA](session.ja.md) | 「この人はログイン済み」を覚えておく仕組み |
| [Sliding Window Expiry](sliding-window-expiry.md) | [EN](sliding-window-expiry.md) | [JA](sliding-window-expiry.ja.md) | アクティビティごとにリセットされるセッションタイムアウト |
| [Absolute Timeout](absolute-timeout.md) | [EN](absolute-timeout.md) | [JA](absolute-timeout.ja.md) | アクティビティに関係なく強制的にセッション切れ |
| [Concurrent Session Limit](concurrent-session-limit.md) | [EN](concurrent-session-limit.md) | [JA](concurrent-session-limit.ja.md) | ユーザーが持てる同時セッション数の制限 |
| [Session Storage Strategies](session-storage-strategies.md) | [EN](session-storage-strategies.md) | [JA](session-storage-strategies.ja.md) | インメモリ vs [Redis](redis.md) vs DB のセッション保存方式 |
| [Session Hijacking](session-hijacking.md) | [EN](session-hijacking.md) | [JA](session-hijacking.ja.md) | 有効なセッショントークンを盗む攻撃 |

### 暗号

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| 公開鍵暗号 | [EN](public-key-cryptography.md) | [JA](public-key-cryptography.ja.md) | 公開鍵と秘密鍵のペアで暗号化・署名 |
| [Digital Signature](digital-signature.md) | [EN](digital-signature.md) | [JA](digital-signature.ja.md) | データの真正性と完全性を証明する署名 |
| [Hash Function](hash-function.md) (SHA-256) | [EN](hash-function.md) | [JA](hash-function.ja.md) | 固定長ダイジェストを生成する一方向関数 |
| [Encryption](encryption.md) at Rest | [EN](encryption-at-rest.md) | [JA](encryption-at-rest.ja.md) | 保存データの暗号化（署名鍵やシークレット） |
| [Key Rotation](key-rotation.md) | [EN](key-rotation.md) | [JA](key-rotation.ja.md) | 暗号鍵を定期的に入れ替える運用 |

### アーキテクチャ

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| ForwardAuth | [EN](forwardauth.md) | [JA](forwardauth.ja.md) | リバースプロキシが「この人 OK？」と volta に聞く仕組み |
| IdP | [EN](idp.md) | [JA](idp.ja.md) | Identity Provider -- Google, [Okta](okta.md), [Keycloak](keycloak.md) 等 |
| OIDC プロバイダ | [EN](oidc-provider.md) | [JA](oidc-provider.ja.md) | Google、Okta、Keycloak 等の ID 「認証者」 |
| [Tenant](tenant.md) | [EN](tenant.md) | [JA](tenant.ja.md) | SaaS での「お客さんの区画」 |
| RBAC | [EN](rbac.md) | [JA](rbac.ja.md) | ロール（役割）で権限を制御 |
| [Client Credentials](client-credentials.md) | [EN](client-credentials.md) | [JA](client-credentials.ja.md) | サーバー同士の [M2M](m2m.md) 認証 (Phase 2) |
| [Backchannel Logout](backchannel-logout.md) | [EN](backchannel-logout.md) | [JA](backchannel-logout.ja.md) | サーバー間で「この人ログアウトした」を伝える |
| ゼロトラスト | [EN](zero-trust.md) | [JA](zero-trust.ja.md) | 「決して信頼せず、常に検証」セキュリティモデル |
| [IAM](iam.md) | [EN](iam.md) | [JA](iam.ja.md) | ID・アクセス管理 -- 包括的な用語 |
| IDaaS | [EN](idaas.md) | [JA](idaas.ja.md) | クラウドホスト型認証サービス（[Auth0](auth0.md)、Clerk、Okta） |

### マルチテナント

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Row-Level Security | [EN](row-level-security.md) | [JA](row-level-security.ja.md) | DB レベルでテナントデータを分離する仕組み |
| [Tenant Resolution](tenant-resolution.md) | [EN](tenant-resolution.md) | [JA](tenant-resolution.ja.md) | リクエストがどのテナントに属するか判定する方法 |
| [Tenant Lifecycle](tenant-lifecycle.md) | [EN](tenant-lifecycle.md) | [JA](tenant-lifecycle.ja.md) | テナントの作成・停止・削除の管理 |
| Cross-[Tenant](tenant.md) Access | [EN](cross-tenant-access.md) | [JA](cross-tenant-access.ja.md) | テナント境界を越えた制御されたアクセス |
| [Free Email Domains](free-email-domains.md) | [EN](free-email-domains.md) | [JA](free-email-domains.ja.md) | gmail.com 等のドメインベーステナント解決への対応 |

### HTTP キャッシュ

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Cache-Control | [EN](cache-control.md) | [JA](cache-control.ja.md) | キャッシュ動作を制御する HTTP ヘッダ |
| no-store vs no-cache | [EN](no-store-vs-no-cache.md) | [JA](no-store-vs-no-cache.ja.md) | 「絶対保存しない」vs「使う前に再検証」 |
| [private vs public](private-vs-public.md) | [EN](private-vs-public.md) | [JA](private-vs-public.ja.md) | ブラウザ限定キャッシュ vs CDN 共有キャッシュ |
| [Browser Back Button Cache](browser-back-button-cache.md) | [EN](browser-back-button-cache.md) | [JA](browser-back-button-cache.ja.md) | 戻るボタンで古い認証済みページが表示される問題 |
| Data Leakage via Cache | [EN](data-leakage-via-cache.md) | [JA](data-leakage-via-cache.ja.md) | 不適切なキャッシュによる機密データ漏洩 |

### 攻撃パターン

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| [Brute Force](brute-force.md) | [EN](brute-force.md) | [JA](brute-force.ja.md) | パスワード総当たり攻撃 |
| [Credential Stuffing](credential-stuffing.md) | [EN](credential-stuffing.md) | [JA](credential-stuffing.ja.md) | 他サービスから漏洩した認証情報の使い回し |
| [Replay Attack](replay-attack.md) | [EN](replay-attack.md) | [JA](replay-attack.ja.md) | 盗んだ正当なリクエスト/トークンを再送する攻撃 |
| [Open Redirect](open-redirect.md) | [EN](open-redirect.md) | [JA](open-redirect.ja.md) | 未チェックのリダイレクト [URL](url.md) を悪用するフィッシング |
| [Token Theft](token-theft.md) | [EN](token-theft.md) | [JA](token-theft.ja.md) | [JWT](jwt.md) やセッショントークンを盗む攻撃 |

### プロトコル概念

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Content-Type | [EN](content-type.md) | [JA](content-type.ja.md) | HTTP ボディのメディアタイプを宣言するヘッダ |
| [Pagination](pagination.md) | [EN](pagination.md) | [JA](pagination.ja.md) | 大量の結果をページに分割する方法 |
| [Idempotency](idempotency.md) | [EN](idempotency.md) | [JA](idempotency.ja.md) | 同じリクエストで同じ結果 -- 安全にリトライ可能 |
| [API](api.md) Versioning | [EN](api-versioning.md) | [JA](api-versioning.ja.md) | クライアントを壊さずに API を進化させる方法 |

### ビジネス・業界用語

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| [MAU](mau.md) | [EN](mau.md) | [JA](mau.ja.md) | 月間アクティブユーザー -- SaaS のユーザー数と課金の仕組み |
| ベンダーロックイン | [EN](vendor-lock-in.md) | [JA](vendor-lock-in.ja.md) | プロバイダの乗り換えが非現実的になること |
| セルフホスティング | [EN](self-hosting.md) | [JA](self-hosting.ja.md) | 自分のサーバーでソフトウェアを実行すること |
| [Auth0](auth0.md) | [EN](auth0.md) | [JA](auth0.ja.md) | 人気の IDaaS プロバイダ（volta との違い） |
| [Keycloak](keycloak.md) | [EN](keycloak.md) | [JA](keycloak.ja.md) | Red Hat のオープンソース [IAM](iam.md) サーバー |
| 設定地獄 | [EN](config-hell.md) | [JA](config-hell.ja.md) | ソフトウェアの設定が多すぎる問題 |

### volta 内部技術

| 用語 | EN | JA | 一言 |
|------|----|----|------|
| Fat JAR | [EN](fat-jar.md) | [JA](fat-jar.ja.md) | 全依存関係を含む単一実行可能 [Java](java.md) ファイル |
| [Flyway](flyway.md) | [EN](flyway.md) | [JA](flyway.ja.md) | DB マイグレーションツール -- 起動時に自動実行 |
| HikariCP | [EN](hikaricp.md) | [JA](hikaricp.ja.md) | DB コネクションプール（DB への「電話回線」） |
| Caffeine | [EN](caffeine-cache.md) | [JA](caffeine-cache.ja.md) | レート制限とセッションキャッシュ用インメモリキャッシュ |
| [jte](jte.md) | [EN](jte.md) | [JA](jte.ja.md) | Java テンプレートエンジン -- 型安全な HTML 生成 |

***

## volta-auth-proxy 固有の用語

| 用語 | 説明 |
|------|------|
| `X-Volta-*` ヘッダ | ForwardAuth で App に渡される identity 情報 |
| `volta_v` | [JWT](jwt.md) [claim](claim.md)s のスキーマバージョン |
| `volta_tid` | JWT 内のテナント ID |
| `volta_roles` | JWT 内のロール配列 |
| `volta-sdk-js` | ブラウザ用 [SDK](sdk.md) -- 401 自動リフレッシュ |
| `volta-sdk` | [Java](java.md) 用 SDK -- JWT 検証ミドルウェア |
| `volta-config.yaml` | App 登録ファイル |
| `VOLTA_SERVICE_TOKEN` | [M2M](m2m.md) 用静的トークン（Phase 1） |
