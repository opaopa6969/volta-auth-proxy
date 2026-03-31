# TOTP (Time-based One-Time Password)

[日本語版はこちら](totp.ja.md)

---

## What is it?

TOTP is a method for generating one-time passwords that change every 30 seconds. When you open Google Authenticator or Authy and see a 6-digit code counting down, that is TOTP. Both your phone and the server share a secret, and they use the current time to independently generate the same code.

Think of it like two synchronized watches with a codebook. You and the server both have identical codebooks. Every 30 seconds, you both look at the current time and the codebook to produce the same 6-digit code. Nobody listening to your conversation can guess the next code because they do not have the codebook.

---

## Why does it matter?

TOTP provides a "something you have" factor (your phone) that is:

- **Offline:** Works without internet or cell signal (unlike SMS)
- **Standard:** Works with any TOTP app (Google Authenticator, Authy, 1Password, etc.)
- **Free:** No per-SMS charges, no vendor lock-in
- **Resistant to SIM swapping:** Unlike SMS codes, TOTP is not tied to a phone number

TOTP is the most widely deployed second factor in the world.

---

## How does it work?

### Setup (one-time)

```
  Step 1: Server generates a random secret (usually 20 bytes)

    secret = "JBSWY3DPEHPK3PXP" (base32-encoded)

  Step 2: Server shows a QR code containing:

    otpauth://totp/volta:taro@acme.com
      ?secret=JBSWY3DPEHPK3PXP
      &issuer=volta
      &algorithm=SHA1
      &digits=6
      &period=30

  Step 3: User scans QR code with their authenticator app

    The app stores the secret. The server stores the secret.
    Both now have the same secret.

  Step 4: User enters a verification code to confirm setup

    This proves the app scanned the QR code correctly.
```

### Code generation (every 30 seconds)

```
  Inputs:
    secret = "JBSWY3DPEHPK3PXP" (shared secret)
    time   = current Unix timestamp / 30 (time step counter)

  Algorithm:
    1. counter = floor(unix_timestamp / 30)
       Example: floor(1711899700 / 30) = 57063323

    2. hmac = HMAC-SHA1(secret, counter_as_8_bytes)
       Result: 20 bytes of HMAC output

    3. offset = last_nibble_of_hmac (0-15)
       Extract 4 bytes starting at that offset

    4. code = extract_31_bits(hmac[offset:offset+4]) mod 1000000
       Result: 6-digit number

  Example:
    Time: 2026-03-31T09:00:00Z → counter = 57063323
    Secret: JBSWY3DPEHPK3PXP
    Code: 847293

    30 seconds later:
    Time: 2026-03-31T09:00:30Z → counter = 57063324
    Code: 159482 (completely different)
```

### Verification

```
  User enters: 847293
  Server computes:
    - Code for current time step:   847293  ← match!
    - Code for previous time step:  391057  (allow +-1 for clock drift)
    - Code for next time step:      159482  (allow +-1 for clock drift)

  If the entered code matches any of the 3 (current, previous, next):
    → Valid! Authentication succeeds.

  If no match:
    → Invalid. Try again (with rate limiting).
```

### Why TOTP is secure

```
  What the attacker needs:        Where it lives:
  ┌──────────────────────────┐    ┌──────────────────────────┐
  │ The shared secret        │    │ Server: encrypted in DB   │
  │                          │    │ Phone: in authenticator   │
  │                          │    │        app's secure store │
  └──────────────────────────┘    └──────────────────────────┘

  What the attacker can see:
  ┌──────────────────────────┐
  │ A 6-digit code            │  ← Only valid for 30-60 seconds
  │ (if they shoulder-surf)   │  ← Cannot derive the secret from it
  │                           │  ← Cannot predict the next code
  └──────────────────────────┘
```

Even if an attacker sees one code, they cannot use it again (it expires) or derive future codes (HMAC is one-way).

---

## How does volta-auth-proxy plan to use TOTP?

### Current status (Phase 1)

TOTP is **not implemented** in Phase 1. volta already includes the `googleauth` library in its dependencies (pom.xml), but the TOTP enrollment and verification flows are planned for Phase 3.

### Phase 3 plan

```
  TOTP enrollment flow:
  ════════════════════

  1. User navigates to /settings/security
  2. Clicks "Enable TOTP"
  3. volta generates a random secret
  4. Shows QR code + manual entry key
  5. User scans with authenticator app
  6. User enters verification code
  7. volta verifies code against secret
  8. If valid: store encrypted secret in DB
     Associate with user account
  9. Generate and display recovery codes
     (one-time use, for when phone is lost)

  TOTP verification flow (on login):
  ═══════════════════════════════════

  1. User completes Google OIDC login
  2. volta checks: does this user have TOTP enabled?
  3. If yes: show TOTP challenge page
  4. User enters 6-digit code from app
  5. volta verifies against stored secret
  6. If valid: complete login, set amr=["google_oidc","totp"]
  7. If invalid: show error (with rate limiting)

  Tenant enforcement:
  ═══════════════════

  Tenant admins can set a policy:
  - "MFA optional" (default)
  - "MFA required for ADMIN and OWNER"
  - "MFA required for all members"

  Users without MFA who belong to a "required" tenant
  will be prompted to set up TOTP on their next login.
```

### Recovery codes

When a user enables TOTP, volta will generate 8-10 single-use recovery codes:

```
  Your recovery codes (save these somewhere safe):

  a4k2-m8p3-x7w1
  b9n5-j2q8-r6t4
  c1v7-h3s9-y5u2
  d6e8-f4g1-z3k7
  ...

  Each code can only be used once.
  Use these if you lose access to your authenticator app.
```

---

## Common mistakes and attacks

### Mistake 1: Not providing recovery codes

If a user loses their phone and has no recovery codes, they are permanently locked out. Always provide single-use recovery codes at TOTP setup time.

### Mistake 2: Not encrypting the TOTP secret at rest

The TOTP shared secret in the database must be encrypted. If the database is breached, unencrypted secrets let the attacker generate valid codes for all users.

### Mistake 3: Not rate-limiting TOTP attempts

A 6-digit code has 1,000,000 possibilities. Without rate limiting, an attacker can brute-force it in minutes. Limit to 5 attempts per minute.

### Mistake 4: Allowing unlimited clock drift

TOTP allows a window for clock drift (typically +/- 1 time step = +/- 30 seconds). If you accept too many time steps (e.g., +/- 5 = 5 minutes), previously-used codes could still be valid.

### Attack: Real-time phishing

An attacker proxies the real login page, captures the TOTP code, and immediately replays it. TOTP is vulnerable to this because codes are not bound to a specific domain. WebAuthn (passkeys) solves this problem. See [webauthn.md](webauthn.md).

---

## Further reading

- [RFC 6238 - TOTP](https://tools.ietf.org/html/rfc6238) -- The official TOTP specification.
- [RFC 4226 - HOTP](https://tools.ietf.org/html/rfc4226) -- The counter-based predecessor to TOTP.
- [mfa.md](mfa.md) -- Multi-factor authentication overview.
- [webauthn.md](webauthn.md) -- A phishing-resistant alternative to TOTP.
