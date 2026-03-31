# Open Redirect

[Japanese / 日本語](open-redirect.ja.md)

---

## What is it?

An open redirect is a vulnerability where a website can be tricked into redirecting users to an external, attacker-controlled URL. It happens when the application takes a redirect destination from a URL parameter (like `?returnTo=...`) without validating that the destination is safe. The attacker crafts a link that looks legitimate (it starts with your trusted domain) but ends up sending the user to a malicious site.

---

## Why does it matter?

Open redirects are dangerous because they abuse trust. A phishing email containing `https://your-trusted-app.com/login?returnTo=https://evil.com/steal` looks legitimate -- the URL starts with a domain the user trusts. After logging in, the user is silently redirected to the attacker's site, which might show a fake "session expired" page to capture credentials again, or steal tokens from the URL.

In the context of OIDC, open redirects are even more dangerous. If the `returnTo` parameter after login is not validated, an attacker can redirect the user (with their fresh session cookie) to a site that harvests those cookies or tokens.

---

## Simple example

Vulnerable code:
```java
String returnTo = request.getParameter("returnTo");
response.redirect(returnTo);  // redirects to ANYTHING, including https://evil.com
```

Attack:
```
https://trusted-app.com/login?returnTo=https://evil.com/phishing
```

The user sees "trusted-app.com" in the URL, trusts it, logs in, and gets redirected to `evil.com`.

Secure code:
```java
String returnTo = request.getParameter("returnTo");
if (isAllowedDomain(returnTo)) {
    response.redirect(returnTo);
} else {
    response.redirect("/dashboard");  // safe fallback
}
```

---

## In volta-auth-proxy

volta prevents open redirects using a domain whitelist configured via the `ALLOWED_REDIRECT_DOMAINS` environment variable:

```
ALLOWED_REDIRECT_DOMAINS=localhost,127.0.0.1
```

The validation is in `HttpSupport.isAllowedReturnTo()`:

```java
public static boolean isAllowedReturnTo(String returnTo, String allowedDomainsCsv) {
    if (returnTo == null || returnTo.isBlank()) return false;
    URI uri;
    try { uri = URI.create(returnTo); } catch (Exception e) { return false; }
    if (uri.getHost() == null ||
        !"https".equalsIgnoreCase(uri.getScheme()) &&
        !"http".equalsIgnoreCase(uri.getScheme())) {
        return false;
    }
    Set<String> allowedDomains = Arrays.stream(allowedDomainsCsv.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toSet());
    return allowedDomains.contains(uri.getHost());
}
```

The checks are:

1. **Null/blank check**: Empty `returnTo` values are rejected.
2. **Parseable URI**: Malformed URIs are rejected (prevents parser confusion attacks).
3. **Scheme check**: Only `http` and `https` are allowed (blocks `javascript:`, `data:`, `ftp:` URIs).
4. **Host whitelist**: The hostname must exactly match one of the configured allowed domains.

If validation fails, volta redirects to a safe default instead. In production, the whitelist would contain only your application's domains (e.g., `wiki.example.com,admin.example.com`).

See also: [redirect-uri.md](redirect-uri.md), [token-theft.md](token-theft.md)
