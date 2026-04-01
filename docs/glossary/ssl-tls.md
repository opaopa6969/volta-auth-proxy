# SSL/TLS

[日本語版はこちら](ssl-tls.ja.md)

---

## In one sentence?

SSL/TLS is the encryption technology that protects data traveling between your [browser](browser.md) and a [server](server.md) -- it's the reason you see a padlock icon in your address bar.

---

## The sealed envelope

Sending data over the internet without SSL/TLS is like sending a postcard -- anyone who handles it along the way can read it. SSL/TLS turns that postcard into a sealed envelope:

| Postcard (HTTP) | Sealed envelope (HTTPS) |
|---|---|
| Anyone in the mail chain can read it | Only sender and receiver can read it |
| Anyone can modify the message | Tampering is detected |
| You don't know if the address is real | The envelope proves who the recipient is |
| Free, easy | Slightly more work (but free with Let's Encrypt) |

**SSL vs TLS -- what's the difference?**

- **SSL** (Secure Sockets Layer) -- The original, now deprecated. SSL 3.0 was the last version (1996).
- **TLS** (Transport Layer Security) -- The modern successor. TLS 1.2 and 1.3 are current.
- Everyone says "SSL" out of habit, but the actual technology in use today is TLS. It's like saying "tape" when you mean "record" -- the old name stuck.

---

## Why do we need this?

Without SSL/TLS:

- **Passwords sent in plain text** -- Anyone on the same [network](network.md) (coffee shop Wi-Fi) can capture your [credentials](credentials.md)
- **[Session](session.md) [cookies](cookie.md) stolen** -- An attacker on the network can grab your session cookie and impersonate you
- **Data tampering** -- Your ISP or a man-in-the-middle could modify web pages (inject ads, change content)
- **No identity verification** -- You couldn't tell if `mybank.com` is really your bank or an attacker's server
- **[Cookie](cookie.md) `Secure` flag useless** -- The `Secure` cookie attribute depends on HTTPS being available

---

## SSL/TLS in volta-auth-proxy

volta requires HTTPS in production for all external communication:

```
  Browser ──HTTPS──> Reverse Proxy ──HTTP──> volta ──HTTP──> PostgreSQL
  ▲                  ▲                       ▲
  encrypted          unencrypted but         private network,
  (public internet)  isolated network        no internet access
```

Where SSL/TLS is used:

| Connection | Encrypted? | Why |
|---|---|---|
| Browser to Reverse Proxy | **Yes (HTTPS)** | Crosses the public internet |
| Reverse Proxy to volta | No (HTTP) | Private [Docker network](network-isolation.md), no exposure |
| volta to Google (OIDC) | **Yes (HTTPS)** | Crosses the public internet |
| volta to PostgreSQL | No | Private network, same Docker host |

volta's SSL/TLS-related security measures:

- **`Secure` cookie flag** -- `__volta_session` is only sent over HTTPS connections
- **[HSTS](header.md)** -- Tells browsers to always use HTTPS for this [domain](domain.md)
- **[Wildcard certificates](wildcard-certificate.md)** -- One cert covers `*.example.com` for all tenant [subdomains](subdomain.md)
- **Certificate managed at the reverse proxy** -- volta itself doesn't handle certificates; the [reverse proxy](reverse-proxy.md) (Traefik/Nginx) terminates TLS

---

## Concrete example

What happens during the TLS handshake when you visit a volta-protected site:

1. [Browser](browser.md) connects to `https://app.acme.example.com` on [port](port.md) 443
2. **Client Hello** -- Browser says: "I support TLS 1.3 and TLS 1.2, here are my supported cipher suites"
3. **Server Hello** -- [Reverse proxy](reverse-proxy.md) says: "Let's use TLS 1.3 with this cipher suite"
4. **Certificate** -- Server sends its [wildcard certificate](wildcard-certificate.md) for `*.example.com`
5. **Verification** -- Browser checks: Is this certificate valid? Is it issued by a trusted authority? Does `*.example.com` match `app.acme.example.com`? Is it expired?
6. **Key Exchange** -- Browser and server agree on a shared secret using asymmetric cryptography
7. **Encrypted Connection** -- All subsequent data is encrypted with the shared secret
8. Browser shows the padlock icon
9. Now the [session cookie](cookie.md), [login](login.md) flow, and all data are protected from eavesdroppers

The entire handshake takes about 50-100 milliseconds. After that, every byte is encrypted.

If the certificate is invalid (self-signed, expired, wrong [domain](domain.md)), the browser shows a scary warning page. This is the browser protecting you from potential man-in-the-middle attacks.

---

## Learn more

- [Wildcard Certificate](wildcard-certificate.md) -- One cert for all subdomains
- [Domain](domain.md) -- What SSL/TLS certificates are tied to
- [Cookie](cookie.md) -- The `Secure` flag that requires HTTPS
- [Protocol](protocol.md) -- SSL/TLS is one of many internet protocols
- [Network](network.md) -- Where encryption protects your data
- [Credentials](credentials.md) -- What SSL/TLS protects from interception
