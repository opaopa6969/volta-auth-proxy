# Reverse Proxy

[日本語版はこちら](reverse-proxy.ja.md)

---

## What is it in one sentence?

A reverse proxy is a server that sits in front of your applications, receives all incoming requests from the internet, and forwards them to the right application behind the scenes -- like a receptionist who directs visitors to the right office.

---

## The receptionist analogy

Imagine a large company building with many departments inside, but only one front desk:

```
  Visitor arrives: "I need to talk to the engineering team."

  Receptionist (reverse proxy):
    1. "Welcome! Let me check which floor engineering is on."
    2. "Engineering is on floor 3. I'll send you there."
    3. The visitor goes to floor 3 and talks to the engineering team.

  The visitor never needed to know the floor number.
  The receptionist handled the routing.
```

Now imagine another visitor: "I need to see the CEO."

```
  Receptionist:
    1. "Do you have an appointment?" (checks authorization)
    2. "No? I'm sorry, you can't go up without an appointment."
    3. The visitor is turned away before reaching the CEO's floor.
```

A reverse proxy does the same thing for web applications:
- It receives all requests from users
- It decides which application should handle each request
- It can block unauthorized requests before they reach the application
- The user never needs to know where the applications actually are

---

## Why is it called "reverse"?

There are two kinds of proxies:

**Forward proxy** -- acts on behalf of the user. Example: a VPN that hides your identity when you visit websites. The website does not know who you are.

**Reverse proxy** -- acts on behalf of the server. Example: Traefik sitting in front of your apps. The user does not know which server they are talking to.

```
  Forward proxy (protects the user):
  User → [Forward Proxy] → Internet → Server
  "The server doesn't know who the user really is"

  Reverse proxy (protects the server):
  User → Internet → [Reverse Proxy] → Server A, Server B, Server C
  "The user doesn't know which server they're really talking to"
```

It is called "reverse" because it is the opposite direction from a forward proxy.

---

## How Traefik works with volta

In a typical volta setup, [Traefik](https://traefik.io/) is used as the reverse proxy. Traefik is a popular, lightweight reverse proxy that is especially good at working with Docker.

Here is how the pieces fit together:

```
  User's browser visits wiki.example.com
       │
       ▼
  Traefik (reverse proxy)
       │
       │  "wiki.example.com? Let me check my routing rules..."
       │  "Rule: wiki.example.com → forward to wiki app on port 8080"
       │
       │  But first! "ForwardAuth is enabled for this route."
       │  "Let me check with volta-auth-proxy first..."
       │
       ├──→ volta-auth-proxy (ForwardAuth check)
       │    volta: "Is this user logged in? What tenant? What role?"
       │    volta: "Yes, this is Taro, MEMBER of ACME. Here are the headers."
       │    volta returns: 200 OK + identity headers
       │
       │  Traefik: "volta approved! Adding the identity headers to the request."
       │
       └──→ wiki app (port 8080)
            Receives request with X-Volta-User-Id, X-Volta-Tenant-Id, etc.
```

Traefik handles the networking (which request goes where), and volta handles the identity (who is making the request and are they allowed).

---

## Why apps sit behind a proxy

There are several reasons why you do not expose your applications directly to the internet:

1. **Security** -- The proxy can block malicious requests before they reach your app. Your app only receives pre-screened traffic.

2. **Authentication** -- With volta's ForwardAuth, the proxy can check if the user is logged in before forwarding the request. Unauthenticated users never reach your app.

3. **Routing** -- One domain can host many apps. `wiki.example.com` goes to the wiki, `admin.example.com` goes to the admin panel. The proxy routes traffic based on the URL.

4. **SSL/TLS** -- The proxy handles HTTPS encryption. Your apps do not need to manage certificates individually.

5. **Load balancing** -- If you have multiple copies of an app running, the proxy can distribute requests evenly between them.

---

## A simple example

Say you have two apps: a wiki and an admin panel. Without a reverse proxy, each app needs its own public address:

```
  Without reverse proxy:
  wiki.example.com:8080   → Wiki app
  admin.example.com:9090  → Admin app
  (Users need to know port numbers. Ugly and insecure.)
```

With a reverse proxy (Traefik):

```
  With reverse proxy:
  wiki.example.com    → Traefik → Wiki app (port 8080, hidden)
  admin.example.com   → Traefik → Admin app (port 9090, hidden)
  (Users just visit clean URLs. Traefik handles the rest.)
```

And with volta's ForwardAuth on top:

```
  With reverse proxy + volta:
  wiki.example.com    → Traefik → volta check → Wiki app
  admin.example.com   → Traefik → volta check → Admin app
  (Every request is authenticated before reaching any app.)
```

---

## Further reading

- [forwardauth.md](forwardauth.md) -- How volta integrates with Traefik's ForwardAuth mechanism.
- [downstream-app.md](downstream-app.md) -- The apps that sit behind the reverse proxy.
- [identity-gateway.md](identity-gateway.md) -- volta's role in the overall architecture.
