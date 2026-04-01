# Whitelist

[日本語版はこちら](whitelist.ja.md)

---

## What is it?

A whitelist (also called an allowlist) is a list of explicitly permitted items. Anything on the list is allowed; everything else is denied by default. In security, whitelisting is a "default deny" approach -- instead of trying to block every possible bad thing (blacklist), you only allow known good things.

Think of it like a VIP guest list at a wedding. The bouncer does not try to memorize every person in the city who should NOT be allowed in. Instead, they have a short list of people who ARE allowed in. If your name is not on the list, you are not getting in -- no matter how nicely you dress up.

In volta-auth-proxy, whitelists are used to restrict cryptographic algorithms, redirect URLs, webhook domains, and other security-sensitive configurations to only known-safe values.

---

## Why does it matter?

Blacklisting (blocking known bad things) is a losing game:

```
  Blacklist approach (fragile):
  ┌─────────────────────────────────────────┐
  │  Block: HS256 (weak algorithm)          │
  │  Block: none  (no-algorithm attack)     │
  │  Block: HS384 (HMAC confusion attack)   │
  │  Block: ???   (next vulnerability?)     │
  │                                         │
  │  Problem: you must know every bad thing │
  │  in advance. Miss one → compromised.    │
  └─────────────────────────────────────────┘

  Whitelist approach (robust):
  ┌─────────────────────────────────────────┐
  │  Allow: RS256 (and ONLY RS256)          │
  │                                         │
  │  Everything else is automatically denied│
  │  including attacks not yet invented.    │
  └─────────────────────────────────────────┘
```

A whitelist is inherently safer because it denies the unknown. New attack vectors are blocked by default without any updates.

---

## How does it work?

### The principle

```
  ┌───────────────────────────────────────────────┐
  │                                               │
  │    Input ──► Is it on the whitelist? ──► YES  │
  │                      │                   │    │
  │                      │                 ALLOW  │
  │                      │                        │
  │                      ▼                        │
  │                     NO ──────────────► DENY   │
  │                                               │
  └───────────────────────────────────────────────┘
```

### Whitelist vs blacklist comparison

| Aspect | Whitelist (allowlist) | Blacklist (blocklist) |
|--------|----------------------|----------------------|
| Default | DENY everything | ALLOW everything |
| List contains | Known good items | Known bad items |
| Unknown items | Blocked | Allowed (dangerous!) |
| Maintenance | Add new good items | Keep up with new threats |
| Security posture | Strong | Weak (reactive) |
| Usability | May need updating for new features | Easy to add new features |

### Common whitelist patterns in security

```
  Algorithm whitelist:
    allowed = ["RS256"]
    if (jwt.algorithm NOT IN allowed) → REJECT

  Redirect URL whitelist:
    allowed_domains = ["app.example.com", "staging.example.com"]
    if (redirect_url.host NOT IN allowed_domains) → REJECT

  IP whitelist:
    allowed_ips = ["10.0.0.0/8", "203.0.113.0/24"]
    if (client_ip NOT IN allowed_ips) → REJECT

  HTTP method whitelist:
    allowed_methods = ["GET", "POST"]
    if (request.method NOT IN allowed_methods) → REJECT
```

---

## How does volta-auth-proxy use it?

### Algorithm whitelist (JWT)

volta whitelists only RS256 for [JWT](jwt.md) signature verification:

```java
// JwtService.java
private static final Set<String> ALLOWED_ALGORITHMS =
    Set.of("RS256");

public AuthPrincipal verify(String token) {
    DecodedJWT decoded = JWT.decode(token);

    if (!ALLOWED_ALGORITHMS.contains(decoded.getAlgorithm())) {
        throw new SecurityException(
            "Algorithm not allowed: " + decoded.getAlgorithm()
        );
    }
    // Proceed with RS256 verification...
}
```

This prevents the "algorithm confusion" attack where an attacker changes the JWT `alg` header to `HS256` or `none`:

```
  Attack: Algorithm confusion
  ┌─────────────────────────────────────────────────┐
  │  Attacker crafts JWT with alg: "none"            │
  │  Signature: (empty)                              │
  │                                                   │
  │  Without whitelist:                               │
  │    volta sees alg=none → skips verification       │
  │    → Attacker has valid token!                    │
  │                                                   │
  │  With whitelist:                                  │
  │    volta sees alg=none → NOT in [RS256]           │
  │    → REJECT immediately                           │
  └─────────────────────────────────────────────────┘
```

### Redirect URL whitelist

volta restricts [OAuth2](oauth2.md) redirect URLs to pre-registered domains:

```
  Tenant configuration:
    allowed_redirect_urls:
      - "https://app.acme.com/callback"
      - "https://staging.acme.com/callback"

  Login request:
    redirect_uri=https://evil.com/steal-token

  volta check:
    "https://evil.com/steal-token" NOT IN allowed list
    → REJECT (prevents open redirect attack)
```

### Webhook URL whitelist

When tenants register [webhook](webhook.md) endpoints, volta validates the URL:

```
  Allowed:
    ✓ https://api.acme.com/webhooks
    ✓ https://hooks.slack.com/services/...

  Blocked:
    ✗ http://169.254.169.254/metadata  (AWS metadata - SSRF)
    ✗ http://localhost:8080/admin       (internal service - SSRF)
    ✗ http://10.0.0.1/secret           (private network - SSRF)
```

volta blocks:
- Private IP ranges (10.x, 172.16-31.x, 192.168.x)
- Loopback addresses (127.x, localhost)
- Link-local addresses (169.254.x)
- Cloud metadata endpoints

### CORS origin whitelist

volta restricts Cross-Origin Resource Sharing to allowed origins:

```yaml
# volta-config.yaml
cors:
  allowed_origins:
    - "https://app.acme.com"
    - "https://staging.acme.com"
```

### [Header](header.md) whitelist for upstream forwarding

volta only forwards specific headers to [upstream](upstream.md) applications, preventing header injection:

```
  Forwarded headers (whitelist):
    X-Volta-User-Id
    X-Volta-Email
    X-Volta-Roles
    X-Volta-Tenant-Id
    X-Volta-M2M

  All other X-Volta-* headers from the client → STRIPPED
  (prevents clients from forging identity headers)
```

---

## Common mistakes and attacks

### Mistake 1: Using a blacklist instead of a whitelist

Blacklists require you to anticipate every attack. Whitelists deny by default. Always prefer whitelists for security-critical decisions.

### Mistake 2: Overly broad whitelist entries

Whitelisting `*.example.com` instead of `app.example.com` allows any subdomain, including attacker-controlled ones (e.g., `evil.example.com` if they can create subdomains).

### Mistake 3: Not updating the whitelist

When you add a new legitimate service or domain, remember to update the whitelist. Otherwise, legitimate requests are blocked.

### Mistake 4: Client-side whitelist only

If the whitelist is only enforced in the browser (JavaScript), an attacker can bypass it by calling the API directly. Always enforce whitelists server-side.

### Attack: Open redirect via whitelist bypass

An attacker finds a URL on the whitelisted domain that redirects (e.g., `https://app.acme.com/redirect?url=https://evil.com`). Defense: validate the full URL path, not just the domain. Disallow redirect chains.

### Attack: DNS rebinding

An attacker's domain resolves to a public IP during whitelist check, then re-resolves to a private IP when the actual request is made. Defense: resolve the domain and check the IP at request time, not just at registration time.

---

## Further reading

- [jwt.md](jwt.md) -- Algorithm whitelist for JWT verification.
- [oauth2.md](oauth2.md) -- Redirect URL whitelist in OAuth flows.
- [webhook.md](webhook.md) -- Webhook URL validation.
- [header.md](header.md) -- Header whitelist for upstream forwarding.
- [upstream.md](upstream.md) -- The upstream app that receives whitelisted headers.
