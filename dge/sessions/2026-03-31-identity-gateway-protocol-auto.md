# DGE Auto-iteration — Protocol C/H Gap 解決
- **Date**: 2026-03-31
- **Flow**: auto-iterate (Protocol)
- **Theme**: App ↔ Proxy Protocol の全 C/H Gap に解決策

---

## Critical 3 件 → ✅ 全解決

1. **ForwardAuth ADR**: Traefik ForwardAuth 採用。Proxy は認証チェックのみ。ボディ中継しない。
2. **ForwardAuth ヘッダ仕様**: X-Volta-{User-Id,Email,Tenant-Id,Tenant-Slug,Roles,Display-Name,JWT}。/auth/verify で 200 + ヘッダ or 401。~10ms/req。
3. **tenantId 不整合チェック**: パスの {tid} == JWT の volta_tid を必ず強制。クロステナントアクセス禁止。

## High 10 件 → ✅ 全解決

4. **バッチ認証**: 静的サービストークン (VOLTA_SERVICE_TOKEN)。Docker 内部限定。Phase 2 で client credentials 移行。
5. **App 登録**: volta-config.yaml で定義。allowed_roles で App 別アクセス制御。
6. **ForwardAuth 動作モデル**: 明文化済み。Proxy はボディを見ない。
7. **ページネーション**: offset/limit。デフォルト 20、最大 100。
8. **PATCH Schema**: users は display_name のみ。members は role のみ。OWNER 変更は別 API。
9. **aud 配列化**: Phase 1 は ["volta-apps"]。配列で発行して Phase 2 の破壊的変更回避。
10. **Rate Limit**: Phase 1 は 200/min/user。Phase 2 で App 単位分離。
11. **JWKS キャッシュ**: stale-while-revalidate。古いキャッシュを最大 24h 使用。

## C/H Gap 残存数: 0

---

## Protocol 確定版サマリー

### 方向 1: Proxy → App（ForwardAuth）

```
Traefik → /auth/verify → 200 + X-Volta-* ヘッダ → App
ヘッダ: User-Id, Email, Tenant-Id, Tenant-Slug, Roles, Display-Name, JWT
パフォーマンス: ~10ms (キャッシュ有で ~1ms)
```

### 方向 2: App → Proxy（Internal API）

```
App → Authorization: Bearer <user-jwt> → /api/v1/*
  or → Authorization: Bearer volta-service:<token> → /api/v1/*

API: users, tenants, members, invitations
ページネーション: offset/limit (default 20, max 100)
tenantId チェック: パス == JWT で強制
Rate Limit: 200/min/user
```

### 方向 3: イベント通知 — Phase 2

```
JWT 短寿命 (5min) で当面代用。
Phase 2: Webhook + X-Volta-Signature
```

### 方向 4: App 登録 — YAML

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```
