# Proxy Types: Forward, Reverse, and Auth

[日本語版はこちら](proxy-types.ja.md)

---

## What is it?

A proxy is a server that acts as an intermediary -- it sits between two parties and handles communication on behalf of one of them. There are several types of proxies, and they differ in who they represent and what they do.

Think of it in terms of people at a company:

- **Forward proxy = your secretary.** When you want to send a letter to someone outside, your secretary sends it on your behalf. The recipient sees the secretary's return address, not yours. The secretary protects YOUR identity.

- **Reverse proxy = the company's receptionist.** When someone from outside wants to reach a department inside, they talk to the receptionist, who routes them to the right person. The visitor never sees the internal office layout. The receptionist protects THE COMPANY's internal structure.

- **Auth proxy = the security guard.** Before anyone enters the building, the guard checks their badge. The guard does not route people or hide identities -- they just verify "are you allowed in?"

---

## Forward proxy

A forward proxy acts on behalf of the client (the user). It sits between the user and the internet.

```
User → [Forward Proxy] → Internet → Server

The server does not know who the real user is.
The proxy hides the user's identity.
```

**Real-world examples:**
- A corporate VPN that lets employees access the internet through the company's network
- A web proxy that bypasses geographic content restrictions
- Tor, which routes traffic through multiple proxies to anonymize the user

**Who it protects:** The client (user).

volta-auth-proxy is NOT a forward proxy. It does not hide user identity from servers -- it reveals it (via X-Volta-* headers).

---

## Reverse proxy

A reverse proxy acts on behalf of the server (the application). It sits between the internet and the server.

```
User → Internet → [Reverse Proxy] → Server A, B, C

The user does not know which server they are talking to.
The proxy hides the server's internal structure.
```

**Real-world examples:**
- Traefik routing `wiki.example.com` to one server and `admin.example.com` to another
- Nginx distributing traffic across 10 identical app servers (load balancing)
- Cloudflare sitting in front of your website to block attacks and cache content

**Who it protects:** The server (application).

In volta's architecture, Traefik is the reverse proxy. It receives all incoming traffic and routes it to the correct backend application.

---

## Auth proxy

An auth proxy is a specialized service that answers one question: "Is this request authorized?" It does not route traffic. It does not hide identities. It just checks credentials and says yes or no.

```
User → [Reverse Proxy] → asks → [Auth Proxy]: "Is this OK?"
                                  Auth Proxy: "Yes, here's who they are."
       [Reverse Proxy] → forwards to → App (with identity headers)
```

There are two patterns for auth proxies:

### Pattern 1: Full reverse proxy with auth (NOT volta)

The auth proxy sits in the traffic path. ALL data flows through it:

```
User → [Auth Proxy] → App

The auth proxy sees everything: headers, body, response.
It is both a reverse proxy and an auth checker.
Problems: bottleneck, privacy risk, complexity.
```

Examples: Ory Oathkeeper (in proxy mode), some API gateways.

### Pattern 2: ForwardAuth sidecar (volta)

The auth proxy sits beside the reverse proxy, not in the traffic path. It only sees headers:

```
User → [Traefik] → App
           │
      [volta-auth-proxy]
      (headers only)
```

The auth proxy is consulted, not traversed. Traffic flows directly from Traefik to the app. volta only receives headers and returns a yes/no with identity information.

---

## Where volta fits

```
                    Forward     Reverse     Auth
                    Proxy       Proxy       Proxy
Protects:           Client      Server      Access
Sees traffic:       All         All         Headers only (ForwardAuth)
In volta's setup:   N/A         Traefik     volta-auth-proxy
```

volta-auth-proxy is an auth proxy using the ForwardAuth pattern. It does not route traffic (Traefik does that). It does not hide identities (it reveals them). It does not see request bodies (only headers). Its single job is answering "Is this request from an authenticated, authorized user?" and providing identity information when the answer is yes.

---

## What "proxy" means in volta-auth-proxy

The name "volta-auth-proxy" can be confusing because volta is not a traditional proxy. It does not sit in the traffic path. A more accurate name might be "volta-auth-service" or "volta-auth-checker." But "auth proxy" is the industry-standard term for a service that handles authentication decisions on behalf of applications, regardless of whether it is in the traffic path or beside it.

The "proxy" in volta-auth-proxy means: "volta handles auth SO THAT your apps do not have to." It proxies the responsibility, not the traffic.

---

## In volta-auth-proxy

volta is an auth proxy using the ForwardAuth pattern -- it sits beside Traefik (the reverse proxy), receives only headers, and answers "is this request authorized?" without ever seeing or routing the actual traffic.

---

## Further reading

- [reverse-proxy.md](reverse-proxy.md) -- Traefik's role in volta's architecture.
- [forwardauth.md](forwardauth.md) -- The specific pattern volta uses.
- [request-body.md](request-body.md) -- Why volta never sees the request body.
- [network-hop.md](network-hop.md) -- How the ForwardAuth pattern minimizes hops.
