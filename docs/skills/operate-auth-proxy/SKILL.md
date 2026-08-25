---
name: operate-auth-proxy
description: volta-auth-proxy MCP (namespace=auth) 経由で認証・テナント・セッション・監査ログを操作する手順
volta:
  version: 1
  namespace: auth
  locality: service
  applies_when:
    - repo.has_file("mcp/server.mjs")
  requires:
    tools:
      - auth__issue_m2m_token
      - auth__get_current_user
  min_role: MEMBER
  export: false
  tags: [auth, ops, mcp]
---

# volta-auth-proxy 運用手順

> この手順は resource `skill://operate-auth-proxy` でも配信される。

## 1. M2M トークンの発行

`auth__issue_m2m_token` に `{client_id, client_secret}` を渡して `access_token` を取得する。

```
auth__issue_m2m_token({ client_id: "...", client_secret: "..." })
→ { access_token: "eyJ...", token_type: "Bearer", expires_in: 3600, scope: "..." }
```

## 2. 読み取り操作

`auth__get_current_user`, `auth__list_user_tenants`, `auth__list_sessions` 等の `jwt` パラメータに M2M トークンを渡す。

```
auth__get_current_user({ jwt: "<access_token>" })
→ { id, email, displayName, tenantId, roles }
```

## 3. 破壊的操作

以下の tool は `confirm: true` で実行。未指定なら dry-run（対象と予定を返す）。

- `auth__remove_member` — メンバー削除
- `auth__revoke_all_sessions` — 全セッション失効
- `auth__rotate_signing_key` — 署名鍵ローテーション（OWNER 権限）
- `auth__request_account_deletion` — GDPR アカウント削除
- `auth__change_member_role` — ロール変更
- `auth__cancel_invitation` — 招待キャンセル
- `auth__revoke_session` — 個別セッション失効

## 4. 監査ログ

`auth__list_audit_logs` で操作履歴を確認。ADMIN 権限が必要。

## 5. 認証フロー可視化

`auth__list_flow_definitions`（公開、JWT 不要）でフロー定義の mermaid 図を取得。

## 6. 注意

- この MCP は `volta-auth-proxy` (Java/Javalin, port 7070) の REST API を wrap している
- catalog 上 `operational_status: retired`。後継の `volta-auth-server` (Rust) が active
- 後継系の操作は `vgw` namespace（vgw-mcp）を優先し、旧版固有の機能の補完として `auth` を使う
- JWT/secret は MCP 経由で露出しない（tool パラメータとして渡すが、サーバログには出力しない）
