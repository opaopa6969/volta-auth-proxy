# QR Code

[日本語版はこちら](qr-code.ja.md)

---

## In one sentence?

A QR code is a square barcode that your phone camera can scan to instantly open a URL, share data, or trigger an action -- no typing required.

---

## A picture worth a thousand keystrokes

A QR code is like a shortcut written in a language only cameras understand:

| Old way | QR code way |
|---|---|
| "Go to https://auth.example.com/invite/a1b2c3d4-e5f6-..." | Scan this square |
| Dictate a 50-character URL over the phone | Point your camera |
| Print a URL on a flyer that nobody will type | Print a QR code that everyone scans |

How they work:

- **Black and white squares** encode data (usually a URL)
- **Three big squares in the corners** help the camera find and orient the code
- **Error correction** means even a partially damaged code can be read
- A standard QR code can hold up to ~4,000 characters

---

## Why do we need this?

Without QR codes:

- Sharing URLs verbally or on paper is error-prone ("Was that a dash or an underscore?")
- Mobile users would need to manually type long URLs
- Invitation links with UUIDs are practically impossible to type correctly
- Physical-to-digital transitions (posters, business cards, receipts) would require typing

QR codes bridge the gap between the physical world and the digital world with zero typing effort.

---

## QR code in volta-auth-proxy

volta uses QR codes for **tenant invitations**:

When an OWNER or ADMIN invites someone to join a tenant, volta generates an invitation URL like:

```
https://auth.example.com/invite/a1b2c3d4-e5f6-7890-abcd-1234567890ef
```

This URL is long and contains a UUID that's impossible to remember. volta can present this as a QR code:

```
  ┌──────────────────────┐
  │ ██ ▄▄▄▄▄ █ █ ██ ▄▄▄ │
  │ ██ █   █ ███ ██ █ █ │
  │ ██ █▄▄▄█ █ ███ █▄▄█ │
  │ ████████ █ █ ██████ │
  │ ██ ▄▄ ▄█ ███ █ ▄▄  │
  │ ██████ █ █ ██ █████ │
  │ ██ ▄▄▄▄▄ █ ███ ▄ █ │
  │ ██ █   █ █ █ █ ███  │
  │ ██ █▄▄▄█ ███ ██ █ █ │
  └──────────────────────┘
  Scan to join ACME Corp
```

Use cases in volta:

| Scenario | How QR helps |
|---|---|
| In-person onboarding | Show QR on screen, new member scans with phone |
| Printed materials | Include QR on welcome packet |
| Remote invitation | Send QR via messaging app (easier than a long URL) |
| Conference/event | Display QR for attendees to join a tenant |

---

## Concrete example

Using a QR code for a volta tenant invitation:

1. ADMIN of "ACME Corp" tenant opens the invitation management page
2. ADMIN clicks "Create Invitation"
3. volta generates invitation URL: `https://auth.example.com/invite/a1b2c3d4-...`
4. volta also generates a QR code encoding that URL
5. ADMIN shows the QR code on their laptop screen during a meeting
6. New team member opens their phone camera and points it at the screen
7. Phone recognizes the QR code and shows a banner: "Open in [browser](browser.md)?"
8. Team member taps the banner
9. [Browser](browser.md) opens the invitation URL
10. If not logged in, volta [redirects](redirect.md) to [login](login.md) via Google [OIDC](oidc.md)
11. After [login](login.md), volta associates the user with ACME Corp as a MEMBER
12. New member is now part of the tenant with MEMBER [role](authentication-vs-authorization.md)

**Security note:** The QR code is only as secure as the invitation URL it encodes. volta invitation links:

- Have a unique UUID (unguessable)
- Can be single-use or limited-use
- Can expire after a set time
- Can be revoked by the ADMIN

---

## Learn more

- [Login](login.md) -- What happens after scanning the QR code
- [Redirect](redirect.md) -- How the browser moves through the invitation flow
- [Browser](browser.md) -- The app that opens after scanning
- [Credentials](credentials.md) -- QR codes are NOT credentials; they're a delivery mechanism
