# Getting Started

[English](getting-started.md)

ローカルで **5 分** 立ち上げ、好きな IdP を繋ぐまでの手順。

> 会話形式のウォークスルーが欲しい → [getting-started-dialogue.ja.md](getting-started-dialogue.ja.md)
> アーキテクチャ内部が見たい → [architecture-ja.md](architecture-ja.md)

---

## 目次

- [前提](#前提)
- [5 分クイックスタート](#5-分クイックスタート)
- [設定](#設定)
  - [環境変数](#環境変数)
  - [`volta-config.yaml`](#volta-configyaml)
- [ForwardAuth 接続](#forwardauth-接続)
  - [構成 A: Traefik](#構成-a-traefik)
  - [構成 B: volta-gateway（組み込み）](#構成-b-volta-gateway組み込み)
  - [構成 C: Nginx `auth_request`](#構成-c-nginx-auth_request)
- [IdP 設定例](#idp-設定例)
  - [Google (OIDC)](#google-oidc)
  - [Microsoft Entra (OIDC)](#microsoft-entra-oidc)
  - [Okta (SAML)](#okta-saml)
  - [汎用 SAML IdP](#汎用-saml-idp)
- [Passkey 有効化](#passkey-有効化)
- [MFA (TOTP) 有効化](#mfa-totp-有効化)
- [動作確認](#動作確認)
- [次に読むもの](#次に読むもの)

---

## 前提

| ツール | 最低バージョン | 用途 |
|-------|---------------|------|
| Java | 21 LTS | Javalin + tramli ランタイム |
| Maven | 3.9 | ビルド |
| Docker / Compose | 24.x | ローカル開発用 Postgres |
| `jq`, `curl` | 任意 | トラブルシュート |

---

## 5 分クイックスタート

```bash
git clone https://github.com/opaopa6969/volta-auth-proxy
cd volta-auth-proxy

# 1. Postgres 起動
docker-compose up -d

# 2. 最小限の env（初回は Google OIDC）
cat > .env <<'EOF'
BASE_URL=http://localhost:7070
DATABASE_URL=jdbc:postgresql://localhost:5432/volta
DATABASE_USER=volta
DATABASE_PASSWORD=volta
SESSION_COOKIE_NAME=__volta_session
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
DEV_MODE=true
EOF

# 3. ビルド + 起動
mvn -q -DskipTests package
java -jar target/volta-auth-proxy-*-shaded.jar

# 4. ログインページを開く
open http://localhost:7070/login
```

成功するとログイン UI が表示され、「Google でログイン」→ OAuth → `/` に戻り
`__volta_session` Cookie が付与される。

---

## 設定

### 環境変数

全リストは `.env.example` 参照。主なものだけ:

| 変数 | 用途 | デフォルト |
|-----|------|-----------|
| `BASE_URL` | `Secure` Cookie 推定 + redirect URI 生成 | —（必須） |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | Postgres | —（必須） |
| `SESSION_COOKIE_NAME` | Cookie 名 | `__volta_session` |
| `SESSION_TTL_MINUTES` | アイドルタイムアウト | `60` |
| `SESSION_ABSOLUTE_TTL_HOURS` | 上限 | `24` |
| `MFA_ENABLED` | 機能フラグ | `false` |
| `PASSKEY_ENABLED` | 機能フラグ | `false` |
| `LOCAL_BYPASS_CIDRS` | ADR-003 LAN bypass（カンマ区切り、`""` で無効化） | `192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10,127.0.0.1/32` |
| `DEV_MODE` | localhost 限定の開発便利機能 | `false` |
| `WEBHOOK_ENABLED` | Outbox worker | `false` |

### `volta-config.yaml`

Tenancy / access / binding 3 レイヤ構成（schema v3）。`volta-config.example.yaml` を起点に:

```yaml
tenancy:
  mode: multi            # single | multi
  resolver: subdomain    # subdomain | header | path
access:
  default_role: MEMBER
  admin_emails:
    - admin@example.com
binding:
  apps:
    - host: console.example.com
      auth: required
      headers: [X-Volta-User-Id, X-Volta-Tenant-Id, X-Volta-Role]
    - host: public.example.com
      auth: anonymous
```

---

## ForwardAuth 接続

### 構成 A: Traefik

```yaml
# docker-compose.yml (抜粋)
services:
  traefik:
    image: traefik:v3
    command:
      - --providers.docker=true
      - --entrypoints.web.address=:80
    labels:
      - traefik.http.middlewares.volta.forwardauth.address=http://volta:7070/auth/verify
      - traefik.http.middlewares.volta.forwardauth.authResponseHeaders=X-Volta-User-Id,X-Volta-Tenant-Id,X-Volta-Role,X-Volta-Email

  app:
    image: your/app
    labels:
      - traefik.http.routers.app.rule=Host(`console.example.com`)
      - traefik.http.routers.app.middlewares=volta
```

### 構成 B: volta-gateway（組み込み）

[volta-gateway](https://github.com/opaopa6969/volta-gateway) は Rust 製リバース
プロキシで volta-auth-proxy 互換の認証サーバを同梱する。`upstream_url` を
アプリに向ければ Traefik 不要。

### 構成 C: Nginx `auth_request`

```nginx
location = /auth/verify {
    internal;
    proxy_pass http://volta:7070/auth/verify;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Uri $request_uri;
    proxy_set_header X-Forwarded-Proto $scheme;
}
location / {
    auth_request /auth/verify;
    auth_request_set $user_id  $upstream_http_x_volta_user_id;
    auth_request_set $tenant_id $upstream_http_x_volta_tenant_id;
    proxy_set_header X-Volta-User-Id  $user_id;
    proxy_set_header X-Volta-Tenant-Id $tenant_id;
    proxy_pass http://app:8080;
    error_page 401 = @login;
}
location @login { return 302 /login?return_to=$scheme://$host$request_uri; }
```

nginx 背面時の `/auth/*` ルーティング修正は `afb6eab` 参照。

---

## IdP 設定例

### Google (OIDC)

1. <https://console.cloud.google.com/> → API とサービス → 認証情報
2. **OAuth 2.0 クライアント ID** → ウェブアプリケーション
3. 承認済みリダイレクト URI: `https://auth.example.com/auth/callback`
4. `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` を保存

### Microsoft Entra (OIDC)

1. Entra ポータル → アプリ登録 → 新規登録
2. リダイレクト URI（Web）: `https://auth.example.com/auth/callback`
3. 証明書とシークレット → 新しいクライアントシークレット
4. env: `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, `MICROSOFT_TENANT_ID`

### Okta (SAML)

1. Okta 管理画面 → Applications → Create App Integration → SAML 2.0
2. **Single sign-on URL (ACS)**: `https://auth.example.com/auth/saml/callback`
3. **Audience URI (SP Entity ID)**: `volta-sp-audience`
4. Name ID 形式: `EmailAddress`
5. Okta metadata XML をエクスポート → volta admin に登録:

```bash
curl -X POST https://auth.example.com/admin/idp \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "kind=SAML" \
  -F "issuer=http://www.okta.com/exk..." \
  -F "audience=volta-sp-audience" \
  -F "x509Cert=@okta.cer"
```

volta は全アサーションに対し **XXE / XSW** 対策を強制する ——
[auth-flows-ja.md](auth-flows-ja.md#saml-xswxxe-テストカバー状況) 参照。

### 汎用 SAML IdP

任意の SAML 2.0 IdP は `POST /auth/saml/callback` に `AuthnResponse` を
POST できれば動く。必須項目:

- `<saml:Issuer>` = 登録済み `issuer` と一致
- `<saml:AudienceRestriction>` = `audience` と一致
- `<saml:Subject><saml:NameID>` にメールアドレス
- Assertion もしくは Response に単一の `<ds:Signature>`
- `<saml:SubjectConfirmationData NotOnOrAfter="...">` は 5 分以内

---

## Passkey 有効化

```bash
PASSKEY_ENABLED=true
PASSKEY_RP_ID=auth.example.com      # ブラウザがクレデンシャルを結びつける RP ID
PASSKEY_RP_NAME="Example Inc."
```

`POST /auth/passkey/register/start` → ブラウザ WebAuthn セレモニー →
`POST /auth/passkey/register/finish` でクレデンシャル登録。オーセンティケータ
種別は登録時に選択可能（`0d17ce6`）。

## MFA (TOTP) 有効化

```bash
MFA_ENABLED=true
MFA_ISSUER="Example Inc."
```

`POST /auth/mfa/setup` で QR 発行、`POST /auth/mfa/verify` で確認。MFA フローは
4 状態の tramli FlowDefinition ——
[architecture-ja.md](architecture-ja.md#mfa-flow-tramli) 参照。

> **ADR-004**: MFA は *テナントスコープ*。テナント切替で再検証が走る。

---

## 動作確認

```bash
# 1. ヘルスチェック
curl -s http://localhost:7070/health | jq .

# 2. ForwardAuth（未認証 → 302）
curl -i -H "X-Forwarded-Host: app.example.com" \
        -H "X-Forwarded-Uri: /"               \
        http://localhost:7070/auth/verify | head -5

# 3. ForwardAuth（認証済み → 200 + ヘッダ）
curl -i -H "Cookie: __volta_session=..." \
        http://localhost:7070/auth/verify
```

(3) の期待出力:

```
HTTP/1.1 200 OK
X-Volta-User-Id:   abc123
X-Volta-Tenant-Id: t456
X-Volta-Role:      MEMBER
X-Volta-Email:     user@example.com
```

---

## 次に読むもの

- [architecture-ja.md](architecture-ja.md) —— 二層 Session SM + tramli Flow SM 群
- [auth-flows-ja.md](auth-flows-ja.md) —— OIDC / SAML / MFA / Passkey シーケンス + テストカバレッジ
- [decisions/](decisions/) —— バイパス/MFA スコープ等の ADR
- [CHANGELOG.md](../CHANGELOG.md) —— リリースノート
