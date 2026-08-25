# MCP 化調査: volta-auth-proxy

## 概要

volta-auth-proxy はマルチテナント認証ゲートウェイ（Java 21 / Javalin 6.7）。OIDC（Google/GitHub/Microsoft/Apple）、SAML SSO、Passkey（WebAuthn）、MFA（TOTP）、JWT 発行、セッション管理、テナント・招待・RBAC、SCIM、Stripe Billing を単一プロセスで提供する。Traefik ForwardAuth パターンで下流アプリに `X-Volta-*` ヘッダを注入する。

tramli（制約付きステートマシンエンジン）で全認証フローを駆動し、tramli-plugins で監査/lint/オブザーバビリティを横断適用。

**catalog 上の状態**: `operational_status: "retired"`。後継の `volta-auth-server`（Rust 製 `volta-gateway` に内蔵）が `active` で稼働中。

## 判定と理由

**判定: `wrap`**（優先度は低い）

既に豊富な REST API を持つ常駐サーバであり、MCP 化は既存エンドポイントを薄く包むだけで済む。ただし:

1. catalog で **retired** 扱い。後継の Rust 版認証サーバが active で稼働中。
2. MCP ラップを付けても、本番トラフィックがこの Java 版に来ていなければ価値が薄い。
3. 後継 `volta-auth-server` 側に MCP を付ける方が、active な方への投資として妥当。

機能の大部分が後継に移行済みであれば `skip` に倒すべき。退役が確定しているか、まだ使う予定があるかが判断の分かれ目。

## 公開候補

| kind | name | io | 副作用 | 長時間 | 対象 |
|------|------|-----|--------|--------|------|
| tool | `verify_session` | cookie/header → {userId, tenantId, roles, jwt} | read | no | GET /auth/verify |
| tool | `refresh_token` | session cookie → {token: jwt} | write | no | POST /auth/refresh |
| tool | `issue_m2m_token` | {client_id, client_secret, scope?} → {access_token, ...} | write | no | POST /oauth/token |
| tool | `get_current_user` | jwt → {id, email, displayName, tenantId, roles} | read | no | GET /api/v1/users/me |
| tool | `list_user_tenants` | jwt → [{id, name, slug, role}] | read | no | GET /api/v1/users/me/tenants |
| tool | `get_tenant` | {tenantId} + jwt → tenantDetail | read | no | GET /api/v1/tenants/{tid} |
| tool | `list_tenant_members` | {tenantId} + jwt → paginated | read | no | GET /api/v1/tenants/{tid}/members |
| tool | `create_invitation` | {tenantId, email?, role?, ...} + jwt → {code, link} | write | no | POST /api/v1/tenants/{tid}/invitations |
| tool | `list_invitations` | {tenantId, status?} + jwt → paginated | read | no | GET /api/v1/tenants/{tid}/invitations |
| tool | `cancel_invitation` | {tenantId, invitationId} + jwt → {ok} | write | no | DELETE /api/v1/tenants/{tid}/invitations/{iid} |
| tool | `change_member_role` | {tenantId, memberId, role} + jwt → {ok} | write | no | PATCH /api/v1/tenants/{tid}/members/{mid} |
| tool | `remove_member` | {tenantId, memberId} + jwt → {ok} | write | no | DELETE /api/v1/tenants/{tid}/members/{mid} |
| tool | `list_sessions` | jwt → [{id, ip, device, ...}] | read | no | GET /api/v1/users/me/sessions |
| tool | `revoke_session` | {sessionId} + jwt → {ok} | write | no | DELETE /api/v1/users/me/sessions/{id} |
| tool | `revoke_all_sessions` | jwt → {ok} | write | no | DELETE /api/me/sessions |
| tool | `mfa_setup` | {userId} + jwt → {secret, otpauth_url} | write | no | POST /api/v1/users/{uid}/mfa/totp/setup |
| tool | `mfa_verify` | {userId, code} + jwt → {ok, enabled} | write | no | POST /api/v1/users/{uid}/mfa/totp/verify |
| tool | `get_mfa_status` | jwt → {totp, recovery_codes_remaining} | read | no | GET /api/v1/users/me/mfa |
| tool | `rotate_signing_key` | jwt(OWNER) → {ok, kid} | write | no | POST /api/v1/admin/keys/rotate |
| tool | `list_audit_logs` | {tenantId?, from?, to?, event?} + jwt(ADMIN+) → paginated | read | no | GET /api/v1/admin/audit |
| tool | `list_flow_definitions` | → {flows: [{name, mermaid, stateDiagram}]} | read | no | GET /viz/flows |
| tool | `list_flows` | {tenant_id?, flow_type?, since?} + jwt(ADMIN) → {flows} | read | no | GET /api/v1/admin/flows |
| tool | `get_flow_transitions` | {flowId} + jwt(ADMIN) → {transitions} | read | no | GET /api/v1/admin/flows/{flowId}/transitions |
| tool | `export_user_data` | jwt → JSON (GDPR) | read | no | POST /api/v1/users/me/data-export |
| tool | `request_account_deletion` | jwt → {status, delete_at} | write | no | DELETE /api/v1/users/me |
| resource | `spec` | — | — | — | auth://spec（能力の機械可読仕様） |
| resource | `guide` | — | — | — | auth://guide（使い方） |
| resource | `jwks` | — | — | — | auth://jwks（JWKS 公開鍵） |
| resource | `flow_definitions` | — | — | — | auth://flows（認証フロー図） |
| skill | `operate-auth-proxy` | — | — | — | repo locality（運用手順） |

## 組み合わせ例

1. `auth__verify_session → volta__svc_health` — セッション検証後に認証関連サービスの健全性を確認
2. `auth__list_audit_logs → index__agent_fork` — 監査ログから不審なアクティビティを検知したら調査エージェントを起動
3. `auth__list_flow_definitions → design__get_component_snippet` — 認証フローの状態遷移図を UI コンポーネントで可視化
4. `auth__issue_m2m_token → volta__svc_deploy` — デプロイ用 M2M トークンを発行してサービスデプロイを自動化

## 依存と協調

| 相手 repo | 方向 | 能力 | 現存 | 備考 |
|-----------|------|------|------|------|
| tramli | depends_on | state machine engine | yes | pom.xml 依存。認証フロー定義・実行の中核 |
| tramli-plugins | depends_on | audit/lint/observability plugins | yes | pom.xml 依存。全フローに横断的関心事を適用 |
| volta-gateway | depends_on | 認証サーバ（Rust 版後継） | yes | catalog で active。本リポジトリは retired |
| volta-auth-console | provides_to | Internal API (/api/v1/*) | yes | React 製管理 UI が本プロキシの API を消費 |
| nanori-site | provides_to | 認証（管理コンソールが volta-auth 認証を使用） | yes | catalog に記載。nanori が認証を委譲している可能性 |

## ライブラリのサーバ化

該当しない。既に常駐サーバ（Javalin, port 7070, /healthz あり）。

## リスク

- **retired 状態**: catalog で退役扱い。後継 `volta-auth-server` (Rust) が active。MCP ラップを付けても本番運用に乗らない可能性
- **秘密情報**: `JWT_KEY_ENCRYPTION_SECRET`, `VOLTA_SERVICE_TOKEN`,各 IdP client secret, Stripe secret key 等の環境変数を扱う。MCP 経由で漏洩しないよう注意
- **破壊的操作**: アカウント削除(GDPR)、鍵ローテーション、メンバー削除、セッション失効等。confirm パラメータを必須にすべき
- **外部 API 課金**: Stripe (billing), Google/Microsoft OIDC (API クォータ), Fraud Alert (FRAUD_ALERT_URL)
- **認証コンテキスト**: MCP tool 経由の呼び出しで JWT/cookie をどう扱うかが課題。エージェントがブラウザセッション cookie を持つ形は不自然。M2M トークン発行→tool 呼び出しの 2 段階が現実的

## 持ち主への質問

1. retired 確定か? 後継 `volta-auth-server` (Rust) に機能が完全に移行済みなら、このリポジトリの MCP 化は `skip` にすべき
2. もし MCP 化するなら、認証コンテキストをどう渡すか（M2M トークン発行→tool 呼び出しの 2 段階？ユーザー JWT を外部から注入？）
3. `volta-auth-console` (React 管理 UI) との役割分担。MCP tool は管理 UI がやることを代替するのか、API 経由の自動化用途か
4. 後継 `volta-auth-server` (Rust) 側に MCP を付ける方が適切ではないか（active な方に投資すべき）
