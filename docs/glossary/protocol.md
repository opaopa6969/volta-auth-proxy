# Protocol

[日本語版はこちら](protocol.ja.md)

---

## In one sentence?

A protocol is a set of rules that computers agree on so they can understand each other when they communicate -- like HTTP for web pages or HTTPS for secure web pages.

---

## Speaking the same language

Two people can only have a conversation if they agree on a language and some basic rules (take turns, don't interrupt, etc.). Computers are the same:

| Human conversation | Computer protocol |
|---|---|
| Language (English, Japanese) | Protocol name ([HTTP](http.md), HTTPS, TCP) |
| Grammar rules | Message format and structure |
| "Hello" → "Hi, how are you?" | Request → Response |
| Speaking louder doesn't help if you speak different languages | Sending more data doesn't help if protocols don't match |

Common protocols you encounter daily:

| Protocol | What it does | Everyday example |
|---|---|---|
| [HTTP](http.md) | Web page transfer (unencrypted) | Reading a blog |
| HTTPS | Web page transfer (encrypted via [SSL/TLS](ssl-tls.md)) | Online banking |
| TCP | Reliable data delivery | Foundation under HTTP |
| DNS | Translates [domain](domain.md) names to IPs | Typing `google.com` |
| SMTP | Sending email | Hitting "Send" in Gmail |
| [OAuth2](oauth2.md) | Authorization delegation | "Sign in with Google" |
| [OIDC](oidc.md) | Identity verification on top of OAuth2 | Logging into volta |

---

## Why do we need this?

Without agreed-upon protocols:

- Every website would invent its own way to communicate, and your [browser](browser.md) would understand none of them
- Encryption wouldn't be standardized -- each site would roll its own (and get it wrong)
- APIs would be incompatible -- no standard way to send requests or parse responses
- Security researchers couldn't find common vulnerabilities because nothing would be common

Protocols are the invisible contracts that make the internet work.

---

## Protocol in volta-auth-proxy

volta speaks and enforces multiple protocols:

| Protocol | Where volta uses it |
|---|---|
| **HTTPS** | All communication between [browsers](browser.md) and the [reverse proxy](reverse-proxy.md) must be encrypted |
| **[HTTP](http.md)** | Internal communication between reverse proxy and volta (within [Docker network](network-isolation.md), encryption unnecessary) |
| **[OAuth2](oauth2.md)** | The authorization framework volta uses to delegate login to Google |
| **[OIDC](oidc.md)** | The identity layer on top of OAuth2 that tells volta who the user is |
| **[JWT](jwt.md) (RFC 7519)** | The token format volta uses to pass user info to apps |
| **JWKS (RFC 7517)** | How volta publishes its public key at `/.well-known/jwks.json` |

Protocol enforcement in volta:

- **HTTPS required in production** -- [Cookies](cookie.md) are set with the `Secure` flag, meaning they won't be sent over plain HTTP
- **RS256 only for JWTs** -- volta rejects JWTs signed with other algorithms (prevents `alg:none` and HS256 confusion attacks)
- **State + Nonce + [PKCE](pkce.md)** -- volta follows the full [OIDC](oidc.md) security protocol, not just the happy path

---

## Concrete example

Protocols involved when you log into a volta-protected app:

1. **HTTPS** -- [Browser](browser.md) connects to `https://app.acme.example.com` (encrypted)
2. **HTTP** -- [Reverse proxy](reverse-proxy.md) internally asks volta `http://volta:8080/auth/verify` (private [network](network.md), no encryption needed)
3. **HTTP 302** -- volta responds with a [redirect](redirect.md) to the [login](login.md) page
4. **[OAuth2](oauth2.md) Authorization Code Flow** -- volta constructs the authorization URL with `client_id`, `redirect_uri`, `state`, `code_challenge` ([PKCE](pkce.md))
5. **HTTPS** -- Browser connects to `https://accounts.google.com` (Google's auth server)
6. **[OIDC](oidc.md)** -- Google returns an `id_token` ([JWT](jwt.md)) with the user's identity
7. **HTTPS** -- volta calls Google's token endpoint to exchange the authorization code
8. **[JWT](jwt.md) RS256** -- volta creates a signed token for the app
9. **HTTP [Headers](header.md)** -- volta passes `X-Volta-JWT`, `X-Volta-User-Id`, etc. to the app

Each step uses a different protocol or builds on top of another. They're like layers of an onion -- OIDC builds on OAuth2, which builds on HTTP, which builds on TCP.

---

## Learn more

- [HTTP](http.md) -- The foundational web protocol
- [SSL/TLS](ssl-tls.md) -- The encryption protocol that turns HTTP into HTTPS
- [OAuth2](oauth2.md) -- The authorization protocol volta uses
- [OIDC](oidc.md) -- The identity protocol built on OAuth2
- [JWT](jwt.md) -- The token format protocol volta uses
- [Network](network.md) -- Where protocols operate
