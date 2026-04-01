# Open Questions / Assumptions

Date: 2026-04-01
Target: volta-auth-proxy implementation review

## Open Questions

1. `users.google_sub` の扱い
- 今後も IdP 非依存の一意キーとして使い続ける前提で良いか？
- 現状は Google/OIDC/SAML 混在時に email 一致で `google_sub` 不一致が起きると `upsertUser` が破綻しやすい。

2. SAML 本番要件
- 本番では XML Signature / 証明書検証（X509）を必須にする方針で確定か？
- 現実装は issuer / audience / expiry などの値検証中心で、署名検証までは未実装。

3. OAuth token endpoint の CSRF 方針
- `POST /oauth/token` を機械間アクセス前提で CSRF 対象外にする方針で良いか？
- 現在のグローバル CSRF ミドルウェアでは `application/x-www-form-urlencoded` 呼び出しが弾かれる可能性がある。

4. RelayState `return_to` の再検証
- `/auth/saml/callback` 側でも `return_to` を `ALLOWED_REDIRECT_DOMAINS` で再検証する方針で良いか？
- login 経由でなく callback 直叩きされるケースを想定すると再検証が必要。

## Assumptions

1. MFA シークレット保存
- `user_mfa.secret` は平文ではなく暗号化 at-rest が必要。

2. セキュリティ優先順位
- 署名検証の無い SAML 認証は本番利用不可として扱う。

3. Phase2+ 複数 IdP
- 将来的には `user_identities` を正として provider-sub を管理し、`users` から provider 固有キー依存を外す。
