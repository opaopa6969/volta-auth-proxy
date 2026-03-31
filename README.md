# volta-auth-proxy (Phase 1)

Javalin 6.x + jte 3.x + nimbus-jose-jwt + Postgres の Phase 1 実装です。  
Google OIDC / Session / JWT / JWKS / ForwardAuth / 招待 / 内部 API の土台を含みます。

セキュリティ:
- 署名鍵の DB 保存は AES-256-GCM で暗号化（`JWT_KEY_ENCRYPTION_SECRET`）
- 監査ログ（`audit_logs`）を主要操作で記録
- 同時ログイン上限 5（超過時は最古セッションから失効）

## 起動前提

- Java 21
- Maven 3.9+
- Postgres 16+（ローカルまたは Docker）

## 環境変数

`.env.example` を参照してください。最低限は以下です。

- `PORT`
- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`
- `JWT_KEY_ENCRYPTION_SECRET`
- `VOLTA_SERVICE_TOKEN`
- `APP_CONFIG_PATH`

## ローカル実行

```bash
docker compose up -d postgres
mvn test
mvn exec:java
```

## DB migration

Flyway をアプリ起動時に自動実行します。

- migration: `src/main/resources/db/migration/V1__init.sql`
- migration: `src/main/resources/db/migration/V2__oidc_flows.sql`
- テーブル: users / tenants / tenant_domains / memberships / sessions / signing_keys / invitations / invitation_usages / audit_logs

## Docker Postgres

```bash
docker compose up -d postgres
```

この構成は `54329 -> 5432` を使います。

## 主要ルート

- `GET /login` Google OIDC 開始
- `GET /callback` OIDC コールバック
- `POST /auth/callback/complete` コールバック検証完了 API（interstitial 画面から呼び出し）
- `POST /auth/refresh` セッションから JWT 再発行
- `POST /auth/logout` ログアウト
- `GET /.well-known/jwks.json` JWKS
- `GET /auth/verify` ForwardAuth エンドポイント（`X-Volta-*` ヘッダ返却）
- `GET /invite/{code}` / `POST /invite/{code}/accept` 招待フロー
- `GET /settings/sessions` / `DELETE /auth/sessions/{id}` / `POST /auth/sessions/revoke-all`
- `GET /api/me/sessions` / `DELETE /api/me/sessions/{id}` / `DELETE /api/me/sessions`
- `POST /dev/token` (`DEV_MODE=true` かつ localhost のみ)
- `GET|PATCH /api/v1/*` 内部 API（Rate limit: 200/min）
- `GET /api/v1/admin/keys` / `POST /api/v1/admin/keys/rotate` / `POST /api/v1/admin/keys/{kid}/revoke`（OWNER 限定）
- `GET /admin/members` / `GET /admin/invitations` 管理画面テンプレート
- `GET /api/v1/users/me/tenants` 所属テナント一覧
- `DELETE /api/v1/tenants/{tenantId}/members/{memberId}` メンバー削除
- `DELETE /api/v1/tenants/{tenantId}/invitations/{invitationId}` 招待取消

## App 登録

`volta-config.yaml` にアプリを定義し、ForwardAuth (`/auth/verify`) で `allowed_roles` を強制します。

```yaml
apps:
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

Traefik から `X-Volta-App-Id` か `X-Forwarded-Host` を渡すと判定されます。

## CSRF

- HTML form の `POST/DELETE/PATCH` は `_csrf` hidden フィールドを検証
- JSON API（`Accept: application/json` または `X-Requested-With: XMLHttpRequest`）は検証をスキップ
