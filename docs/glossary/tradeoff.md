# Trade-off

[日本語版はこちら](tradeoff.ja.md)

---

## What is it?

A trade-off is a situation where improving one thing necessarily makes another thing worse. You cannot have everything. Every choice has a cost. Engineering is the art of choosing which costs to accept.

Think of it like choosing a place to live. A big house in the suburbs gives you space but costs you commute time. A small apartment in the city gives you convenience but costs you space. You cannot have a big house with a zero-minute commute at a low price. You must choose what matters most and accept what you lose.

---

## Why does it matter?

Software engineering is made of trade-offs. Fast or reliable? Simple or flexible? Cheap or feature-rich? Teams that pretend they can have everything end up with systems that are slow, complex, expensive, AND unreliable -- because they never made a conscious choice about what to sacrifice.

volta-auth-proxy is interesting because it is honest about its trade-offs. It does not pretend to be everything. It says: "Here is what you get. Here is what you give up. Choose accordingly."

---

## volta's specific trade-offs

### Trade-off 1: Control vs convenience

```
  Auth0 / Clerk:                       volta-auth-proxy:
  ┌──────────────────────┐             ┌──────────────────────┐
  │ High convenience     │             │ High control         │
  │                      │             │                      │
  │ ✓ Dashboard setup    │             │ ✓ Full source code   │
  │ ✓ SDKs for everything│             │ ✓ Modify any behavior│
  │ ✓ Managed hosting    │             │ ✓ Own your data      │
  │ ✓ Security team      │             │ ✓ No vendor lock-in  │
  │                      │             │                      │
  │ ✗ Can't self-host    │             │ ✗ You host it        │
  │ ✗ Can't modify code  │             │ ✗ You configure it   │
  │ ✗ Pricing scales     │             │ ✗ You secure it      │
  │ ✗ Vendor lock-in     │             │ ✗ You maintain it    │
  └──────────────────────┘             └──────────────────────┘
```

volta trades convenience for control. You cannot click a button to set up auth in 5 minutes. But you can read every line of code, modify any behavior, and never worry about a vendor changing terms.

### Trade-off 2: Security responsibility vs freedom

This is the biggest trade-off. When you use Auth0, their security team monitors for vulnerabilities, responds to CVEs, and patches exploits. You pay $2,400/month, and part of that buys their security expertise.

When you use volta, **you are the security team.**

```
  Auth0:                               volta:
  ┌──────────────────────┐             ┌──────────────────────┐
  │ CVE in auth library? │             │ CVE in auth library? │
  │ Auth0 patches it.    │             │ YOU patch it.        │
  │ You do nothing.      │             │ You update deps.     │
  │                      │             │ You redeploy.        │
  │                      │             │ You verify the fix.  │
  └──────────────────────┘             └──────────────────────┘
```

This is not a small thing. Security responsibility is real work. volta makes this trade-off consciously: $0/month, but you own the security burden.

### Trade-off 3: Features vs simplicity

volta deliberately does less than Keycloak:

| Feature | Keycloak | volta |
|---------|----------|-------|
| SAML 2.0 IdP | Yes | Phase 3 (planned) |
| LDAP/AD federation | Yes | No |
| Custom auth flows | Yes (complex) | No (one flow: OIDC + PKCE) |
| User self-registration | Yes | Via OIDC only (Google handles it) |
| Password management | Yes | No (delegated to IdP) |
| Account management portal | Yes | No (via API) |

volta chose simplicity. Fewer features means less code, fewer bugs, faster startup, easier understanding. But if you need LDAP federation tomorrow, volta cannot do it today.

### Trade-off 4: Opinionated vs flexible

volta makes choices for you:

| Decision | volta's choice | Alternative |
|----------|---------------|-------------|
| JWT algorithm | RS256 only | Could support RS384, RS512, ES256... |
| Session timeout | 8-hour sliding window | Could be configurable per tenant |
| Max sessions | 5 per user | Could be configurable |
| JWT expiry | 5 minutes | Could be configurable |
| OIDC flow | Authorization Code + PKCE | Could support Implicit, Hybrid |

Each "could" is configuration that someone must learn, document, test, and debug. volta says: "RS256 is the right choice. 8 hours is the right timeout. We decided, so you do not have to."

The trade-off: if volta's opinion is wrong for your use case, you must modify the source code. There is no setting to change.

### Trade-off 5: Single-process vs distributed

volta runs as a single process. This is simple but limits horizontal scaling:

```
  volta (single process):
  ┌──────────────────┐
  │ volta-auth-proxy  │  One process does everything.
  │ + Postgres        │  Simple. Limited scaling.
  └──────────────────┘

  Distributed (e.g., Ory):
  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐
  │Proxy │ │Login │ │OAuth │ │Perms │  Four processes.
  │      │ │      │ │      │ │      │  Complex. Scalable.
  └──────┘ └──────┘ └──────┘ └──────┘
```

For most SaaS products with thousands (not millions) of users, a single volta instance behind a single PostgreSQL instance is more than enough. But if you are building the next Slack with 10 million daily active users, volta's architecture will need adaptation.

---

## How to think about trade-offs

The worst engineering decisions happen when teams refuse to acknowledge trade-offs:

- "We want Auth0's convenience AND self-hosting AND zero cost" -- pick two.
- "We want Keycloak's features AND volta's simplicity" -- impossible.
- "We want maximum security AND zero operational effort" -- pay Auth0 or do the work.

The best engineering decisions happen when teams clearly state:

- "We value **control** over convenience. We accept the hosting burden."
- "We value **simplicity** over features. We accept the limitations."
- "We value **understanding** over speed. We accept the learning investment."

volta is for teams that make these specific choices. If your priorities are different, a different tool is the right tool.

---

## Further reading

- [self-hosting.md](self-hosting.md) -- The trade-offs of hosting your own auth.
- [vendor-lock-in.md](vendor-lock-in.md) -- The trade-off of convenience for lock-in.
- [config-hell.md](config-hell.md) -- The trade-off of flexibility for complexity.
- [keycloak.md](keycloak.md) -- A tool with different trade-offs.
- [auth0.md](auth0.md) -- Another tool with different trade-offs.
