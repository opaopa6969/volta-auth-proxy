# Open Questions / Assumptions — Answered

Date: 2026-04-01
Source: docs/open-questions-and-assumptions.md
Answered based on DGE design sessions (106 gaps resolved) + DSL v3.2 (10/10 tribunal score)

---

## Open Questions — Answers

### 1. `users.google_sub` の扱い

**Q:** 今後も IdP 非依存の一意キーとして使い続ける前提で良いか？

**A: No. Phase 2 で `user_identities` テーブルに分離する。**

```
Phase 1 (現状):
  users.google_sub — Google 固有。Phase 1 は Google のみなので OK

Phase 2 (計画済み):
  users テーブルから google_sub を削除
  user_identities テーブルに分離:
    user_identities (
      id          UUID PRIMARY KEY,
      user_id     UUID REFERENCES users(id),
      provider    VARCHAR(30),     -- google, github, microsoft, saml
      provider_sub VARCHAR(255),
      UNIQUE(provider, provider_sub)
    )

  → email 一致で自動リンク（同じ email の複数 provider を同一 user に紐付け）
  → google_sub 不一致問題は構造的に解消される
```

DSL 参照: `dsl/auth-machine-phase2-4.yaml` の `user_identities` テーブル定義 + `upsert_user_identity` アクション

**今すぐ必要なアクション:** Phase 1 では `google_sub` のまま。Phase 2 マイグレーションで `V4__user_identities.sql` を追加。

---

### 2. SAML 本番要件

**Q:** 本番では XML Signature / 証明書検証（X509）を必須にする方針で確定か？

**A: Yes. 署名検証なしの SAML は本番利用不可。**

```
方針:
  - 開発/テスト: 署名検証をスキップ可能（環境変数 SAML_SKIP_SIGNATURE=true）
  - 本番: 署名検証必須。SAML_SKIP_SIGNATURE=false（デフォルト）
  - IdP の X509 証明書は saml_idp_configs テーブルに保存
  - 証明書のローテーション: 管理画面で更新可能

実装ライブラリ:
  onelogin/java-saml or pac4j (Phase 3)
  → XML 署名検証は自前で書かない。ライブラリに任せる

Assumption #2 確定: 「署名検証のない SAML 認証は本番利用不可」
```

DSL 参照: `dsl/auth-machine-phase2-4.yaml` の `SAML_PENDING` 状態 + `saml_idp_configs` テーブル

---

### 3. OAuth token endpoint の CSRF 方針

**Q:** `POST /oauth/token` を機械間アクセス前提で CSRF 対象外にする方針で良いか？

**A: Yes. M2M エンドポイントは CSRF 対象外。**

```
理由:
  1. /oauth/token は Client Credentials grant 専用（ブラウザは使わない）
  2. Content-Type: application/x-www-form-urlencoded は OAuth 仕様準拠
  3. 認証は client_id + client_secret で行う（CSRF token は不要）

実装:
  CSRF ミドルウェアの除外リスト:
    - /oauth/token           ← M2M
    - /api/v1/*              ← JSON API（Accept: application/json で免除）
    - /.well-known/jwks.json ← 公開エンドポイント
    - /healthz               ← ヘルスチェック

  CSRF 検証対象（HTML フォーム POST のみ）:
    - POST /invite/{code}/accept
    - POST /auth/logout（ブラウザ版）
    - POST /auth/switch-tenant（テナント選択画面のフォーム）
```

DSL 参照: `dsl/policy.yaml` の csrf セクション + `dsl/auth-machine-phase2-4.yaml` の `m2m_token_issue`

---

### 4. RelayState `return_to` の再検証

**Q:** `/auth/saml/callback` 側でも `return_to` を `ALLOWED_REDIRECT_DOMAINS` で再検証する方針で良いか？

**A: Yes. callback では必ず再検証する。**

```
理由:
  1. callback は外部 IdP からのリダイレクト。攻撃者が RelayState を改ざんする可能性
  2. login 時に検証済みでも、callback は別のリクエスト。検証を再実行するコストは無視できる
  3. Open Redirect 防止は多層防御（Defense in Depth）

実装:
  /auth/saml/callback handler:
    1. SAML Response を検証（署名、issuer、audience、expiry）
    2. RelayState から return_to を取得
    3. isAllowedReturnTo(return_to) で ALLOWED_REDIRECT_DOMAINS チェック
    4. NG → return_to を無視して config.default_app_url にリダイレクト

  /callback (OIDC) でも同様:
    → 現在の実装で return_to は session に保存されるため
       callback 直叩きでは return_to が存在しない → デフォルトに fallback
    → session 経由なら検証済みの値が使われる
    → 念のため callback でも再検証する方針（多層防御）
```

DSL 参照: `dsl/auth-machine.yaml` の `callback_success` + `dsl/protocol.yaml` の `content_negotiation`
用語集参照: `docs/glossary/open-redirect.md` — Open Redirect 攻撃の解説

---

## Assumptions — Confirmation

### Assumption 1: MFA シークレット保存

**確定: 暗号化 at-rest 必須。**

```
実装方針:
  totp_credentials.secret → AES-256-GCM 暗号化
  暗号化キー: JWT_KEY_ENCRYPTION_SECRET を共有（or MFA 専用キーを追加）
  既存の KeyCipher クラスを再利用

Phase 3 で実装時に:
  ALTER TABLE totp_credentials ADD COLUMN secret TEXT NOT NULL;
  → secret は KeyCipher.encrypt(rawSecret) で暗号化して保存
  → 復号は KeyCipher.decrypt(encryptedSecret)
```

用語集参照: `docs/glossary/encryption-at-rest.md`

---

### Assumption 2: セキュリティ優先順位

**確定: 署名検証なし SAML は本番利用不可。**

Open Question #2 の回答と同じ。環境変数 `SAML_SKIP_SIGNATURE` で開発/本番を切り替え。

---

### Assumption 3: Phase 2+ 複数 IdP

**確定: `user_identities` テーブルが正。**

```
Phase 2 マイグレーション計画:
  1. V4__user_identities.sql で新テーブル作成
  2. 既存 users.google_sub → user_identities に移行
     INSERT INTO user_identities (user_id, provider, provider_sub)
     SELECT id, 'google', google_sub FROM users WHERE google_sub IS NOT NULL;
  3. users.google_sub カラムを nullable に変更（互換性維持）
  4. Phase 3 で users.google_sub カラムを DROP
```

DSL 参照: `dsl/auth-machine-phase2-4.yaml` の `user_identities` テーブル + account linking フロー

---

## Summary

| # | Question/Assumption | Answer | Action |
|---|-------------------|--------|--------|
| Q1 | google_sub の扱い | Phase 2 で user_identities に分離 | Phase 1 は現状維持 |
| Q2 | SAML 署名検証 | 本番必須。開発は環境変数でスキップ可 | Phase 3 で実装 |
| Q3 | /oauth/token の CSRF | 対象外（M2M エンドポイント） | CSRF 除外リストに追加 |
| Q4 | callback の return_to 再検証 | 必ず再検証（多層防御） | OIDC/SAML 両方で実装 |
| A1 | MFA シークレット暗号化 | AES-256-GCM（KeyCipher 再利用） | Phase 3 で実装 |
| A2 | 署名なし SAML 禁止 | 確定 | Q2 と同じ |
| A3 | user_identities が正 | 確定。Phase 2 でマイグレーション | V4 マイグレーション準備 |
