# DGE Session — App ↔ Proxy Protocol 設計
- **Date**: 2026-03-31
- **Flow**: quick
- **Structure**: roundtable
- **Theme**: volta-auth-proxy と App 間の通信契約（protocol）
- **Characters**: ヤン, Auth 専門家, リヴァイ, SaaS 専門家

---

## アーキテクチャ判断: ForwardAuth パターン採用

```
ブラウザ → Traefik → App
               ↓ (ForwardAuth)
           volta-auth-proxy (認証チェックのみ)
               ↓
           X-Volta-* ヘッダで identity context を返す
               ↓
           Traefik が App に転送
```

Proxy はリクエストボディを中継しない。認証チェックだけ。

## volta-auth-proxy の 4 層構造

```
[UI 層]         login / signup / tenant管理 / member管理 / 招待 / セッション
[Auth 層]       Google OIDC / セッション / JWT 発行
[ForwardAuth]   GET /auth/verify → X-Volta-* ヘッダ返却
[Internal API]  /api/v1/* → App が CRUD を移譲するための API
```

## 4 方向通信

```
方向 1: Proxy → App（ForwardAuth ヘッダ） — Phase 1 必須
方向 2: App → Proxy（Internal API 呼び出し） — Phase 1 必須
方向 3: Proxy → App（イベント通知） — Phase 2 以降
方向 4: App 登録 — Phase 1: YAML / Phase 2: DB
```

## Protocol: ForwardAuth レスポンスヘッダ

```
X-Volta-User-Id:      uuid (required)
X-Volta-Email:        string (required)
X-Volta-Tenant-Id:    uuid (required)
X-Volta-Tenant-Slug:  string (required)
X-Volta-Roles:        comma-separated (required)
X-Volta-Display-Name: string (optional)
X-Volta-JWT:          signed JWT (required)
```

## Protocol: Internal API

```
GET    /api/v1/users/me
GET    /api/v1/users/{id}
GET    /api/v1/tenants/{tid}/members
GET    /api/v1/tenants/{tid}/members/{uid}
PATCH  /api/v1/tenants/{tid}/members/{uid}
DELETE /api/v1/tenants/{tid}/members/{uid}
GET    /api/v1/tenants/{tid}/invitations
POST   /api/v1/tenants/{tid}/invitations
DELETE /api/v1/tenants/{tid}/invitations/{iid}
GET    /api/v1/tenants/{tid}
PATCH  /api/v1/tenants/{tid}

認証: 元リクエストの JWT を Authorization ヘッダに転送
```

## Protocol: JSON Schema (v1)

ForwardAuthResponse, JWTClaims, APIResponse, UserObject, MemberObject, TenantObject

## App 登録 (Phase 1)

```yaml
# volta-config.yaml
apps:
  - id: app-wiki
    name: "Wiki"
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

## App 側のやること（2 つだけ）

1. X-Volta-* ヘッダを読む（or JWT 検証）
2. 必要なら Proxy の Internal API を叩く

---

## Gap 一覧

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| P-1 | ForwardAuth パターン採用の ADR | Spec-impl mismatch | 🔴 Critical |
| P-2 | ForwardAuth ヘッダ仕様 | Missing logic | 🔴 Critical |
| P-3 | App → Proxy API 認証方式 | Missing logic | 🟠 High |
| P-4 | Schema 配布形式 | Spec-impl mismatch | 🟡 Medium |
| P-5 | SDK コード自動生成 | Integration gap | 🟡 Medium |
| P-6 | App 登録と App 別アクセス制御 | Missing logic | 🟠 High |
| P-7 | ForwardAuth 動作モデル明文化 | Spec-impl mismatch | 🟠 High |
| P-8 | 4 方向通信の Phase 分け | Spec-impl mismatch | 🟡 Medium |

---

## Auto-merge: 素の LLM 追加 Gap

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| PL-1 | パスの tenantId と JWT volta_tid の不整合チェック — テナント間データ漏洩 | Safety gap | 🔴 Critical |
| PL-2 | ページネーション仕様（offset/limit, デフォルト20, 最大100） | Missing logic | 🟠 High |
| PL-3 | PATCH 更新可能フィールド JSON Schema | Missing logic | 🟠 High |
| PL-4 | volta_tname/tslug が volta_v:1 に含まれるか | Spec-impl mismatch | 🟡 Medium |
| PL-5 | App バックエンドでの JWT リフレッシュ手段 | Missing logic | 🟠 High |
| PL-6 | aud 一律で App 単位アクセス制御不可 — 配列化の検討 | Safety gap | 🟠 High |
| PL-7 | CRUD API Rate Limit が App 単位分離されていない | Ops gap | 🟠 High |
| PL-8 | Webhook Interface 境界の予約定義 | Integration gap | 🟡 Medium |
| PL-9 | JWKS 可用性 — stale-while-revalidate キャッシュ戦略 | Ops gap | 🟠 High |

### マージ統合: Protocol Gap 全 17 件（Critical 3 / High 10 / Medium 4）
