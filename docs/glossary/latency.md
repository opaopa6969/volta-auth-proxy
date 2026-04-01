# Latency

[日本語版はこちら](latency.ja.md)

---

## What is it?

Latency is the time between asking for something and getting the response -- the waiting time. In software, it is measured in milliseconds (ms), and it is the delay from when a user clicks a button to when they see the result.

Think of it like ordering coffee. You walk up to the counter, place your order, and wait. The time between saying "one latte, please" and receiving the cup is latency. If the barista makes it in 30 seconds, that is low latency. If there is a line of 20 people and it takes 10 minutes, that is high latency. The coffee tastes the same either way -- but your experience is very different.

---

## Why milliseconds matter for auth

Authentication sits on the critical path of every request. In a ForwardAuth setup like volta's, every single request to a protected application goes through this flow:

```
User clicks link
    → Browser sends request to Traefik          ~1ms
    → Traefik sends ForwardAuth check to volta   ~1ms
    → volta validates session + issues JWT        ~Xms  ← THIS IS LATENCY
    → Traefik forwards request to app            ~1ms
    → App processes and responds                 ~Yms
    → User sees the page

Total added by auth: ~2ms network + Xms volta processing
```

If volta's processing takes 5ms, the user barely notices. If it takes 500ms, every single page load feels sluggish. And because this happens on EVERY request (not just login), even small increases multiply across the entire user experience.

```
5ms auth latency × 20 requests per page load = 100ms added
50ms auth latency × 20 requests per page load = 1,000ms (1 second!) added
```

One second of latency per page load will make users leave your application.

---

## How network hops add latency

Every time data crosses a network boundary -- from one server to another -- it adds latency. This is called a "network hop."

```
Microservice auth (many hops):
  Traefik → ForwardAuth Service → Session Service → JWT Service
            ~1ms                  ~1ms               ~1ms
  Total network overhead: ~3ms minimum
  Plus processing time at each service
  Plus serialization/deserialization at each hop
  Realistic total: 15-50ms

volta (minimal hops):
  Traefik → volta-auth-proxy (does everything internally)
            ~1ms
  Total network overhead: ~1ms
  Plus processing time (one process, no serialization)
  Realistic total: 3-10ms
```

The difference matters. 3ms vs 50ms means volta can handle ForwardAuth checks fast enough that users never perceive the auth layer as slow.

---

## volta's 10ms ForwardAuth target

volta targets sub-10ms response time for ForwardAuth verification. Here is how:

1. **Single process.** No network hops between internal components. Session lookup, role check, and JWT issuance are method calls, not HTTP calls.

2. **In-memory caching.** Frequently accessed sessions can be cached in Caffeine (a Java in-memory cache), avoiding database round-trips for hot sessions.

3. **Minimal work per request.** ForwardAuth verification reads a session cookie, validates it, and returns headers. It does not parse request bodies, resolve complex policies, or call external services.

4. **Pre-computed JWTs.** JWTs are signed with a pre-loaded RSA key. No key fetching, no external JWKS calls during request handling.

```
ForwardAuth /auth/verify breakdown:
  Read cookie from headers:           ~0.01ms
  Session lookup (cache hit):         ~0.1ms
  Session lookup (DB, Postgres local): ~2-5ms
  Role authorization check:           ~0.01ms
  JWT creation + RS256 signing:       ~1-2ms
  Header assembly + response:         ~0.01ms
  ─────────────────────────────────────────
  Total (cache hit):                  ~1-3ms
  Total (DB hit):                     ~3-8ms
```

Both are well under 10ms. By comparison, an OAuth2 proxy that calls an external IdP on every request can easily take 100-500ms.

---

## Latency vs throughput

Latency and throughput are related but different:

- **Latency:** How long one request takes (time per request)
- **Throughput:** How many requests per second the system handles

You can have low latency but low throughput (a fast system that can only handle one request at a time). You can have high throughput but high latency (a system that handles thousands of requests per second, but each one takes 500ms).

volta optimizes for both: low latency (sub-10ms per request) and reasonable throughput (the JVM handles concurrent requests efficiently on a single instance).

---

## In volta-auth-proxy

volta targets sub-10ms ForwardAuth response time by running as a single process with no internal network hops, using in-memory caching for session lookups, and doing the minimum work necessary to validate a session and issue a JWT.

---

## Further reading

- [network-hop.md](network-hop.md) -- Why each network boundary adds delay.
- [forwardauth.md](forwardauth.md) -- The pattern that runs on every request.
- [caffeine-cache.md](caffeine-cache.md) -- The in-memory cache that keeps latency low.
- [single-process.md](single-process.md) -- Why one process means fewer hops.
