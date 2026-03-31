# Credential Stuffing

[Japanese / 日本語](credential-stuffing.ja.md)

---

## What is it?

Credential stuffing is an attack where an attacker takes username/password pairs leaked from one website and tries them on other websites. It exploits the fact that many people reuse the same password across multiple services. Unlike brute force (which guesses randomly), credential stuffing uses real credentials that are known to work somewhere -- just not necessarily on the target site.

---

## Why does it matter?

Massive data breaches happen regularly. Billions of email/password pairs are available on the dark web. If you used the same password on a breached gaming site and your company's admin panel, an attacker can log into your admin panel using the leaked credentials. No hacking skill needed -- just automated tools that try each leaked pair against the target login page.

Credential stuffing is one of the most common causes of account takeover. The primary user-side defense is unique passwords (ideally via a password manager). The primary server-side defenses are rate limiting, anomaly detection, and multi-factor authentication.

---

## Simple example

```
1. Site A gets breached. Attacker obtains:
   alice@example.com : P@ssw0rd123
   bob@example.com   : qwerty2024

2. Attacker runs automated tool against Site B:
   POST /login  body: {email: "alice@example.com", password: "P@ssw0rd123"}
   -> 200 OK (Alice reused her password!)

   POST /login  body: {email: "bob@example.com", password: "qwerty2024"}
   -> 401 Unauthorized (Bob used a different password on Site B)
```

The attacker did not "hack" Site B. They just reused Alice's leaked password from Site A.

---

## In volta-auth-proxy

volta is inherently resistant to credential stuffing because it does not have a password-based login. Users authenticate exclusively through Google OIDC:

- **No password storage**: volta never stores or accepts passwords. There is no `/login` endpoint that takes an email/password pair. Leaked passwords from other sites are irrelevant.
- **Google handles authentication**: The actual password check happens on Google's servers, which have their own sophisticated anti-stuffing defenses (CAPTCHAs, risk analysis, suspicious login alerts).
- **MFA via Google**: If users enable 2FA on their Google account, credential stuffing is blocked even if the password is correct.

However, volta still applies defenses against related attacks:

- **Rate limiting**: 200 requests per minute per IP prevents automated tools from rapidly cycling through leaked credentials against volta's OIDC flow.
- **Audit logging**: All authentication events (login, logout, failure) are logged with IP and user agent, making it possible to detect patterns like one IP trying many different accounts.
- **Session limits**: A maximum of 5 concurrent sessions per user means an attacker who somehow gains access cannot silently maintain many sessions.

The lesson: delegating authentication to a major identity provider (Google) offloads the hardest parts of credential defense to a team with far more resources to handle it.

See also: [brute-force.md](brute-force.md), [token-theft.md](token-theft.md)
