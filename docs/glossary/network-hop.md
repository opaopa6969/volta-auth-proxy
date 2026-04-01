# Network Hop

[日本語版はこちら](network-hop.ja.md)

---

## What is it?

A network hop is each time data travels from one server (or process) to another across a network connection. Every hop adds time, introduces a potential failure point, and creates a spot where someone could intercept the data.

Think of it like passing a letter through intermediaries. You give the letter to Alice, who gives it to Bob, who gives it to Carol, who gives it to the recipient. Each handoff is a "hop." Each hop takes time (Alice has to find Bob). Each hop can fail (Bob might be on vacation). And each hop is a chance for someone to read the letter (Bob is nosy).

Sending the letter directly to the recipient -- one hop -- is faster, more reliable, and more private.

---

## Why fewer hops matter

### Speed

Every network hop adds latency. Even on a fast local network, each hop adds roughly 0.5-2ms for the round trip. That sounds tiny, but it adds up:

```
1 hop:   ~1ms    (fast enough to be invisible)
3 hops:  ~3-6ms  (noticeable at scale)
5 hops:  ~5-10ms (users start to feel it)
10 hops: ~10-20ms (clearly sluggish for auth)
```

And that is just network transit time. Each hop also requires:
- Serialization (converting data to bytes for sending)
- Deserialization (converting bytes back to data on arrival)
- Connection overhead (TCP handshake, TLS negotiation)
- Processing at each stop

Realistic per-hop cost including processing: 2-10ms.

### Reliability

Each hop is a potential point of failure. Networks are not perfectly reliable. Connections time out. DNS resolves incorrectly. Load balancers have bugs. Firewalls silently drop packets.

If each hop has a 99.9% success rate (which is good), then:

```
1 hop:   99.9% success rate
3 hops:  99.9% × 99.9% × 99.9% = 99.7%
5 hops:  99.9%^5 = 99.5%
10 hops: 99.9%^10 = 99.0%
```

At 10 hops, 1 in 100 requests will fail due to network issues. For an auth system that handles every request, that means 1 in 100 page loads will fail.

### Security

Every hop is a place where data is in transit and potentially exposed. Even with TLS encryption, each hop is a point where:
- The data is decrypted, processed, and re-encrypted
- The receiving server has access to the plain data
- A misconfigured server could log or leak sensitive information
- A compromised server could modify the data

Fewer hops means fewer places where auth tokens, session IDs, and user data are exposed.

---

## volta's ForwardAuth vs reverse proxy: a hop comparison

```
Full reverse proxy (NOT volta):
  Browser → Reverse Proxy → Auth Service → Backend App
  Hop 1: Browser to proxy
  Hop 2: Proxy to auth service
  Hop 3: Auth service back to proxy
  Hop 4: Proxy to backend app
  Total: 4 hops, auth service sees all traffic (including request body)

ForwardAuth (volta):
  Browser → Traefik → volta (auth check only)
                    → Backend App (actual request)
  Hop 1: Browser to Traefik
  Hop 2: Traefik to volta (headers only, no body)
  Hop 3: volta response to Traefik
  Hop 4: Traefik to backend app (original request with added headers)
  Total: 4 hops, BUT volta only sees headers (not the full request)
```

The hop count is similar, but the key difference is what travels over those hops. In a full reverse proxy, the entire request (including large POST bodies, file uploads) flows through the auth service. In ForwardAuth, volta only receives and returns headers -- a few hundred bytes instead of potentially megabytes.

And within volta itself, there are zero internal hops:

```
Microservice auth (internal hops):
  ForwardAuth endpoint → Session Service → JWT Service
  2 additional internal hops per request

volta (no internal hops):
  ForwardAuth endpoint → session check → JWT issue
  0 internal hops (all method calls in one process)
```

---

## In volta-auth-proxy

volta eliminates internal network hops by running all auth logic in a single process, and minimizes the data transmitted over external hops by using the ForwardAuth pattern where only headers (not request bodies) pass through the auth check.

---

## Further reading

- [latency.md](latency.md) -- How hops contribute to response time.
- [forwardauth.md](forwardauth.md) -- The pattern that minimizes data over hops.
- [fault-propagation.md](fault-propagation.md) -- Each hop is a failure point.
- [request-body.md](request-body.md) -- Why not sending the body over auth hops matters.
