# MCP 設計書: volta-auth-proxy (namespace: `auth`)

> Phase 1 survey.json (decision=wrap) に基づく。割当表 #14: namespace=`auth`, port=9211。

## 1. namespace と種別

- **namespace**: `auth`（予約語 `catalog`/`probe`/`skill` と衝突しない）
- **種別**: `wrap` — 既存 Java REST API（Javalin, port 7070）を薄く包む Node.js MCP サーバ
- **ホスト**: 192.168.1.8（既存の `volta-auth-proxy` と同じ WSL ホスト）
- **MCP ポート**: 9211（割当表指定、machine_ports で空き確認済み）
- **min_role**: MEMBER（認証系の操作はメンバー以上に見せる）

## 2. 認証コンテキストの設計

既存の REST API は JWT（`Authorization: Bearer`）またはセッション cookie で認証する。
MCP tool 経由の呼び出しではエージェントがブラウザ cookie を持たないため、**M2M トークン（`issue_m2m_token` で `client_credentials` グラント）→ 得られた JWT を以降の tool 呼び出しに渡す** 2 段階を基本とする。

- `auth__issue_m2m_token` は `{client_id, client_secret, scope?, audience?}` を受け取り、`{access_token, token_type, expires_in, scope}` を返す（POST /oauth/token へのラップ）。
- 以降の tool 呼び出しでは `jwt` パラメータ（string）で得られた access_token を渡す。MCP サーバは `Authorization: Bearer <jwt>` として Java API に転送する。
- 破壊的操作（鍵ローテーション、アカウント削除、メンバー削除等）は `confirm: boolean=false` を持ち、false なら dry-run（対象と予定を返す）。

## 3. tools 表

| name | 目的 | 入力 schema（要点） | 出力の形 | 副作用 | dry-run | job 型 | 所要時間 | min_role |
|------|------|---------------------|----------|--------|---------|--------|----------|----------|
| `verify_session` | セッションを検証して X-Volta-* ヘッダを返す | `{cookie: string}` | `{userId, tenantId, roles, jwt, appId} \| 401` | read | no | no | <1s | VIEWER |
| `refresh_token` | JWT をリフレッシュする | `{jwt: string}` | `{token: string, expires_in: number}` | write | no | no | <1s | MEMBER |
| `issue_m2m_token` | M2M クライアント認証でトークンを発行する | `{client_id: string, client_secret: string, scope?: string, audience?: string}` | `{access_token, token_type, expires_in, scope}` | write | no | no | <1s | MEMBER |
| `get_current_user` | 現在のユーザー情報を取得する | `{jwt: string}` | `{id, email, displayName, tenantId, roles}` | read | no | no | <1s | MEMBER |
| `list_user_tenants` | ユーザーが所属するテナント一覧を取得する | `{jwt: string}` | `{data: [{id, name, slug, role, isLast}]}` | read | no | no | <1s | MEMBER |
| `get_tenant` | テナント情報を取得する | `{jwt: string, tenantId: string}` | tenantDetail | read | no | no | <1s | MEMBER |
| `list_tenant_members` | テナントメンバー一覧を取得する | `{jwt: string, tenantId: string, offset?: number, limit?: number}` | paginated members | read | no | no | <1s | MEMBER |
| `change_member_role` | メンバーロールを変更する | `{jwt: string, tenantId: string, memberId: string, role: string, confirm?: boolean}` | `{ok: true} \| dry-run` | write | yes | no | <1s | ADMIN |
| `remove_member` | メンバーを削除する | `{jwt: string, tenantId: string, memberId: string, confirm?: boolean}` | `{ok: true} \| dry-run` | destructive | yes | no | <1s | ADMIN |
| `create_invitation` | テナントに招待を作成する | `{jwt: string, tenantId: string, email?: string, role?: string, max_uses?: number, expires_in_hours?: number}` | `{id, code, link, expiresAt}` | write | no | no | <1s | MEMBER |
| `list_invitations` | テナントの招待一覧を取得する | `{jwt: string, tenantId: string, status?: string}` | paginated invitations | read | no | no | <1s | MEMBER |
| `cancel_invitation` | 招待をキャンセルする | `{jwt: string, tenantId: string, invitationId: string, confirm?: boolean}` | `{ok: true} \| dry-run` | write | yes | no | <1s | MEMBER |
| `list_sessions` | セッション一覧を取得する | `{jwt: string}` | `{items: [{id, tenantId, ip, lastActiveAt, device, browser, os}]}` | read | no | no | <1s | MEMBER |
| `revoke_session` | セッションを失効する | `{jwt: string, sessionId: string, confirm?: boolean}` | `{ok: true} \| dry-run` | write | yes | no | <1s | MEMBER |
| `revoke_all_sessions` | 全セッションを失効する | `{jwt: string, confirm?: boolean}` | `{ok: true} \| dry-run` | destructive | yes | no | <1s | MEMBER |
| `mfa_setup` | MFA (TOTP) セットアップを開始する | `{jwt: string, userId: string}` | `{secret, otpauth_url}` | write | no | no | <1s | MEMBER |
| `mfa_verify` | MFA (TOTP) を検証・有効化する | `{jwt: string, userId: string, code: string}` | `{ok, enabled}` | write | no | no | <1s | MEMBER |
| `get_mfa_status` | MFA ステータスを取得する | `{jwt: string}` | `{totp: {enabled}, recovery_codes_remaining}` | read | no | no | <1s | MEMBER |
| `rotate_signing_key` | 署名鍵をローテーションする | `{jwt: string, confirm?: boolean}` | `{ok, kid} \| dry-run` | destructive | yes | no | <1s | OWNER |
| `list_audit_logs` | 監査ログを取得する | `{jwt: string, tenantId?: string, from?: string, to?: string, event?: string, offset?: number, limit?: number}` | paginated logs | read | no | no | <1s | ADMIN |
| `list_flow_definitions` | 認証フロー定義一覧を取得する | `{}` | `{flows: [{name, mermaid, stateDiagram}]}` | read | no | no | <1s | VIEWER |
| `list_flows` | 認証フロー実行一覧を取得する | `{jwt: string, tenant_id?: string, flow_type?: string, since?: string, limit?: number}` | `{flows: [...]}` | read | no | no | <1s | ADMIN |
| `get_flow_transitions` | フロー遷移履歴を取得する | `{jwt: string, flowId: string}` | `{flowId, transitions: [...]}` | read | no | no | <1s | ADMIN |
| `export_user_data` | GDPR データエクスポート | `{jwt: string}` | JSON (user, memberships, sessions, devices) | read | no | no | <1s | MEMBER |
| `request_account_deletion` | GDPR 削除要求 | `{jwt: string, confirm?: boolean}` | `{status: scheduled, delete_at} \| dry-run` | destructive | yes | no | <1s | MEMBER |

## 4. resources 表

| uri | 内容 | mime |
|-----|------|------|
| `auth://spec` | 能力の機械可読仕様（tools/list から生成 + compositions/depends_on） | application/json |
| `auth://guide` | 使い方ガイド（M2M トークン→tool 呼び出しの流れ、confirm の使い方） | text/markdown |
| `auth://jwks` | JWKS 公開鍵（`/.well-known/jwks.json` のプロキシ） | application/json |
| `auth://flows` | 認証フロー図（mermaid + stateDiagram、`/viz/flows` のプロキシ） | application/json |

## 5. prompts / skills

| name | 用途 | locality |
|------|------|----------|
| `operate-auth-proxy` | volta-auth-proxy の運用手順（M2M トークン発行→各種操作→confirm） | service（`docs/skills/operate-auth-proxy/SKILL.md` + resource `skill://operate-auth-proxy`） |

## 6. 組み合わせ例

1. `auth__issue_m2m_token → auth__get_current_user → auth__list_user_tenants` — M2M トークンを発行し、ユーザー情報とテナント一覧を取得する基本フロー
2. `auth__list_audit_logs → index__agent_fork` — 監査ログから不審なアクティビティを検知したら調査エージェントを起動
3. `auth__list_flow_definitions → design__get_component_snippet` — 認証フローの状態遷移図を UI コンポーネントで可視化
4. `auth__issue_m2m_token → volta__svc_deploy` — デプロイ用 M2M トークンを発行してサービスデプロイを自動化

## 7. 依存と協調

| 相手 repo | 方向 | 能力 | 合意したいこと |
|-----------|------|------|----------------|
| tramli | depends_on | state machine engine | フロー定義 API の形式確定（暫定: `/viz/flows` の JSON 形式をそのまま使用） |
| volta-auth-console | provides_to | Internal API (/api/v1/*) | 管理UI と MCP tool の役割分担（暫定: MCP は API 経由の自動化用途、UI は人間向け） |
| volta-gateway (Rust) | depends_on | 後継認証サーバ | 後継への MCP 移行タイミング（暫定: 並行稼働、retired 確定時に移行） |
| auth-test/volta-gateway (vgw-mcp) | coordinate | `vgw` namespace は後継 volta-auth-server/Rust 向け | `vgw__audit_search` / `vgw__user_search` / `vgw__tenant_list` は後継 (Rust) の監査ログ・ユーザー・テナント操作。`auth__list_audit_logs` 等は旧版 (Java) の同等 API。対象システムが違うため重複ではないが、エージェントは active な後継 (vgw) を優先し、旧版 (auth) は後継に未移行の機能の補完として使うこと（暫定） |

issue-hub に `mcp-coordination` ラベルで登録する。

## 8. 非対応にした候補

Phase 1 survey からの差分:
- **Passkey 登録系** (`/api/v1/users/{userId}/passkeys/*`) — WebAuthn のチャレンジレスポンスフローは MCP tool 向きでない（ブラウザの WebAuthn API が必要）。除外。
- **Stripe Billing 系** (`/api/v1/tenants/{tid}/billing/*`) — 外部課金 API を扱う。破壊的操作かつ課金リスクが高く、M2M 自動化用途に合わない。除外。
- **SCIM 系** (`/scim/v2/*`) — 外部 IdP 同期用。MCP tool としての需要が低い。除外。
- **管理画面 HTML ルート** (`/admin/*`, `/settings/*`) — HTML ページ配信。MCP tool でなくてよい。除外。

## 9. 参加方法

- **manifest**: `volta.service.json` を root に配置
- **id**: `volta-auth-proxy`（catalog 既存 id。MCP 項を追加する形）
- **hostname**: `auth-proxy.unlaxer.org`（新規サブドメイン、MCP/API 用）
- **port**: 9211（MCP サーバ）
- **host**: 192.168.1.8（WSL）
- **runtime**: systemd user unit
- **auth**: minRole:MEMBER
- **health_check**: /healthz

## 10. テスト方針

- e2e: MCP クライアント（`@modelcontextprotocol/sdk` の `Client` + `StreamableHTTPClientTransport`）で
  1. `/healthz` 200 確認
  2. `tools/list` で全 tool が見える
  3. `issue_m2m_token` の dry-run（client_id/secret 無しで 400 を期待、schema validation 確認）
  4. `auth://spec` resource の取得
  5. `auth://guide` resource の取得
  6. confirm required tool の dry-run（confirm=false → dry-run message）
