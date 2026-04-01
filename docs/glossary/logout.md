# Logout

[日本語版はこちら](logout.ja.md)

---

## In one sentence?

Logout is the act of ending your authenticated [session](session.md) so the system no longer recognizes you as a signed-in user.

---

## Checking out of the hotel

If [login](login.md) is checking into a hotel, logout is checking out:

| Hotel | Web |
|---|---|
| Return your room key | Browser deletes the [session](session.md) [cookie](cookie.md) |
| Front desk deactivates the key | Server deletes the session from the database |
| Key no longer opens the door | Cookie no longer grants access |
| Your room is cleaned for the next guest | Server resources are freed |

The important part: **both sides must act**. If you only throw away your key but the hotel doesn't deactivate it, someone who finds it can still open your room. That's why proper logout must invalidate the session server-side.

---

## Why do we need this?

Without logout:

- Sessions live forever (or until they expire) -- dangerous on shared computers
- If someone steals your [cookie](cookie.md), you can't revoke access
- Compliance regulations (GDPR, SOC 2) require the ability to terminate sessions
- Users who leave an organization would remain logged in until session expiry
- No way to "sign out everywhere" if a device is stolen

---

## Logout in volta-auth-proxy

volta implements server-side session destruction:

```
  Browser                    volta-auth-proxy              Database
  ──────                     ──────────────────            ──────────
  1. Click "Log out"
  2. POST /auth/logout ─────>
                             3. Read session ID from cookie
                             4. DELETE session from sessions table ──>
                             5. Clear __volta_session cookie
                               (Set-Cookie with Max-Age=0)
  <────── 302 Redirect to /
  6. User sees login page
```

What volta does on logout:

1. **Deletes the session from PostgreSQL** -- The session ID is immediately invalid. Even if someone copied the cookie value, it's useless.
2. **Clears the cookie** -- Sets `__volta_session` with `Max-Age=0`, which tells the [browser](browser.md) to delete it.
3. **Redirects to the landing page** -- The user sees the unauthenticated state.

What volta does NOT do:

- **Does not revoke the Google session** -- Logging out of volta doesn't log you out of Google. This is intentional: you may be using Google for other services.
- **Does not revoke issued [JWTs](jwt.md)** -- JWTs are valid until they expire (5 minutes). This is why JWTs have short expiry -- it limits the window after logout.

---

## Concrete example

User logs out from a volta-protected app:

1. User clicks "Log out" in the app UI
2. App calls `POST https://auth.acme.example.com/auth/logout`
3. volta reads the `__volta_session` cookie: `550e8400-e29b-41d4-...`
4. volta runs `DELETE FROM sessions WHERE id = '550e8400-e29b-41d4-...'`
5. volta sets response [header](header.md): `Set-Cookie: __volta_session=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`
6. volta responds with HTTP 302 [redirect](redirect.md) to `/`
7. [Browser](browser.md) deletes the cookie and follows the redirect
8. User sees the login page
9. If any app still has a cached [JWT](jwt.md), it will work for at most 5 more minutes, then fail with 401
10. volta-sdk-js detects 401, tries to refresh, gets rejected (no session), and redirects to login

---

## Learn more

- [Login](login.md) -- The reverse of logout: starting your session
- [Session](session.md) -- What gets destroyed when you log out
- [Cookie](cookie.md) -- The browser-side token that gets cleared
- [JWT](jwt.md) -- Short-lived tokens that survive briefly after logout
