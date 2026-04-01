# Traefik なしで動かす

[English](no-traefik-guide.md) | [日本語](no-traefik-guide.ja.md)

> [Traefik](glossary/traefik.md) は推奨だが必須ではない。volta は[リバースプロキシ](glossary/reverse-proxy.ja.md)なしで動く。

***

## 3 つのデプロイパターン

### パターン A: volta のみ（最シンプル）

```
ブラウザ
  ↓
volta-auth-proxy（ポート 7070）
  ├── /login, /invite, /admin    → volta が HTML を返す
  ├── /api/v1/*                  → Internal API
  └── /.well-known/jwks.json     → JWKS

あなたのアプリ（ポート 8080）
  ├── /api/*                     → ビジネス API
  └── /                          → フロントエンド
```

**仕組み:**

- volta が認証ページを直接提供
- アプリは別ポートで動く
- フロントエンドが volta（認証）とアプリ（データ）の 2 つの [API](glossary/api.md) を叩く
- [リバースプロキシ](glossary/reverse-proxy.ja.md)不要

**アプリは [JWT](glossary/jwt.md) を直接[検証](glossary/verification.ja.md)**（[ForwardAuth](glossary/forwardauth.ja.md) ヘッダなし）:

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());
```

**向いてるケース:** 開発、プロトタイプ、単一アプリ

***

### パターン B: Traefik（本番推奨）

メインの [README](../README.ja.md) で説明しているパターン。

**向いてるケース:** 本番、複数アプリ、[サブドメイン](glossary/subdomain.ja.md)[ルーティング](glossary/routing.ja.md)

***

### パターン C: nginx / Caddy / 任意のリバースプロキシ

[ForwardAuth](glossary/forwardauth.ja.md) は [Traefik](glossary/traefik.md) 固有ではない。auth\_request ([nginx](glossary/nginx.md)) や forward\_auth ([Caddy](glossary/caddy.md)) でも同じ。

[nginx](glossary/nginx.md) と [Caddy](glossary/caddy.md) の設定例は[英語版](no-traefik-guide.md)を参照。

***

## 比較表

| | パターン A (プロキシなし) | パターン B ([Traefik](glossary/traefik.md)) | パターン C ([nginx](glossary/nginx.md)/[Caddy](glossary/caddy.md)) |
|---|---|---|---|
| **追加インフラ** | なし | [Traefik](glossary/traefik.ja.md) | [nginx](glossary/nginx.ja.md) or [Caddy](glossary/caddy.ja.md) |
| **セットアップ** | 最も簡単 | 中程度 | 中程度 |
| **認証方法** | [JWT](glossary/jwt.md) [検証](glossary/verification.ja.md) | [ForwardAuth](glossary/forwardauth.ja.md) ヘッダ | ForwardAuth ヘッダ |
| **[CORS](glossary/cors.ja.md)** | 必要 | 不要 | 不要 |
| **複数アプリ** | [JWT](glossary/jwt.ja.md) 手動 | ヘッダ自動 | ヘッダ自動 |
| **[サブドメイン](glossary/subdomain.ja.md)** | なし | あり | あり |
| **本番向き** | 開発/小規模 | はい | はい |
