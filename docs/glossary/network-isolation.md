# Network Isolation

[日本語版はこちら](network-isolation.ja.md)

---

## In one sentence?

Network isolation means setting up walls between groups of computers so they can only talk to the ones they're supposed to, preventing unauthorized access.

---

## The staff-only area in a restaurant

In a restaurant:

- **Dining area** -- Customers can sit here (public [network](network.md))
- **Kitchen** -- Only staff allowed (private network)
- **The door between them** -- Only waiters pass through (reverse proxy)

| Restaurant | volta deployment |
|---|---|
| Customers (diners) | Users' [browsers](browser.md) on the internet |
| Dining area | Public-facing [reverse proxy](reverse-proxy.md) |
| Kitchen door (waiters only) | [ForwardAuth](forwardauth.md) verification |
| Kitchen | [Docker](docker.md) internal network |
| Chef | volta-auth-proxy |
| Refrigerator | PostgreSQL database |
| Customers can't touch the fridge | Browsers can't query the database directly |

If anyone could walk into the kitchen, they could tamper with food (modify data), steal ingredients (read secrets), or open the back door for their friends (create backdoor access).

---

## Why do we need this?

Without network isolation:

- **Database exposed to the internet** -- Anyone could try to connect to PostgreSQL directly and brute-force the password
- **Internal APIs exposed** -- volta's Internal API (trusted, no auth) would be callable by anyone
- **Attack surface multiplied** -- Every service becomes a potential entry point
- **Lateral movement** -- If one service is compromised, the attacker can reach everything

Network isolation follows the **principle of least privilege**: each component can only talk to exactly what it needs.

---

## Network isolation in volta-auth-proxy

volta uses [Docker](docker.md) networks to create isolation:

```
  Internet
  ═══════════════════════════════════════
       │ (only port 443 exposed)
       ▼
  ┌─────────────────────────────────────┐
  │  Reverse Proxy (Traefik/Nginx)      │  ← connected to BOTH networks
  └─────────────────────────────────────┘
       │
  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
       │  Docker internal network
       ▼
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │ volta-auth   │  │  Your App    │  │  PostgreSQL   │
  │ :8080        │  │  :3000       │  │  :5432        │
  └──────────────┘  └──────────────┘  └──────────────┘
       NOT accessible from internet
```

What can talk to what:

| From | To | Allowed? | Why |
|---|---|---|---|
| Internet | Reverse proxy (:443) | Yes | This is the front door |
| Internet | volta (:8080) | **No** | volta must be behind the proxy |
| Internet | PostgreSQL (:5432) | **No** | Database must never be public |
| Reverse proxy | volta | Yes | ForwardAuth needs this |
| Reverse proxy | Your app | Yes | Proxying requests |
| volta | PostgreSQL | Yes | Session/user data storage |
| volta | Google APIs | Yes | [OIDC](oidc.md) authentication |
| Your app | volta Internal API | Yes | Getting user info via [headers](header.md) or API |
| Your app | PostgreSQL | **Depends** | Your app may have its own DB |

---

## Concrete example

Setting up network isolation with Docker Compose:

```yaml
# docker-compose.yml (simplified)
services:
  traefik:
    networks:
      - public      # accessible from internet
      - internal    # can reach volta and app

  volta-auth-proxy:
    networks:
      - internal    # NOT on public network
    # No "ports:" section = not exposed to host

  app:
    networks:
      - internal
    # No "ports:" section = not exposed to host

  postgres:
    networks:
      - internal
    # No "ports:" section = not exposed to host

networks:
  public:
    # Traefik binds 443 here
  internal:
    # Completely isolated from the internet
```

What happens if network isolation is missing:

1. Attacker scans your server's IP, finds [port](port.md) 5432 open
2. Attacker runs `psql -h your-server.com -U postgres` -- tries default passwords
3. If successful, attacker has full access to all session data, user data, and tenant data
4. Game over

With proper isolation, step 1 finds nothing -- port 5432 is only available inside the Docker network, which has no route from the internet.

---

## Learn more

- [Network](network.md) -- The basics of how computers connect
- [Docker](docker.md) -- The tool volta uses to create isolated networks
- [ForwardAuth](forwardauth.md) -- Authentication that works within the isolated network
- [Reverse Proxy](reverse-proxy.md) -- The gateway between public and private networks
- [Port](port.md) -- The numbered entry points that isolation controls
