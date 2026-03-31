# volta-auth-proxy UI Flow

> DGE generated -- human review required
> status: draft
> source: DGE UI Flow Design Review, 2026-04-01

---

## User State Model

```mermaid
stateDiagram-v2
    [*] --> Unauthenticated_New : First access
    [*] --> Unauthenticated_Existing : Session expired

    Unauthenticated_New --> Authenticated_NoTenant : Google login + user created
    Unauthenticated_Existing --> Authenticated_NoTenant : Google login

    Authenticated_NoTenant --> Authenticated_Ready : Tenant selected
    Authenticated_Ready --> Authenticated_NoTenant : Tenant switch
    Authenticated_Ready --> Unauthenticated_Existing : Logout or session expired

    Authenticated_Ready --> Authenticated_Ready : Silent JWT refresh
```

---

## Flow 1: Invite Link - First Login

Most important flow. User touches volta for the first time.

```mermaid
flowchart TD
    A["Tap invite link in Slack"] --> B["GET /invite/code"]

    B --> C{"Code status?"}
    C -->|Valid| D["Invite landing page"]
    C -->|Expired| E["Expired page"]
    C -->|Used| F["Already used page"]
    C -->|Invalid| G["404 Error"]

    D --> H["Click: Login with Google"]
    H --> I["GET /login?invite_code=X"]
    I --> J["302 to Google OIDC"]
    J --> K["Google login screen"]
    K --> L["GET /callback?code=Y&state=Z"]

    L --> M{"Callback validation"}
    M -->|State mismatch| N["Error: Auth failed"]
    M -->|Email not verified| O["Error: Email unverified"]
    M -->|Success| P{"User exists?"}

    P -->|New| Q["INSERT user"]
    P -->|Existing| R["SELECT user"]
    Q --> S["Create session + Cookie"]
    R --> S

    S --> T["302 to /invite/code/accept"]
    T --> U["Consent screen: Join tenant?"]

    U -->|Accept| V["POST /invite/code/accept"]
    U -->|Cancel| W["Redirect to /"]

    V --> X{"Result"}
    X -->|Success| Y["302 to App URL"]
    X -->|Already member| Z["409: Already a member"]
```

### Notes
- **Screens**: Minimum 4 (landing, Google, consent, App)
- **Back button**: Going back to /callback causes state mismatch error. Recovery: "Login again" button
- **Mobile**: All screens responsive. Google login works on mobile browsers

---

## Flow 2: Returning User - Session Valid

Most frequent flow. User sees nothing.

```mermaid
flowchart TD
    A["Access App URL"] --> B["Traefik ForwardAuth"]
    B --> C["GET /auth/verify"]

    C --> D{"Session?"}
    D -->|"Valid + Tenant active"| E["200 + X-Volta headers"]
    D -->|"Valid + 1 tenant + not selected"| F["Auto-select tenant"]
    D -->|"Valid + multi tenant + not selected"| G["302 to /select-tenant"]
    D -->|"Valid + Tenant suspended"| H["403 TENANT_SUSPENDED"]
    D -->|"Invalid or expired"| I["401"]

    E --> J["App renders - no login screen"]
    F --> J

    G --> K["Tenant selection screen"]
    K --> L["Select tenant"]
    L --> M["POST /auth/switch-tenant"]
    M --> J

    H --> N{"Other tenants?"}
    N -->|Yes| O["Switch workspace button"]
    N -->|No| P["Contact admin"]

    I --> Q["Redirect to /login"]
```

### Notes
- **Single tenant**: Selection screen skipped (zero clicks)
- **Tenant suspended**: Error page with switch option
- **90% of returning users**: Reach E directly. Never see login screen

---

## Flow 3: Tenant Selection

```mermaid
flowchart TD
    A["GET /select-tenant"] --> B{"Tenant count"}

    B -->|1| C["Auto-select, skip screen"]
    B -->|"2-5"| D["Card list with last-used highlight"]
    B -->|"6+"| E["Search bar + scroll list"]

    D --> F["Click tenant"]
    E --> F

    F --> G["POST /auth/switch-tenant"]
    G --> H{"Result"}
    H -->|Success| I["Session updated, go to App"]
    H -->|Suspended| J["403 Error page"]
    H -->|Not a member| K["403 Error page"]
```

---

## Flow 4: Tenant Switch During Session

```mermaid
sequenceDiagram
    participant User
    participant App as App Browser
    participant SDK as volta-sdk-js
    participant GW as Gateway
    participant DB as Postgres

    User->>App: Click tenant switch UI
    App->>SDK: volta.switchTenant t_new
    SDK->>GW: POST /auth/switch-tenant
    GW->>DB: UPDATE sessions SET tenant_id
    GW-->>SDK: 200 OK
    SDK->>App: window.location.reload
    App->>GW: GET /auth/verify ForwardAuth
    GW-->>App: 200 + X-Volta headers new tenant
    App->>User: Re-render with new tenant data
```

---

## Flow 5: Session Expired - Silent Refresh

```mermaid
sequenceDiagram
    participant User
    participant App as App SPA
    participant SDK as volta-sdk-js
    participant GW as Gateway

    User->>App: Click button triggers API call
    App->>SDK: volta.fetch /api/data
    SDK->>GW: GET /api/data ForwardAuth
    GW-->>SDK: 401 JWT expired

    Note over SDK: 401 intercepted

    SDK->>GW: POST /auth/refresh with Cookie
    alt Session valid
        GW-->>SDK: 200 + new JWT
        SDK->>GW: GET /api/data retry
        GW-->>SDK: 200 + data
        SDK-->>App: Return data
        App-->>User: Update screen silently
    else Session invalid
        GW-->>SDK: 401 SESSION_EXPIRED
        SDK->>App: Redirect to /login
        App-->>User: Show login screen
    end
```

---

## Flow 6: Logout

```mermaid
flowchart TD
    A["Logout button"] --> B["POST /auth/logout"]
    B --> C["Delete cookie + invalidate session"]
    C --> D["302 to /login"]
    D --> E["Login page"]

    F["Browser back button"] --> G["Cached App page shown"]
    G --> H["Next API call returns 401"]
    H --> I["volta-sdk-js redirects to /login"]
```

### Mitigation
- ForwardAuth returns `Cache-Control: no-store, private`
- SDK docs recommend `Cache-Control: no-store` for App pages

---

## Flow 7: Invitation Management - Admin

```mermaid
flowchart TD
    A["GET /admin/invitations"] --> B["Invitation list"]
    B --> C{"Action"}

    C -->|Create| D["Enter email + select role"]
    D --> E["POST /api/v1/tenants/tid/invitations"]
    E --> F["Show invite link + Copy + QR"]

    C -->|Cancel| G["DELETE invitation"]
    G --> H["Remove from list"]

    B --> I["Status display"]
    I --> J["Pending"]
    I --> K["Used - show who"]
    I --> L["Expired"]
```

---

## Flow 8: Member Management - Admin

```mermaid
flowchart TD
    A["GET /admin/members"] --> B["Member list"]
    B --> C{"Action"}

    C -->|"Change role"| D["Dropdown: VIEWER/MEMBER/ADMIN"]
    D --> E["PATCH member role"]
    E --> F{"Result"}
    F -->|Success| G["Refresh list"]
    F -->|"OWNER transfer"| H["Confirm ownership transfer"]
    F -->|"Self demotion"| I["Confirm: Your role will change"]

    C -->|"Remove member"| J["Confirm dialog"]
    J --> K["DELETE member"]
    K --> G
```

---

## Flow 9: Session Management - User

```mermaid
flowchart TD
    A["GET /settings/sessions"] --> B["Session list"]

    B --> C["Current session - This Device badge"]
    B --> D["Other sessions - Logout button each"]

    D --> E["DELETE /auth/sessions/id"]
    E --> F["Remove item from list"]

    B --> G["Logout all other devices button"]
    G --> H["Confirm dialog"]
    H --> I["POST /auth/sessions/revoke-all"]
    I --> J["Refresh - only current remains"]
```

---

## Error Recovery Flow

```mermaid
flowchart TD
    ERR["Error occurred"] --> TYPE{"Error code"}

    TYPE -->|AUTH_REQUIRED| A1["Login button"]
    TYPE -->|SESSION_EXPIRED| A2["Re-login button"]
    TYPE -->|SESSION_REVOKED| A3["Re-login + admin contact"]

    TYPE -->|FORBIDDEN| B1["Back button"]
    TYPE -->|TENANT_ACCESS_DENIED| B2{"Other tenants?"}
    B2 -->|Yes| B2a["Switch workspace"]
    B2 -->|No| B2b["Request invitation"]

    TYPE -->|TENANT_SUSPENDED| B3{"Other tenants?"}
    B3 -->|Yes| B3a["Switch to another workspace"]
    B3 -->|No| B3b["Contact admin"]

    TYPE -->|ROLE_INSUFFICIENT| C1["Back + request permission"]

    TYPE -->|INVITE_EXPIRED| D1["Ask inviter to resend"]
    TYPE -->|INVITE_EXHAUSTED| D2["Login link"]

    TYPE -->|RATE_LIMITED| E1["Countdown timer + auto retry"]

    TYPE -->|NOT_FOUND| F1["Go to top"]
    TYPE -->|INTERNAL_ERROR| F2["Try again + admin contact"]
```

---

## Full Screen Transition Map

```mermaid
flowchart TD
    START["Browser access"] --> FA{"ForwardAuth"}

    FA -->|"Session OK + Tenant OK"| APP["App display"]
    FA -->|"Session OK + No tenant"| SEL["/select-tenant"]
    FA -->|"Session OK + Suspended"| SUSP["Tenant suspended error"]
    FA -->|"Session invalid"| LOGIN["/login"]

    LOGIN --> GOOGLE["Google OIDC"]
    GOOGLE --> CB["/callback"]
    CB -->|"OK + invite"| ACCEPT["/invite/code/accept"]
    CB -->|"OK + 1 tenant"| APP
    CB -->|"OK + multi tenant"| SEL
    CB -->|"Failed"| ERR_AUTH["Auth error"]

    SEL --> APP
    ACCEPT --> APP

    APP -->|"JWT expired"| REFRESH{"/auth/refresh"}
    REFRESH -->|"OK"| APP
    REFRESH -->|"Failed"| LOGIN

    APP -->|"Logout"| LOGOUT["POST /auth/logout"]
    LOGOUT --> LOGIN

    APP -->|"Switch tenant"| SWITCH["POST /auth/switch-tenant"]
    SWITCH --> APP

    APP --> SESSIONS["/settings/sessions"]
    APP --> MEMBERS["/admin/members"]
    APP --> INVITES["/admin/invitations"]

    SUSP -->|"Other tenants"| SEL
    SUSP -->|"None"| CONTACT["Contact admin"]

    ERR_AUTH --> LOGIN

    INVITE_LINK["Invite link"] --> INVITE["/invite/code"]
    INVITE -->|"Valid + not logged in"| LOGIN
    INVITE -->|"Valid + logged in"| ACCEPT
    INVITE -->|"Expired"| ERR_EXP["Expired error"]
    INVITE -->|"Used"| ERR_USED["Already used error"]
```

---

## Browser Back Button Behavior

| Current Screen | Back goes to | Behavior | Mitigation |
|---------------|-------------|----------|------------|
| /login | Previous page | OK | - |
| Google login | /login | OK | - |
| /callback | Google login | State mismatch error | "Login again" button |
| /invite/code/accept | /callback | State mismatch error | Same as above |
| /select-tenant | Previous page | OK | - |
| App after logout | Cached page | API call returns 401 | Cache-Control: no-store |
| Error page | Previous page | OK | - |

---

## Gap List

### Found by DGE

| # | Gap | Severity | Mitigation |
|---|-----|----------|------------|
| FL-1 | Back button on /callback causes state mismatch | High | "Login again" button. return_to saved in session |
| FL-2 | Tenant suspended in /auth/verify flow | High | 403 + switch option or admin contact |
| FL-3 | Tenant switch atomicity | Medium | reload after POST success, no reload on failure |
| FL-4 | Browser cache after logout | Medium | Cache-Control: no-store, private on ForwardAuth |
| FL-5 | Session expiry during form input | Medium | SDK docs: recommend form auto-save |

### Found by LLM Code Review (Implementation Bugs)

| # | Gap | Severity | Mitigation |
|---|-----|----------|------------|
| FL-6 | Invite flow membership race: /callback findMembership fails for new users | **Critical** | Skip membership check when invite_code present. Create membership in /invite/code/accept |
| FL-7 | All templates are scaffolds: login/tenant-select/invite-consent/sessions are empty | **Critical** | Replace with full implementations |
| FL-8 | /admin/members and /admin/invitations routes missing from Main.java | **Critical** | Add route handlers |
| FL-9 | volta.js is empty: no client-side SDK logic | **Critical** | Implement volta-sdk-js |
| FL-10 | No CSRF protection on POST endpoints | High | CSRF token in jte forms + before handler validation |
| FL-11 | Invitation list API lacks ADMIN/OWNER role check | High | Add role enforcement |
| FL-12 | No protection against demoting last OWNER | High | Check OWNER count in updateMemberRole |
| FL-13 | No tenant list API for /select-tenant screen | High | Add GET /api/v1/users/me/tenants |
| FL-14 | /callback missing Cache-Control: no-store | High | Add response header |
| FL-15 | Default redirect after login is /settings/sessions instead of App URL | High | Use default App URL from volta-config.yaml |
| FL-16 | No invitation cancel API DELETE | High | Add DELETE /api/v1/tenants/tid/invitations/iid |
| FL-17 | Old session not invalidated on tenant switch | Medium | Invalidate old session in switch-tenant |
| FL-18 | Google session persists after logout | Medium | Acceptable in Phase 1. prompt=select_account mitigates |
| FL-19 | 429 response missing Retry-After header | Medium | Add response header |
| FL-20 | No flash message after invitation acceptance | Medium | Store flash in session, display on next page |
