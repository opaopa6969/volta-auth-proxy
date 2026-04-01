# Interstitial

[日本語版はこちら](interstitial.ja.md)

---

## What is it?

An interstitial is an intermediate page shown between two actions in a flow. It is not the starting point and not the final destination -- it is a brief stop in the middle. Interstitials are used to show the user important information, ask for confirmation, or handle technical transitions before proceeding to the real destination.

Think of it like an airport transit lounge. You have left your departure gate but have not arrived at your destination. You are in an in-between space where something is happening (customs check, boarding pass scan) before you can continue. The transit lounge is not where you want to be -- it is a necessary step to get where you are going.

In volta-auth-proxy, interstitials appear at several points in the auth flow: the OIDC callback page, the invitation consent screen, the tenant selection page, and the "no tenant" page. Each is a brief pause between authentication and the user's final destination.

---

## Why does it matter?

Without interstitials:

- **Lost context**: The OIDC callback returns a `code` parameter. Something must exchange it for tokens before redirecting the user. Without an interstitial, this logic has nowhere to live
- **No consent**: Users would be automatically added to tenants via invitation without seeing what they are joining
- **No choice**: Users with multiple tenants would be dropped into a random one without being asked
- **Abrupt UX**: Users see either the login page or the app, with no transition or explanation in between

---

## How does it work?

### Types of interstitials

```
  ┌────────────────────────────────────────────────────────┐
  │ Type        │ Purpose               │ User sees         │
  │─────────────│───────────────────────│───────────────────│
  │ Technical   │ Exchange OIDC code    │ Brief loading page│
  │             │ for tokens            │ (or nothing)      │
  │─────────────│───────────────────────│───────────────────│
  │ Consent     │ Ask user to confirm   │ "Join workspace   │
  │             │ an action             │ Acme Corp?"       │
  │─────────────│───────────────────────│───────────────────│
  │ Selection   │ User chooses from     │ List of           │
  │             │ options               │ workspaces        │
  │─────────────│───────────────────────│───────────────────│
  │ Informational│ Tell user something  │ "No workspaces.   │
  │             │ important             │ Ask for invite."  │
  └────────────────────────────────────────────────────────┘
```

### Interstitial flow pattern

```
  Action A                Interstitial               Action B
  ┌──────────┐           ┌──────────────┐           ┌──────────┐
  │ OIDC     │  redirect │ Callback     │  redirect │ App      │
  │ Provider │ ─────────►│ Processing   │ ─────────►│ Dashboard│
  │ (Google) │           │ (interstitial)│           │          │
  └──────────┘           └──────────────┘           └──────────┘
                          │
                          ├── Exchange code for tokens
                          ├── Upsert user in database
                          ├── Create session
                          └── Determine next destination
```

---

## How does volta-auth-proxy use it?

### OIDC callback interstitial

After Google authenticates the user, it redirects to `/callback?code=...&state=...`. This is a technical interstitial -- the user sees it briefly (or not at all) while volta:

1. Validates the state and nonce parameters
2. Exchanges the authorization code for tokens
3. Creates or updates the user record
4. Creates a session
5. Determines the next destination (app, tenant select, invite consent, or no-tenant)

```yaml
# auth-machine.yaml — AUTH_PENDING state
callback_success:
  trigger: "GET /callback"
  guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"
  actions:
    - { type: side_effect, action: upsert_user }
    - { type: side_effect, action: delete_oidc_flow }
    - { type: side_effect, action: create_session }
    - { type: audit, event: LOGIN_SUCCESS }
  next_if:
    - guard: "invite.present && invite.valid"
      next: INVITE_CONSENT          # → invitation interstitial
    - guard: "user.tenant_count == 1"
      next: AUTHENTICATED           # → straight to app (no interstitial)
    - guard: "user.tenant_count > 1"
      next: TENANT_SELECT           # → selection interstitial
    - guard: "user.tenant_count == 0"
      next: NO_TENANT               # → informational interstitial
```

### Invitation consent interstitial

When a user has a pending invitation, they see a consent page before joining:

```
  ┌─────────────────────────────────────────┐
  │                                         │
  │   You have been invited to join         │
  │                                         │
  │   ┌─────────────────────────────────┐   │
  │   │  Acme Corporation               │   │
  │   │  Role: MEMBER                   │   │
  │   │  Invited by: admin@acme.com     │   │
  │   └─────────────────────────────────┘   │
  │                                         │
  │   [ Accept ]     [ Decline ]            │
  │                                         │
  └─────────────────────────────────────────┘
```

This maps to the INVITE_CONSENT state:

```yaml
INVITE_CONSENT:
  transitions:
    show_consent:
      trigger: "GET /invite/{code}/accept"
      actions:
        - { type: http, action: render_html, template: invite-consent }
      next: INVITE_CONSENT

    accept:
      trigger: "POST /invite/{code}/accept"
      guard: "invite.valid && !invite.expired && !invite.used && invite.email_match"
      actions:
        - { type: guard_check, check: csrf_token_valid }
        - { type: side_effect, action: create_membership }
        - { type: side_effect, action: consume_invitation }
        - { type: http, action: redirect, target: "{config.default_app_url}" }
      next: AUTHENTICATED
```

### Tenant selection interstitial

Users with multiple tenants see a selection page:

```
  ┌─────────────────────────────────────────┐
  │                                         │
  │   Select a workspace                    │
  │                                         │
  │   ┌─────────────────────────────────┐   │
  │   │  ● Acme Corporation            │   │
  │   │  ○ Side Project LLC            │   │
  │   │  ○ Open Source Org             │   │
  │   └─────────────────────────────────┘   │
  │                                         │
  │   [ Continue ]                          │
  │                                         │
  └─────────────────────────────────────────┘
```

### No-tenant interstitial

Users with no tenant membership see an informational page:

```
  ┌─────────────────────────────────────────┐
  │                                         │
  │   No workspaces yet                     │
  │                                         │
  │   You are not a member of any           │
  │   workspace. Ask your administrator     │
  │   to send you an invitation.            │
  │                                         │
  │   Contact: support@example.com          │
  │                                         │
  └─────────────────────────────────────────┘
```

---

## Common mistakes and attacks

### Mistake 1: Skippable interstitials

If a user can bypass the invitation consent page by navigating directly to the app, the consent interstitial is useless. volta enforces state machine transitions -- you must go through INVITE_CONSENT to reach AUTHENTICATED when an invitation is pending.

### Mistake 2: Interstitials that block forever

The OIDC callback interstitial has a 600-second timeout (defined in AUTH_PENDING). Without this, a user who never completes the callback would have a hanging OIDC flow forever.

### Mistake 3: Leaking information in interstitials

The invitation consent page shows the tenant name and inviter. It should NOT show other members, internal details, or the invitation code in the URL after consumption. volta shows only what the user needs to make a decision.

### Attack: CSRF on consent interstitials

An attacker crafts a page that auto-submits the invitation acceptance form. volta prevents this with a [CSRF](csrf.md) token check (`guard_check: csrf_token_valid`) on the POST action.

---

## Further reading

- [state-machine.md](state-machine.md) -- The states that represent interstitials.
- [invitation-flow.md](invitation-flow.md) -- The invitation consent interstitial.
- [tenant.md](tenant.md) -- The tenant selection interstitial.
- [cookie.md](cookie.md) -- Session cookies set during interstitials.
- [nonce.md](nonce.md) -- Nonce validated during the callback interstitial.
- [csrf.md](csrf.md) -- CSRF protection on consent forms.
