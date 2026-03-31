# MFA / 2FA (Multi-Factor Authentication)

[日本語版はこちら](mfa.ja.md)

---

## What is it?

Multi-Factor Authentication (MFA) means requiring more than one type of evidence to prove your identity. Instead of just a password, you also need something else -- like a code from your phone or a fingerprint scan. Two-Factor Authentication (2FA) is MFA with exactly two factors.

Think of it like a bank vault that requires two keys held by two different people. Even if a thief steals one key, they cannot open the vault without the other. MFA works the same way: even if your password is stolen, the attacker still needs your phone or fingerprint.

---

## Why does it matter?

Passwords alone are not enough. They get:

- **Stolen** in data breaches (83% of breaches involve stolen credentials, per Verizon DBIR)
- **Phished** via fake login pages
- **Guessed** via brute force or common password lists
- **Reused** across multiple sites (if one is breached, all are)

MFA dramatically reduces the impact of password compromise. Even if an attacker has your password, they still need your second factor -- which is typically something physical that the attacker does not have.

---

## How does it work?

### The three factors

Authentication factors are categorized by what they are:

```
  ┌─────────────────────────────────────────────────────────────┐
  │                    Authentication Factors                     │
  │                                                              │
  │  1. Something you KNOW         2. Something you HAVE         │
  │  ┌──────────────────────┐     ┌──────────────────────┐      │
  │  │ - Password            │     │ - Phone (TOTP app)   │      │
  │  │ - PIN                 │     │ - Security key       │      │
  │  │ - Security question   │     │ - Smart card         │      │
  │  │ - Pattern             │     │ - Hardware token     │      │
  │  └──────────────────────┘     └──────────────────────┘      │
  │                                                              │
  │  3. Something you ARE                                        │
  │  ┌──────────────────────┐                                   │
  │  │ - Fingerprint         │                                   │
  │  │ - Face recognition    │                                   │
  │  │ - Iris scan           │                                   │
  │  │ - Voice               │                                   │
  │  └──────────────────────┘                                   │
  │                                                              │
  │  True MFA requires factors from DIFFERENT categories.        │
  │  Password + security question = still one factor (both KNOW) │
  │  Password + TOTP code = two factors (KNOW + HAVE)            │
  └─────────────────────────────────────────────────────────────┘
```

### Common MFA methods

| Method | Factor type | How it works | Security level |
|--------|------------|-------------|----------------|
| **TOTP** (Google Authenticator) | HAVE | Time-based one-time password from an app | Good. See [totp.md](totp.md) |
| **SMS code** | HAVE | Code sent via text message | Weak. SIM swapping attacks are common |
| **Email code** | HAVE | Code sent via email | Weak. Email accounts are often compromised |
| **WebAuthn/Passkey** | HAVE + ARE | Security key or biometric | Excellent. See [webauthn.md](webauthn.md) |
| **Push notification** | HAVE | Approve on your phone | Good, but vulnerable to "fatigue attacks" |

### MFA flow example

```
  User                          volta-auth-proxy              Google
  ====                          ================              ======

  1. Click "Login with Google"
  ─────────────────────────────►
                                  redirect to Google OIDC
                                ──────────────────────────────►
  2. Authenticate with Google                                   verify
  ─────────────────────────────────────────────────────────────►
                                ◄──────────────────────────────
                                  id_token received

                                3. Check: Is MFA required for this tenant?
                                   (admin setting: "MFA required for all members")

                                   ├── No: Complete login normally
                                   └── Yes: Continue to step 4

  ◄─────────────────────────────
  4. Show MFA challenge page
     "Enter your 6-digit code"

  5. Enter TOTP code: 847293
  ─────────────────────────────►
                                6. Verify TOTP code
                                   ├── Invalid: Show error, try again
                                   └── Valid: Complete login

                                7. Set session cookie
                                8. Record auth method in session:
                                   amr = ["google_oidc", "totp"]

  ◄─────────────────────────────
  9. Redirect to app
```

---

## How does volta-auth-proxy use MFA?

### Current status (Phase 1)

In Phase 1, volta does **not** implement MFA. Authentication relies on Google OIDC, which has its own MFA (Google's 2-Step Verification). This means:

- If the user has Google 2FA enabled, they get MFA through Google
- volta does not add a second layer on top of Google's authentication
- volta does not enforce MFA requirements per tenant

### Phase 2-3 plan

volta plans to add MFA in phases:

**Phase 2: Passkeys (WebAuthn/FIDO2)**
- Add passkeys as an optional second authentication method
- Users can register a security key or use biometric authentication
- See [webauthn.md](webauthn.md) for details

**Phase 3: TOTP and MFA enforcement**
- Add TOTP (Google Authenticator, Authy, etc.)
- Tenant admins can enforce "MFA required for all members"
- Risk-based authentication (extra verification on new device/IP)
- The `amr` (Authentication Methods References) JWT claim will reflect auth strength

```json
// Future volta JWT with MFA claims
{
  "iss": "volta-auth",
  "sub": "user-uuid",
  "volta_v": 1,
  "volta_tid": "tenant-uuid",
  "volta_roles": ["ADMIN"],
  "amr": ["google_oidc", "totp"],
  "auth_time": 1711899700
}
```

### Architecture for MFA

```
  ┌──────────────────────────────────────────────┐
  │               volta-auth-proxy                │
  │                                               │
  │  Google OIDC ──► Primary authentication       │
  │       │                                       │
  │       ▼                                       │
  │  MFA Required? ──► Check tenant policy        │
  │       │                                       │
  │       ├── TOTP challenge (Phase 3)            │
  │       │   └── Verify against shared secret    │
  │       │                                       │
  │       ├── WebAuthn challenge (Phase 2)        │
  │       │   └── Verify against registered key   │
  │       │                                       │
  │       └── No MFA ──► Complete login           │
  │                                               │
  │  JWT: amr claim records which methods used    │
  │  Apps can check amr for sensitive operations  │
  └──────────────────────────────────────────────┘
```

---

## Common mistakes and attacks

### Mistake 1: SMS as the only second factor

SMS codes can be intercepted via SIM swapping (an attacker convinces the phone carrier to transfer your number to their SIM). Use TOTP or WebAuthn instead.

### Mistake 2: Making MFA optional forever

If MFA is always optional, users will not enable it. Tenant admins should be able to enforce MFA for all members.

### Mistake 3: Not providing recovery codes

If a user loses their phone, they lose access to their TOTP codes. Always provide one-time recovery codes at MFA setup.

### Attack: MFA fatigue (push notification spam)

Attackers with stolen passwords repeatedly trigger push notification MFA. The exhausted user eventually approves one by accident. Mitigations: rate limit MFA attempts, use number matching (user must type a code shown on screen).

### Attack: Real-time phishing

Sophisticated attackers proxy the real login page, capturing both password and MFA code in real time, then replaying them to the real site. WebAuthn (passkeys) are resistant to this because they are bound to the domain. See [webauthn.md](webauthn.md).

---

## Further reading

- [totp.md](totp.md) -- How TOTP one-time passwords work.
- [webauthn.md](webauthn.md) -- How passkeys and security keys work.
- [NIST SP 800-63B](https://pages.nist.gov/800-63-3/sp800-63b.html) -- Authentication assurance levels.
- [OWASP MFA Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html) -- Implementation best practices.
