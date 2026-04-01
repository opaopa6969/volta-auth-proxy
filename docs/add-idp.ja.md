# IdP（アイデンティティプロバイダー）の追加方法

[English](add-idp.md) | [日本語](add-idp.ja.md)

volta-auth-proxy は複数の OAuth2/OIDC プロバイダーをそのまま使えます。
各プロバイダーは環境変数の設定だけで有効化できます。コード変更は不要です。

---

## 対応プロバイダー

| プロバイダー | 種類 | ENV プレフィックス | 用途 |
|------------|------|-----------------|------|
| Google | OIDC + PKCE | `GOOGLE_` | デフォルト。コンシューマー向け SaaS に推奨 |
| GitHub | OAuth2 | `GITHUB_` | 開発者向けツールに推奨 |
| Microsoft / Azure AD | OIDC + PKCE | `MICROSOFT_` | 企業向け・B2B に推奨 |
| SAML（テナント別） | SAML 2.0 | Admin API | テナントごとの SSO — [SAML セットアップ](#saml-テナント別) 参照 |

複数プロバイダーを同時に有効化できます。ログインページには有効なプロバイダーのボタンが表示されます。

---

## Google（デフォルト）

1. [Google Cloud Console](https://console.cloud.google.com/) → APIs & Services → 認証情報
2. OAuth 2.0 クライアント ID を作成（ウェブアプリケーション）
3. 承認済みリダイレクト URI: `https://auth.example.com/callback`

```env
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret
GOOGLE_REDIRECT_URI=https://auth.example.com/callback
```

---

## GitHub

1. GitHub → Settings → Developer settings → OAuth Apps → New OAuth App
2. Authorization callback URL: `https://auth.example.com/callback`

```env
GITHUB_CLIENT_ID=your-github-client-id
GITHUB_CLIENT_SECRET=your-github-client-secret
```

**注意:** GitHub でメールアドレスを非公開設定にしているユーザーは、GitHubプロフィールでパブリックメールを設定するか、volta が自動でリクエストする `user:email` スコープを許可する必要があります。

---

## Microsoft / Azure AD

### オプション A: 全 Microsoft アカウント（個人 + 法人）

1. [Azure Portal](https://portal.azure.com/) → Microsoft Entra ID → アプリの登録 → 新規登録
2. サポートされるアカウントの種類: **任意の組織ディレクトリのアカウントと個人の Microsoft アカウント**
3. リダイレクト URI: `https://auth.example.com/callback`
4. 登録後: 証明書とシークレット → 新しいクライアントシークレット

```env
MICROSOFT_CLIENT_ID=your-application-client-id
MICROSOFT_CLIENT_SECRET=your-client-secret
MICROSOFT_TENANT_ID=common
```

### オプション B: 特定の Azure AD テナントのみ（法人アカウント限定）

`common` の代わりにテナント ID を指定:

```env
MICROSOFT_CLIENT_ID=your-application-client-id
MICROSOFT_CLIENT_SECRET=your-client-secret
MICROSOFT_TENANT_ID=your-tenant-id-or-domain.onmicrosoft.com
```

**注意:** `MICROSOFT_TENANT_ID=common` の場合、任意の Microsoft テナントのトークンを受け入れます。自社組織のみに制限するには、具体的なテナント ID を設定してください。

---

## 複数プロバイダーの同時有効化

関連する ENV をすべて設定するだけです。クライアント ID が空でない場合、volta は自動的にそのプロバイダーを有効化します。

```env
# 3つ全て有効 — ログインページにボタンが3つ表示される
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GITHUB_CLIENT_ID=...
GITHUB_CLIENT_SECRET=...
MICROSOFT_CLIENT_ID=...
MICROSOFT_CLIENT_SECRET=...
```

---

## SAML（テナント別）

SAML は ENV ではなく Admin API でテナントごとに設定します。
各テナントが自社の IdP（Okta、Azure AD SAML 等）を持ち込めます。

```bash
# テナントに SAML IdP を登録
curl -X POST https://auth.example.com/api/v1/admin/idp \
  -H "Authorization: Bearer volta-service:$VOLTA_SERVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tenant_id": "your-tenant-uuid",
    "provider_type": "SAML",
    "metadata_url": "https://your-idp.com/saml/metadata",
    "issuer": "https://your-idp.com"
  }'
```

そのテナントのユーザーは `/auth/saml/login?tenant_id=<uuid>` から企業 IdP でログインできます。

---

## 新しいプロバイダーの追加（コントリビューター向け）

上記にないプロバイダー（GitLab、Slack、Apple 等）を追加するには:

1. **`OidcService.java`** — `createAuthorizationUrl()` と `exchangeAndValidate()` に `case` を追加
   - OIDC プロバイダー: Microsoft パターンを参考に（`verifyIdToken` + JWKS URL）
   - OAuth2 のみのプロバイダー: GitHub パターンを参考に（アクセストークン → ユーザー情報 API）

2. **`AppConfig.java`** — `fooClientId`、`fooClientSecret`、`isFooEnabled()` フィールドを追加

3. **DB マイグレーション** — 不要（プロバイダーは `oidc_flows.provider` の文字列として保存）

4. **`login.jte`** — `@param boolean fooEnabled` とボタンを追加

5. **`.env.example`** — 新しい ENV 変数をドキュメント化

6. **このファイル** — セットアップセクションを追加

各プロバイダーが返すべき値:

```java
new OidcIdentity(
    "<provider>:<unique-id>",  // sub — users テーブルの provider_sub として保存
    email,                     // 検証済みメールアドレス
    displayName,               // ユーザーの表示名
    true,                      // emailVerified
    flow.returnTo(),
    flow.inviteCode(),
    "PROVIDER_NAME"            // 大文字。監査ログに記録される
)
```

---

## トラブルシューティング

| エラー | 原因 | 対処 |
|--------|------|------|
| `No IdP configured` | すべてのクライアント ID が空 | いずれかの `*_CLIENT_ID` を設定 |
| `GitHub account has no verified email` | GitHub のメールが非公開 | GitHub プロフィールでパブリックメールを設定 |
| `Invalid Microsoft issuer` | 予期しないテナントのトークン | `MICROSOFT_TENANT_ID` を確認 |
| `Invalid nonce` | state の期限切れ（10分超）または再利用 | ユーザーがログインをやり直す |
| `SAML IDP_NOT_FOUND` | テナントに SAML 設定なし | Admin API で先に登録 |
