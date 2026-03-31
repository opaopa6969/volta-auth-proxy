# WebAuthn / FIDO2 / Passkeys

[日本語版はこちら](webauthn.ja.md)

---

## What is it?

WebAuthn (Web Authentication) is a standard that lets you log in to websites using a security key (like a YubiKey), your device's fingerprint reader, or face recognition -- instead of (or in addition to) a password. When people say "passkeys," they are talking about WebAuthn credentials that sync across your devices (via iCloud Keychain, Google Password Manager, etc.).

Think of it like a lock that only opens for your specific fingerprint. No password to steal, no code to phish. Your device proves you are you by performing a cryptographic challenge that only it can answer, and it only does so for the specific website that registered the key.

---

## Why does it matter?

WebAuthn solves the two biggest problems with passwords and TOTP:

1. **Phishing resistance.** The credential is cryptographically bound to the website's domain. If an attacker creates a fake login page at `g00gle.com`, your passkey will not work there because it was registered for `google.com`. This makes real-time phishing attacks (which bypass TOTP) impossible.

2. **No shared secrets.** Unlike passwords or TOTP, there is no secret stored on the server that could be stolen. The server only stores a public key. The private key never leaves your device.

```
  Password:     Shared secret → can be stolen from server
  TOTP:         Shared secret → can be stolen from server
  WebAuthn:     Public key only on server → nothing useful to steal
```

---

## How does it work?

### Registration (one-time setup)

```
  User                         Browser                    volta-auth-proxy
  ====                         =======                    ================

  1. Navigate to /settings/security
     Click "Register passkey"
                               ──────────────────────────►
                                                           2. Generate challenge
                                                              (random bytes)
                                                              Store challenge
                               ◄──────────────────────────
                                                           3. Send challenge +
                                                              server info

                               4. Browser calls
                                  navigator.credentials.create()

  5. Device prompts:
     "Touch your security key"
     or "Use Face ID"
     or "Use fingerprint"

  6. User authenticates
     with biometric/touch

                               7. Device generates a NEW key pair:
                                  - Private key (stays on device, NEVER leaves)
                                  - Public key (sent to server)

                               8. Device signs the challenge
                                  with the new private key

                               9. Device returns:
                                  - Public key
                                  - Signed challenge
                                  - Credential ID
                                  - Attestation data

                               ──────────────────────────►
                                                           10. Verify signature
                                                               Store public key
                                                               + credential ID
                                                               in database
                               ◄──────────────────────────
                                                           11. "Passkey registered!"
```

### Authentication (on every login)

```
  User                         Browser                    volta-auth-proxy
  ====                         =======                    ================

  1. Navigate to /login
     Click "Sign in with passkey"
                               ──────────────────────────►
                                                           2. Generate new challenge
                                                              Look up user's
                                                              credential IDs
                               ◄──────────────────────────
                                                           3. Send challenge +
                                                              allowed credential IDs

                               4. Browser calls
                                  navigator.credentials.get()

  5. Device prompts:
     "Touch your security key"
     or "Use Face ID"

  6. User authenticates

                               7. Device finds the matching
                                  private key for this domain

                               8. Device signs the challenge
                                  with the private key

                               9. Returns signed challenge
                                  + credential ID

                               ──────────────────────────►
                                                           10. Look up public key
                                                               by credential ID
                                                           11. Verify signature
                                                               against public key
                                                           12. If valid: login!
```

### Why phishing does not work

```
  Real site: volta.example.com
  Fake site: v0lta.example.com (attacker's phishing page)

  When a passkey is registered:
    credential.domain = "volta.example.com"

  When the user visits the fake site:
    Browser: "Hey device, do you have a credential for v0lta.example.com?"
    Device: "No. I only have one for volta.example.com."
    Result: Authentication fails. User cannot be phished.

  With TOTP, the user would type the code into the fake site,
  and the attacker would relay it to the real site.
  With WebAuthn, this is structurally impossible.
```

### Passkeys vs hardware security keys

| Feature | Hardware key (YubiKey) | Passkey (synced) |
|---------|----------------------|-------------------|
| Where the private key lives | On the physical key | In iCloud/Google cloud |
| Lost device | Need a backup key | Sync to new device automatically |
| Cross-device | Must plug in the key | Available on all synced devices |
| Phishing resistant | Yes | Yes |
| Requires purchase | Yes (~$25-50) | No (built into OS) |

---

## How does volta-auth-proxy plan to use WebAuthn?

### Current status (Phase 1)

WebAuthn is **not implemented** in Phase 1. volta relies on Google OIDC for authentication.

### Phase 2 plan: Passkeys as second factor

```
  Phase 2 WebAuthn flow:

  1. User logs in with Google OIDC (primary authentication)
  2. volta checks: Does this user have a passkey registered?
  3. If yes: Challenge the passkey
  4. User touches security key or uses biometric
  5. volta verifies the signature
  6. Complete login with amr=["google_oidc", "webauthn"]

  Registration:
  1. User goes to /settings/security
  2. Clicks "Add passkey"
  3. Browser prompts for biometric/security key
  4. volta stores the public key + credential ID
  5. User can register multiple passkeys (backup)
```

### Future: Passkeys as primary authentication (passwordless)

In later phases, volta could support passkey-only login:

```
  1. User clicks "Sign in with passkey"
  2. Browser prompts for biometric/security key
  3. volta verifies the signature
  4. Login complete (no Google OIDC needed)

  This removes Google as a dependency for authentication.
```

### Database schema (planned)

```sql
CREATE TABLE webauthn_credentials (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES users(id),
    credential_id   BYTEA NOT NULL,  -- from WebAuthn registration
    public_key      BYTEA NOT NULL,  -- COSE-encoded public key
    sign_count      BIGINT DEFAULT 0, -- replay protection
    name            TEXT,             -- user-friendly name ("My YubiKey")
    created_at      TIMESTAMPTZ DEFAULT now(),
    last_used_at    TIMESTAMPTZ
);
```

---

## Common mistakes and attacks

### Mistake 1: Not checking the sign counter

WebAuthn includes a sign counter that increments on each use. If the server sees a counter that is lower than the stored value, it means the credential was cloned. Always check and enforce the counter.

### Mistake 2: Not supporting multiple credentials per user

Users should be able to register multiple passkeys (e.g., a YubiKey and a phone). If they lose one, they can still log in with the other.

### Mistake 3: Not providing a fallback

If a user's only passkey is lost and there is no fallback (TOTP, recovery codes), they are locked out forever. Always provide alternative recovery methods.

### Attack: Device theft

If someone steals your phone (unlocked) or knows your device PIN, they can use your passkeys. This is mitigated by device lock screens and biometric requirements.

### Attack: Credential cloning (hardware)

In theory, a sophisticated attacker could clone a security key's private key. The sign counter detects this: if both the real and cloned key are used, the counter will not match. In practice, this attack requires physical access and specialized equipment.

---

## Further reading

- [WebAuthn Guide](https://webauthn.guide/) -- Interactive visual guide.
- [FIDO2 Specification](https://fidoalliance.org/fido2/) -- The FIDO Alliance standard.
- [Passkeys.dev](https://passkeys.dev/) -- Developer resources for passkeys.
- [mfa.md](mfa.md) -- Multi-factor authentication overview.
- [totp.md](totp.md) -- TOTP as an alternative second factor.
