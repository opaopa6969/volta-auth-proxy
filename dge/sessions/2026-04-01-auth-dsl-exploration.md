# DGE Session — Auth DSL 探索
- **Date**: 2026-04-01
- **Flow**: quick
- **Theme**: 認証の protocol/flow/state を DSL で定義し、コード生成 or DSL 実行する構想

---

## 着想

認証フローは本質的に状態マシン。今は Markdown + Java に散在している定義を DSL に統合すれば:
- DSL → Java コード変換（or DSL executor で直接実行）
- DSL → テスト自動生成
- DSL → ドキュメント/mermaid 自動生成
- AI が DSL を読んで正確な実装を生成

## DSL で定義できるもの

### 1. State Machine

```yaml
# volta-auth.states.yaml

states:
  UNAUTHENTICATED:
    description: "No session. Not logged in."
    transitions:
      - to: OIDC_IN_PROGRESS
        trigger: GET /login
        action: start_oidc_flow

  OIDC_IN_PROGRESS:
    description: "Redirected to Google. Waiting for callback."
    transitions:
      - to: AUTHENTICATED_NO_TENANT
        trigger: GET /callback
        guard: callback_valid AND user_exists
        action: [upsert_user, create_session]
      - to: UNAUTHENTICATED
        trigger: GET /callback
        guard: callback_invalid
        action: show_error("AUTH_FAILED")

  AUTHENTICATED_NO_TENANT:
    description: "Logged in but no tenant selected."
    transitions:
      - to: AUTHENTICATED
        trigger: POST /auth/switch-tenant
        guard: has_membership AND tenant_active
        action: [set_tenant, issue_jwt]
      - to: AUTHENTICATED
        trigger: auto
        guard: tenant_count == 1
        action: [auto_select_tenant, issue_jwt]
      - to: TENANT_SELECT
        trigger: auto
        guard: tenant_count > 1
        action: show_tenant_select

  TENANT_SELECT:
    description: "Showing tenant selection screen."
    transitions:
      - to: AUTHENTICATED
        trigger: POST /auth/switch-tenant
        guard: has_membership AND tenant_active
        action: [set_tenant, issue_jwt]

  AUTHENTICATED:
    description: "Fully authenticated. Tenant confirmed. JWT issued."
    transitions:
      - to: AUTHENTICATED
        trigger: any_request
        guard: session_valid AND jwt_not_expired
        action: forward_to_app
      - to: AUTHENTICATED
        trigger: any_request
        guard: session_valid AND jwt_expired
        action: [refresh_jwt, forward_to_app]
      - to: UNAUTHENTICATED
        trigger: POST /auth/logout
        action: [invalidate_session, clear_cookie]
      - to: UNAUTHENTICATED
        trigger: any_request
        guard: session_invalid
        action: redirect_to_login
      - to: TENANT_SUSPENDED
        trigger: any_request
        guard: session_valid AND tenant_suspended
        action: show_error("TENANT_SUSPENDED")

  TENANT_SUSPENDED:
    description: "Tenant is suspended."
    transitions:
      - to: TENANT_SELECT
        trigger: user_action
        guard: has_other_tenants
        action: show_tenant_select
```

### 2. Flow Definition

```yaml
# volta-auth.flows.yaml

flows:
  invite_first_login:
    description: "First-time user via invitation link"
    steps:
      - screen: invite_landing
        url: GET /invite/{code}
        show:
          tenant_name: invitation.tenant.name
          inviter_name: invitation.created_by.display_name
          role: invitation.role
        actions:
          - label: "Login with Google"
            goto: oidc_start
        errors:
          invitation_expired: show_expired_page
          invitation_used: show_used_page
          invitation_not_found: show_404

      - screen: oidc_start
        url: GET /login
        params:
          invite_code: "{code}"
          return_to: "/invite/{code}/accept"
        action: redirect_to_google_oidc

      - screen: google_login
        external: true
        provider: google

      - screen: oidc_callback
        url: GET /callback
        action: process_oidc_callback
        on_success:
          new_user: [create_user, create_session, goto invite_consent]
          existing_user: [create_session, goto invite_consent]
        on_error:
          state_mismatch: show_error("AUTH_FAILED", retry: oidc_start)
          email_not_verified: show_error("EMAIL_NOT_VERIFIED")

      - screen: invite_consent
        url: GET /invite/{code}/accept
        show:
          tenant_name: invitation.tenant.name
          role: invitation.role
          consent_text: "Your profile will be visible to workspace admins"
        actions:
          - label: "Join"
            method: POST
            action: [accept_invitation, create_membership, redirect_to_app]
          - label: "Cancel"
            goto: "/"
        errors:
          already_member: show_error("CONFLICT", link_to_app: true)

  returning_user:
    description: "User with valid session accessing an app"
    steps:
      - screen: forward_auth
        url: GET /auth/verify
        action: verify_session
        on_success:
          tenant_ok: return_volta_headers
          no_tenant_single: [auto_select, return_volta_headers]
          no_tenant_multi: redirect_to_tenant_select
          tenant_suspended: return_403
        on_error:
          session_invalid: return_401
```

### 3. Protocol Definition

```yaml
# volta-auth.protocol.yaml

protocol:
  version: 1

  forward_auth:
    endpoint: GET /auth/verify
    request:
      cookies: [__volta_session]
      headers: [X-Forwarded-Host, X-Forwarded-For]
    response:
      success:
        status: 200
        headers:
          X-Volta-User-Id:      { type: uuid, required: true, source: user.id }
          X-Volta-Email:        { type: email, required: true, source: user.email }
          X-Volta-Tenant-Id:    { type: uuid, required: true, source: session.tenant_id }
          X-Volta-Tenant-Slug:  { type: string, required: true, source: tenant.slug }
          X-Volta-Roles:        { type: csv, required: true, source: membership.roles }
          X-Volta-Display-Name: { type: string, required: false, source: user.display_name }
          X-Volta-JWT:          { type: jwt, required: true, source: issued_jwt }
          X-Volta-App-Id:       { type: string, required: false, source: matched_app.id }
      error:
        unauthenticated: { status: 401, code: AUTHENTICATION_REQUIRED }
        tenant_suspended: { status: 403, code: TENANT_SUSPENDED }
        role_insufficient: { status: 403, code: ROLE_INSUFFICIENT }

  jwt:
    algorithm: RS256
    expiry: 300  # seconds
    claims:
      iss: { type: string, const: "volta-auth" }
      aud: { type: string[], default: ["volta-apps"] }
      sub: { type: uuid, source: user.id }
      exp: { type: timestamp, computed: "now + expiry" }
      iat: { type: timestamp, computed: "now" }
      jti: { type: uuid, computed: "random" }
      volta_v: { type: int, const: 1 }
      volta_tid: { type: uuid, source: session.tenant_id }
      volta_tname: { type: string, source: tenant.name }
      volta_tslug: { type: string, source: tenant.slug }
      volta_roles: { type: string[], source: membership.roles }
      volta_display: { type: string, source: user.display_name, optional: true }

  errors:
    - code: AUTHENTICATION_REQUIRED
      status: 401
      message_en: "Login is required"
      message_ja: "ログインが必要です"
      recovery: { action: redirect, target: "/login?return_to={current}" }

    - code: SESSION_EXPIRED
      status: 401
      message_en: "Your session has expired"
      message_ja: "セッションの有効期限が切れました"
      recovery: { action: redirect, target: "/login?return_to={current}" }

    - code: TENANT_SUSPENDED
      status: 403
      message_en: "This workspace is suspended"
      message_ja: "このワークスペースは一時停止中です"
      recovery:
        has_other_tenants: { action: redirect, target: "/select-tenant" }
        no_other_tenants: { action: show_contact, source: "config.support_contact" }

    - code: INVITATION_EXPIRED
      status: 410
      message_en: "This invitation has expired"
      message_ja: "招待リンクの有効期限が切れました"
      recovery: { action: show_message, text_en: "Ask the inviter to resend", text_ja: "招待者に再送信を依頼してください" }

  api:
    base_path: /api/v1
    auth: { type: bearer, source: jwt }
    tenant_check: { path_param: tenantId, must_match: jwt.volta_tid }

    endpoints:
      - method: GET
        path: /users/me
        auth: any
        response: UserObject

      - method: GET
        path: /users/me/tenants
        auth: any
        response: TenantObject[]

      - method: GET
        path: /tenants/{tenantId}/members
        auth: MEMBER+
        pagination: { default_limit: 20, max_limit: 100 }
        response: MemberObject[]

      - method: PATCH
        path: /tenants/{tenantId}/members/{memberId}
        auth: ADMIN+
        body: { role: "VIEWER | MEMBER | ADMIN" }
        guards:
          - last_owner_check: "cannot demote last OWNER"

      - method: DELETE
        path: /tenants/{tenantId}/members/{memberId}
        auth: ADMIN+
        action: deactivate_membership

      - method: POST
        path: /tenants/{tenantId}/invitations
        auth: ADMIN+
        body:
          email: { type: email, optional: true }
          role: { type: enum, values: [VIEWER, MEMBER, ADMIN], default: MEMBER }
          expires_in_hours: { type: int, default: 72, max: 720 }
        response: InvitationObject

  models:
    UserObject:
      id: uuid
      email: email
      display_name: string?
      is_active: boolean
      created_at: timestamp

    TenantObject:
      id: uuid
      name: string
      slug: string
      plan: string
      is_active: boolean

    MemberObject:
      user: UserObject
      role: enum[OWNER, ADMIN, MEMBER, VIEWER]
      joined_at: timestamp

    InvitationObject:
      id: uuid
      code: string
      link: url
      email: string?
      role: string
      expires_at: timestamp
      status: enum[pending, used, expired, cancelled]
```

### 4. Policy Definition

```yaml
# volta-auth.policy.yaml

roles:
  hierarchy: [OWNER, ADMIN, MEMBER, VIEWER]  # highest to lowest

  permissions:
    OWNER:
      inherits: ADMIN
      can:
        - delete_tenant
        - transfer_ownership
        - manage_signing_keys

    ADMIN:
      inherits: MEMBER
      can:
        - invite_members
        - remove_members
        - change_roles  # up to ADMIN level
        - change_tenant_settings
        - manage_invitations

    MEMBER:
      inherits: VIEWER
      can:
        - use_apps
        - view_own_profile
        - manage_own_sessions

    VIEWER:
      can:
        - read_only  # enforced by app

  constraints:
    - "Last OWNER cannot be demoted"
    - "Cannot promote above own role"
    - "Cannot demote self if last of that role"

  app_access:
    source: volta-config.yaml
    enforcement: forward_auth
    rule: "user.roles intersect app.allowed_roles must not be empty"
```

## DSL から生成できるもの

```
volta-auth.states.yaml  →  StateMachine.java (状態遷移の実装)
                        →  state-diagram.mermaid (ドキュメント)
                        →  StateTransitionTest.java (全遷移のテスト)

volta-auth.flows.yaml   →  FlowRouter.java (画面遷移のルーティング)
                        →  flow-diagrams.mermaid (フロー図)
                        →  FlowTest.java (全フローの E2E テスト)
                        →  *.jte (テンプレートのスケルトン)

volta-auth.protocol.yaml → ForwardAuthHandler.java
                         → JwtClaims.java (型安全な claims)
                         → ApiRouter.java (全 API ルーティング)
                         → ErrorHandler.java (エラーレスポンス)
                         → openapi.yaml (API ドキュメント)
                         → volta-sdk models (Java + JS)

volta-auth.policy.yaml  →  RoleChecker.java (権限チェック)
                        →  PolicyTest.java (全ポリシーのテスト)
```

## AI との相性

```
DSL があれば AI は:
  1. DSL を読んで「何を実装すべきか」を正確に理解できる
  2. DSL の変更から差分コードを生成できる
  3. DSL と実装の乖離を検出できる
  4. DSL からテストを自動生成して実装を検証できる
  5. 新しいフロー/エンドポイントの追加が DSL 1 行で済む

つまり:
  DSL = 設計の single source of truth
  実装 = DSL から自動/半自動で生成
  テスト = DSL から自動生成
  ドキュメント = DSL から自動生成
```
