# Rate Limiting

## What is it?

Rate limiting is the practice of controlling how many requests a client can make to your server within a given time period. If a client exceeds the limit, the server rejects additional requests (usually with a 429 "Too Many Requests" response) until the time window resets.

Think of it like a bouncer at a club with a capacity limit. Once the club is full, nobody else gets in until some people leave. Rate limiting does the same thing for your API, but per-client rather than globally.

```
  Client sends 200 requests per minute (limit is 200):

  Request 1   --> 200 OK
  Request 2   --> 200 OK
  ...
  Request 200 --> 200 OK
  Request 201 --> 429 Too Many Requests  (blocked!)
  Request 202 --> 429 Too Many Requests  (blocked!)

  (Next minute starts)
  Request 203 --> 200 OK  (counter reset)
```

## Why does it matter?

Rate limiting protects your application from several threats:

**Brute force attacks.** An attacker trying to guess passwords will attempt thousands of combinations. Rate limiting slows them down dramatically. At 200 attempts per minute, a 6-character password that would take seconds to crack now takes much longer.

**Denial of service.** Without rate limiting, a single client (or botnet) can overwhelm your server with requests, making it unavailable for legitimate users.

**Resource exhaustion.** Even non-malicious clients can accidentally create too much load -- a buggy script in an infinite loop, a misconfigured cron job, or a mobile app retrying failed requests too aggressively.

**Credential stuffing.** Attackers use lists of stolen username/password combinations from data breaches and try them against your login endpoint. Rate limiting makes this impractical at scale.

**Cost protection.** If your app calls external paid APIs (like AI services or SMS providers), a runaway client can rack up enormous bills.

## How does it work?

### Common algorithms

**Fixed window counter** (what volta uses):
Divide time into fixed windows (e.g., 1-minute intervals). Count requests per client in each window. Reset at the start of each window.

```
  Time:     |  Minute 1  |  Minute 2  |  Minute 3  |
  Requests: |  ||||||||| |  ||||      |  ||||||    |
  Count:    |  150       |  40        |  60        |
  Limit:    |  200       |  200       |  200       |
  Status:   |  OK        |  OK        |  OK        |
```

Drawback: burst at the boundary. A client could send 200 requests at the end of minute 1 and 200 at the start of minute 2, effectively getting 400 requests in a short period.

**Sliding window:**
Instead of fixed boundaries, track requests over a rolling time period. More accurate but more memory-intensive.

**Token bucket:**
Each client has a "bucket" that holds tokens. Tokens are added at a steady rate (e.g., 10 per second). Each request consumes a token. If the bucket is empty, the request is rejected. The bucket has a maximum capacity, allowing short bursts.

```
  Token bucket (capacity=10, refill=5/second):

  Time 0:  Bucket = 10 tokens
  Burst:   10 requests --> Bucket = 0 (all 10 served instantly)
  Time 1s: Bucket = 5  (refilled 5 tokens)
  Time 2s: Bucket = 10 (refilled 5 more, capped at 10)
```

**Leaky bucket:**
Similar to token bucket, but requests are processed at a constant rate. Excess requests queue up (up to a limit) rather than being immediately rejected.

### What to use as the key

| Key | When to use | Pros | Cons |
|-----|-------------|------|------|
| IP address | Unauthenticated endpoints (login page) | Simple, works before auth | Shared IPs (NAT, VPN) punish multiple users |
| User ID | Authenticated endpoints (API) | Accurate per-user limiting | Requires authentication first |
| API key | Machine-to-machine APIs | Clear accountability | Key sharing defeats it |
| IP + endpoint | Fine-grained control | Prevents abuse of specific endpoints | More complex to manage |

## How does volta-auth-proxy use it?

volta-auth-proxy implements rate limiting using a fixed-window counter with a `ConcurrentHashMap`.

**Implementation.** The `RateLimiter` class uses a fixed 1-minute window. Each minute boundary creates a new counter bucket per key:

- Requests within the same minute increment a shared counter.
- When a new minute starts, the counter resets to 1.
- If the counter exceeds the configured maximum (200 requests per minute), the request is denied.

**Configuration.** volta creates its rate limiter with a limit of 200 requests per minute:
```java
RateLimiter rateLimiter = new RateLimiter(200);
```

**Key selection.** volta uses the client IP address as the rate limit key. This is appropriate because rate limiting is most important for unauthenticated endpoints (like the login flow) where there is no user ID yet.

**Where it applies.** The rate limiter is checked before processing sensitive operations like login attempts, ensuring that brute force attacks against the OIDC flow are throttled.

The flow:

```
  Client (IP: 203.0.113.42)
  |
  | Request to /login
  |
  v
  RateLimiter.allow("203.0.113.42")
  |
  |-- Current minute bucket exists?
  |   |
  |   No:  Create new bucket, count=1, allow
  |   Yes: Increment count
  |         |
  |         count <= 200? --> Allow request
  |         count > 200?  --> Reject with 429
  |
  v
  Process request (if allowed)
```

**Thread safety.** volta's rate limiter uses `ConcurrentHashMap.compute()` for atomic counter updates. This is important because web servers handle multiple requests concurrently, and without thread-safe operations, the counter could be inaccurate.

**Memory management.** Old buckets are naturally replaced when a new minute starts (the `compute()` callback creates a new `Counter` when the bucket minute changes). This means stale entries are garbage collected as clients continue to make requests.

## Common mistakes

**1. Not rate limiting login endpoints.**
The login endpoint is the number one target for brute force attacks. If you only rate limit API endpoints, you are leaving the front door open.

**2. Rate limiting too generously.**
A limit of 10,000 requests per minute might as well not exist. Set limits based on what legitimate users actually need. For login endpoints, 10-20 attempts per minute per IP is reasonable.

**3. Using only IP-based rate limiting behind a reverse proxy.**
If all requests come through a load balancer or reverse proxy, they may all appear to come from the same IP. Make sure to use the `X-Forwarded-For` header to get the real client IP. volta does this via its `clientIp()` helper.

**4. Not returning proper 429 responses.**
When rate limiting kicks in, return a `429 Too Many Requests` status with a `Retry-After` header telling the client when to try again. Without this, clients might retry immediately, making things worse.

**5. Rate limiting at only one layer.**
Apply rate limiting at multiple levels: reverse proxy (Traefik/Nginx), application level (volta), and possibly even at the database query level. Each layer catches different types of abuse.

**6. Forgetting about distributed deployments.**
If your app runs on multiple servers, an in-memory rate limiter (like volta's `ConcurrentHashMap`) only counts requests that hit a particular server. For distributed rate limiting, use a shared store like Redis. volta's current implementation is designed for single-instance deployment.

**7. Not considering legitimate bursts.**
Some legitimate usage patterns involve short bursts (like a page load that triggers 20 API calls simultaneously). Token bucket algorithms handle this better than strict fixed-window counters by allowing bursts up to a maximum capacity.
