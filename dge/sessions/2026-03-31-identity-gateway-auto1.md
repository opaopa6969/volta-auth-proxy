# DGE Auto-iteration 1/5 — Phase 1 C/H Gap 解決
- **Date**: 2026-03-31
- **Flow**: quick (auto-iterate 1/5)
- **Theme**: 残存 18 件の C/H Gap に具体的解決策を当てる
- **Architecture**: Gateway (Javalin) + Postgres + nimbus-jose-jwt, Google OIDC direct

---

## 解決策一覧

### 1. JWT 鍵管理 → ✅
- RS256 (RSA 2048bit), JWK 形式
- signing_keys テーブルで管理 (id, kid, public_key, private_key, status, created_at, rotated_at, expires_at)
- status: active → rotated → revoked
- JWKS に active + rotated を公開（猶予期間）
- Phase 1 は単一鍵、ローテーション実装は Phase 2

### 2. フリーメール tenant 誤マッチ → ✅
- フリーメールドメインリスト（設定）で email domain 解決を除外
- tenant_domains テーブル (tenant_id, domain, verified)
- 解決優先順位: Cookie/JWT > URL サブドメイン > email domain > 招待/選択

### 3. App 直接アクセス防止 → ✅
- Layer 1: docker network で App ポート非公開
- Layer 2: SDK で JWT RS256 署名検証必須

### 4. トークン失効 → ✅
- JWT exp: 5 分 / Cookie session: 30 分 (sliding)
- sessions テーブル (id, user_id, tenant_id, created_at, last_active_at, expires_at, invalidated_at)
- 即時無効化: invalidated_at 更新 → 次回 JWT 再取得で弾く（最大 5 分ラグ）

### 5. セッション管理 → ✅
- Cookie: __volta_session, Secure; HttpOnly; SameSite=Lax
- セッション固定攻撃: ログイン成功時に ID 再生成
- 同時ログイン: Phase 1 は許可

### 6. 招待コード → ✅
- crypto random 32 bytes → base64url (43文字)
- invitations テーブル (id, tenant_id, code, email, role, created_by, created_at, expires_at, consumed_at, consumed_by)
- デフォルト有効期限 7 日、1 回使用
- Referrer-Policy: no-referrer

### 7. JWT claims schema バージョニング → ✅
- "v": 1 フィールドで schema version
- additive only（追加のみ、削除・変更しない）
- 削除時は deprecation 期間 30 日

### 8. JWT PII ポリシー → ✅
- JWT に載せる: sub, email, tid, roles, v
- JWT に載せない: 氏名, 電話, 住所, カスタム属性
- 追加情報: GET /api/users/{id}/attributes

### 9. ログアウト伝播 → ✅
- JWT 短寿命 (5分) で吸収
- セッション Cookie 削除 + sessions.invalidated_at 更新
- backchannel logout 不要

### 10. 監査ログ → ✅
- Phase 1 イベント: login, logout, signup, invitation, user/tenant 操作
- audit_logs テーブル (id, timestamp, event_type, user_id, tenant_id, ip_address, user_agent, details JSONB)
- stdout 構造化 JSON + DB 保存

### 11. レート制限 → ✅
- Traefik Rate Limit middleware
- login: 10/min/IP, signup: 5/min/IP, invite: 20/min/tenant, API: 100/min/user

### 12. Google OIDC セキュリティ → ✅
- state (CSRF), nonce (リプレイ), PKCE (S256)
- redirect_uri 厳密一致, id_token 署名検証
- iss/aud/exp/email_verified 検証

### 13. 自前 JWT セキュリティ → ✅
- 発行: RS256固定, kid必須, exp 5分, iss/aud/iat
- 検証: alg ホワイトリスト(RS256のみ), kid で鍵選択, JWKS 5分キャッシュ
- alg:none/HS256 絶対拒否

### 14. App SDK → ✅
- VoltaAuth(jwksUrl) / verify(token) / middleware()
- VoltaContext: userId, tenantId, email, roles, hasRole()
- Javalin middleware として提供

### 15. エラー契約 → ✅
- JSON: { "error": { "code": "AUTH_*", "message": "...", "status": N } }
- 401: TOKEN_MISSING/EXPIRED/INVALID
- 403: FORBIDDEN/TENANT_SUSPENDED/ROLE_INSUFFICIENT
- Content negotiation: JSON or HTML redirect

### 16. テナント作成フロー → ✅
- Phase 1: managed (platform admin が POST /admin/tenants)
- self-service は Phase 3 以降

### 17. マルチテナント membership → ✅
- 1 ユーザー = 複数テナント可
- memberships テーブル (user_id, tenant_id, role, joined_at, suspended)
- JWT には現在の tenant_id 1 つ、切替は POST /auth/switch-tenant

### 18. Phase 1 拡張性 → ✅
- Interface で拡張点: AuthProvider, SessionStore, TokenIssuer
- Phase 1: Google, Postgres, Nimbus
- Phase 2+: 差し替え可能

---

## C/H Gap 残存数: 0

**全 18 件に具体的解決策を当てた。Critical/High = 0。**
