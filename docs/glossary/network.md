# Network

[日本語版はこちら](network.ja.md)

---

## In one sentence?

A network is a group of computers connected together so they can send data to each other -- from a home Wi-Fi to the entire internet.

---

## A neighborhood of houses with roads

Imagine computers are houses. A network is the road system connecting them:

- **Home network** -- A cul-de-sac with 3-4 houses (your phone, laptop, smart TV) connected by a single router
- **Office network** -- A neighborhood with dozens of houses (workstations, servers, printers) connected by switches
- **The Internet** -- The entire country's road system connecting millions of neighborhoods together

| Real world | Network |
|---|---|
| Houses | Computers, phones, [servers](server.md) |
| Street address | IP address (e.g., `192.168.1.5`) |
| Road connecting houses | Ethernet cable or Wi-Fi signal |
| Highway connecting cities | Internet backbone (fiber optic cables) |
| Traffic rules | [Protocols](protocol.md) (TCP/IP, [HTTP](http.md)) |
| A gated community | [Network isolation](network-isolation.md) |

---

## Why do we need this?

Without networks:

- Every computer would be an island -- no email, no web, no cloud services
- You'd have to physically walk a USB drive between computers to share files
- [Servers](server.md) couldn't serve web pages to anyone
- [Browsers](browser.md) would have nothing to connect to
- Modern SaaS applications simply couldn't exist

Networks are the foundation everything else runs on. [HTTP](http.md), [cookies](cookie.md), [sessions](session.md), [JWTs](jwt.md) -- all of it travels over a network.

---

## Network in volta-auth-proxy

volta operates across multiple network boundaries:

```
  Internet (public network)
  ┌──────────────────────────────────────────────┐
  │  User's browser                              │
  │  ↕ HTTPS (encrypted via SSL/TLS)             │
  │  Reverse Proxy (Traefik/Nginx)               │
  └──────────────────────────────────────────────┘
           │
  Docker network (private/isolated)
  ┌──────────────────────────────────────────────┐
  │  Reverse Proxy ←→ volta-auth-proxy           │
  │  Reverse Proxy ←→ Your App                   │
  │  volta-auth-proxy ←→ PostgreSQL              │
  │  volta-auth-proxy ←→ Google (for OIDC)       │
  └──────────────────────────────────────────────┘
```

Key network concepts in volta:

- **Public vs private** -- The [browser](browser.md) talks to the reverse proxy over the public internet. volta-auth-proxy and PostgreSQL live on a private [Docker](docker.md) network that the internet cannot reach directly.
- **[Network isolation](network-isolation.md)** -- volta-auth-proxy should NOT be directly accessible from the internet. Only the reverse proxy should be able to reach it.
- **[Port](port.md) exposure** -- volta listens on a [port](port.md) (e.g., 8080) but that port is only exposed within the Docker network, not to the outside.
- **[ForwardAuth](forwardauth.md)** -- This pattern works entirely within the private network. The reverse proxy makes an internal network call to volta before forwarding the request to your app.

---

## Concrete example

Network flow when a user accesses a volta-protected app:

1. User's [browser](browser.md) is on their home Wi-Fi network
2. Browser sends HTTPS request over the internet to `app.acme.example.com`
3. DNS resolves the [domain](domain.md) to the server's public IP (e.g., `203.0.113.10`)
4. Request arrives at the [reverse proxy](reverse-proxy.md) on [port](port.md) 443
5. Reverse proxy is on both the public network AND the private Docker network
6. Reverse proxy makes an internal request to volta at `http://volta:8080/auth/verify` (private network, no [SSL/TLS](ssl-tls.md) needed)
7. volta queries PostgreSQL at `postgres:5432` (also private network)
8. volta responds to the reverse proxy with auth [headers](header.md)
9. Reverse proxy forwards the original request to the app at `http://app:3000` (private network)
10. Response travels back through the reverse proxy to the user's browser over the internet

Steps 6-9 happen entirely within the private network. The [browser](browser.md) never sees or communicates with volta directly.

---

## Learn more

- [Network Isolation](network-isolation.md) -- Restricting which computers can talk to each other
- [Protocol](protocol.md) -- The rules for how computers communicate on a network
- [SSL/TLS](ssl-tls.md) -- Encryption for data traveling over a network
- [Port](port.md) -- The numbered doors on a networked computer
- [Docker](docker.md) -- How volta creates isolated networks for its services
- [ForwardAuth](forwardauth.md) -- Authentication that happens within the private network
