# Oathkeeper (Ory Oathkeeper)

[日本語版はこちら](oathkeeper.ja.md)

---

## What is it?

Ory Oathkeeper is an open-source identity and access proxy from the Ory ecosystem. It sits in front of your applications and decides whether to allow or deny incoming HTTP requests based on configurable rules. It can verify JWTs, check cookies, call external authorization services, and mutate requests (adding headers, rewriting tokens) before forwarding them downstream.

Think of it as a trained security guard at every door of an office building. The guard has a rulebook: "If they have a valid badge, let them in and stamp their hand. If they have an expired badge, send them to the front desk. If they have no badge at all, turn them away." Oathkeeper is that guard -- and the rulebook is a set of JSON or YAML rules you write.

---

## Why does it matter?

Oathkeeper is the access proxy component of the Ory stack (alongside Ory Kratos for identity, Ory Hydra for OAuth2, and Ory Keto for permissions). When teams evaluate self-hosted auth solutions, the Ory stack is a common candidate alongside [Keycloak](keycloak.md) and [Auth0](auth0.md).

Understanding Oathkeeper helps explain why volta-auth-proxy chose to implement its own [ForwardAuth](forwardauth.md) rather than adopting an existing proxy.

---

## How Oathkeeper works

Oathkeeper processes requests through a pipeline of four stages:

```
  Incoming request
       │
       ▼
  ┌──────────────────┐
  │ 1. Authenticator  │  "Who are you?"
  │    (cookie_session,│  Verify JWT, check cookie, call an API, etc.
  │     jwt, oauth2,   │
  │     anonymous...)  │
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐
  │ 2. Authorizer     │  "Are you allowed?"
  │    (allow, deny,  │  Check permissions via Ory Keto, or static allow/deny
  │     keto_engine)  │
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐
  │ 3. Mutator        │  "What should the downstream see?"
  │    (header, cookie,│  Add X-User-Id headers, issue JWTs, set cookies
  │     id_token...)  │
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐
  │ 4. Error Handler  │  "What to do on failure?"
  │    (redirect, json)│  Redirect to login, return 401 JSON, etc.
  └──────────────────┘
```

Each stage is configured per-rule. A single Oathkeeper deployment can have hundreds of rules, each with different authenticators, authorizers, and mutators.

---

## How it compares to volta's ForwardAuth

| Aspect | Oathkeeper | volta-auth-proxy |
|--------|-----------|-----------------|
| **Architecture** | Standalone proxy (separate process) | Embedded in the auth service itself |
| **Rule definition** | JSON/YAML rule files per route | `volta-config.yaml` with app-level `allowed_roles` |
| **Authentication** | Pluggable authenticators (JWT, cookie, OAuth2, anonymous) | Session cookie only (one path, deeply validated) |
| **Authorization** | Pluggable authorizers (Ory Keto, allow/deny) | Role-based per app (`allowed_roles` in config) |
| **Header mutation** | Configurable mutators | Fixed `X-Volta-*` header set |
| **Tenant awareness** | None (tenant is not a concept) | Native multi-tenant (tenant resolved per request) |
| **JWT issuance** | Can issue JWTs via `id_token` mutator | Issues RS256 JWTs with user/tenant/role claims |
| **Dependencies** | Needs Ory Kratos + Ory Hydra + Ory Keto for full stack | Needs only PostgreSQL |
| **Configuration** | Dozens of rules, each with 4-stage pipeline | One YAML file + `.env` |

---

## Why volta does not use Oathkeeper

### 1. It is one piece of a four-piece stack

Oathkeeper does not handle user authentication itself. It delegates to Ory Kratos (for identity/login), Ory Hydra (for OAuth2/OIDC), and Ory Keto (for permissions). Running the full Ory stack means operating four separate services:

```
  Ory Stack (full):
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │ Oathkeeper   │  │ Kratos       │  │ Hydra        │  │ Keto         │
  │ (proxy)      │  │ (identity)   │  │ (OAuth2)     │  │ (permissions)│
  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘
       +                  +                 +                  +
  ┌─────────────────────────────────────────────────────────────────────┐
  │ PostgreSQL (shared or separate databases)                          │
  └─────────────────────────────────────────────────────────────────────┘

  volta Stack:
  ┌──────────────┐
  │ volta-auth-  │
  │ proxy        │
  │ (everything) │
  └──────────────┘
       +
  ┌──────────────┐
  │ PostgreSQL   │
  └──────────────┘
```

volta chose to keep everything in one process. One thing to deploy, one thing to monitor, one thing to understand.

### 2. No native multi-tenancy

Oathkeeper has no concept of tenants. It processes rules per URL pattern, not per tenant. If you need multi-tenant behavior (resolving which tenant a request belongs to, checking tenant-scoped roles, issuing tenant-specific JWTs), you have to build that logic yourself -- either in a custom authorizer or in your application. volta's ForwardAuth has tenant resolution built into every request.

### 3. Configuration overhead

A typical Oathkeeper rule file for a single route:

```json
{
  "id": "wiki-app-rule",
  "upstream": { "url": "http://wiki-app:8080" },
  "match": {
    "url": "https://wiki.example.com/<**>",
    "methods": ["GET", "POST", "PUT", "DELETE"]
  },
  "authenticators": [
    { "handler": "cookie_session",
      "config": { "check_session_url": "http://kratos:4433/sessions/whoami" } }
  ],
  "authorizer": { "handler": "allow" },
  "mutators": [
    { "handler": "header",
      "config": { "headers": { "X-User-Id": "{{ print .Subject }}" } } }
  ],
  "errors": [
    { "handler": "redirect",
      "config": { "to": "https://auth.example.com/login" } }
  ]
}
```

Multiply this by every route in every app. Then add the Kratos configuration, the Hydra configuration, and the Keto policies. volta replaces all of this with:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

### 4. volta's ForwardAuth is simpler because it knows more

Oathkeeper is generic -- it can work with any auth system. That generality requires configuration. volta's ForwardAuth is specific -- it knows about sessions, tenants, roles, and JWTs because it is the auth system. When you own both the proxy and the identity logic, you do not need a separate proxy at all.

---

## When Oathkeeper makes sense

- You are already using the Ory stack (Kratos + Hydra + Keto)
- You need a proxy that works with multiple different authentication backends
- You need fine-grained per-route rules with different auth strategies
- You have an ops team comfortable running microservices

---

## When volta makes more sense

- You want one service, not four
- You need multi-tenancy as a first-class concept
- You prefer a 20-line YAML to dozens of rule files
- You want to understand every line of your auth stack

---

## Further reading

- [forwardauth.md](forwardauth.md) -- How volta implements the ForwardAuth pattern.
- [keycloak.md](keycloak.md) -- Another alternative volta evaluated and chose not to use.
- [identity-gateway.md](identity-gateway.md) -- The broader concept of identity gateways.
- [config-hell.md](config-hell.md) -- Why more configuration is not always better.
