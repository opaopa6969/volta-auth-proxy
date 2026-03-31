# Brute-Force Attack

[Japanese / 日本語](brute-force.ja.md)

---

## What is it?

A brute-force attack is the simplest form of attack: trying every possible combination until one works. For passwords, this means trying "aaa", "aab", "aac"... through every combination. For tokens, it means generating random values and submitting them. It is not clever -- it is persistent. Given enough time and no defenses, brute force always works eventually.

---

## Why does it matter?

Brute-force attacks are the bread and butter of automated attack tools. Every server connected to the internet faces a constant stream of login attempts from bots. Without rate limiting, an attacker can try thousands of passwords per second. Even strong passwords fall if the attacker has unlimited attempts. Rate limiting is the primary defense: by restricting how many attempts one IP address (or one account) can make per minute, you make brute force impractically slow.

---

## Simple example

Without rate limiting:
```
Attacker (1 IP) -> tries 10,000 passwords/minute
Server          -> checks each one... eventually one works
```

With rate limiting (200 requests/minute):
```
Attacker (1 IP) -> request 1-200: allowed
                -> request 201+: HTTP 429 Too Many Requests
                -> must wait for next minute window
```

At 200 attempts per minute, trying all 6-character lowercase passwords (308 million combinations) would take about 2.9 years. With a reasonable password, brute force becomes infeasible.

---

## In volta-auth-proxy

volta implements per-IP rate limiting using `RateLimiter`:

```java
public final class RateLimiter {
    private final int maxPerMinute;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        long bucket = Instant.now().getEpochSecond() / 60;
        Counter counter = counters.compute(key, (k, v) -> {
            if (v == null || v.bucketMinute != bucket) {
                return new Counter(bucket, 1);
            }
            v.count++;
            return v;
        });
        return counter.count <= maxPerMinute;
    }
}
```

Key details:

- **Limit**: 200 requests per minute per IP address (configurable via `new RateLimiter(200)`).
- **Bucket**: Uses 1-minute time windows. The counter resets when a new minute begins.
- **Scope**: Applied by IP address. An attacker from one IP cannot bypass the limit by using different usernames.
- **Response**: When the limit is exceeded, volta returns an error indicating too many requests.
- **Concurrency-safe**: Uses `ConcurrentHashMap` with `compute()` for thread-safe counting.

Since volta delegates authentication to Google (OIDC), there are no traditional password login attempts to brute-force. However, rate limiting still protects against abuse of the login flow itself, API endpoints, and prevents resource exhaustion from automated scanning tools.

See also: [credential-stuffing.md](credential-stuffing.md), [replay-attack.md](replay-attack.md)
