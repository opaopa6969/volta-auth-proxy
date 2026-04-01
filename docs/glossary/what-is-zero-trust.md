# What Is Zero Trust, Actually?

[日本語版はこちら](what-is-zero-trust.ja.md)

---

## The misconception

Zero trust is the most buzzword-ified term in security. It has been adopted by marketing departments, sprinkled onto landing pages, and used to sell everything from VPNs to endpoint detection software to identity platforms.

Ask ten people what zero trust means and you will get twelve answers, most of which boil down to "it is a security thing" or "it means you do not trust anything." Both of these are simultaneously correct and useless.

---

## What zero trust actually means

Zero trust is a security model built on one principle: **never trust, always verify.**

In a traditional network security model (sometimes called "castle and moat"), you have a perimeter. Everything inside the perimeter is trusted. Everything outside is not. A firewall protects the perimeter. A VPN lets you tunnel into the perimeter from outside. Once you are inside, you can go anywhere.

Zero trust says: there is no inside. Every request, from every source, at every layer, must prove it is authorized. Being on the corporate network does not give you access to anything. Having a valid VPN connection does not give you access to anything. Each resource verifies each request independently.

The three principles:

1. **Verify explicitly.** Always authenticate and authorize based on all available data: identity, location, device health, service or workload, data classification, and anomalies.
2. **Use least privilege access.** Limit access to the minimum necessary. Just-in-time and just-enough-access.
3. **Assume breach.** Design as if the attacker is already inside. Minimize blast radius, segment access, verify end-to-end encryption, and use analytics to detect threats.

---

## What zero trust does NOT mean

**It does not mean "trust nothing."** You trust things. You trust them after verifying them, and you trust them for a limited time, for a limited scope. Zero trust does not mean paranoia. It means conditional trust with continuous verification.

**It does not mean "don't trust your own code."** Zero trust is about network and identity architecture, not about whether you trust your own `if` statements. Your code still needs to assume that data from your own database is valid. Zero trust applies to the boundaries, not to every line of code.

**It does not mean you need to buy a product.** The vendor landscape around zero trust is enormous and largely parasitic. Zero trust is an architecture and a set of principles. You can implement it with open-source tools, reverse proxies, and careful design. You do not need a "zero trust platform" that costs six figures per year.

**It does not mean zero risk.** No security model eliminates risk. Zero trust reduces the blast radius of a breach and makes lateral movement harder. It does not make breaches impossible.

---

## How volta implements zero trust (without calling it that)

volta-auth-proxy's ForwardAuth pattern is zero trust in practice, even though the project does not use the buzzword.

Here is how it maps:

### Never trust, always verify

Every request to a downstream app passes through Traefik, which forwards it to volta's ForwardAuth endpoint before the request reaches the app. Every single request. Not just the first one. Not just login.

```
User Request → Traefik → volta ForwardAuth → (200 OK?) → Downstream App
                                            → (401?)    → Redirect to Login
```

The downstream app never sees an unauthenticated request. It does not need to check authentication itself. It receives pre-verified identity via HTTP headers (`X-Volta-User-Id`, `X-Volta-Tenant-Id`, `X-Volta-Roles`). But -- and this is the zero trust part -- these headers only exist because volta verified the session on this specific request. There is no "trusted zone" where requests skip verification.

### Least privilege access

volta-config.yaml defines which roles can access which apps:

```yaml
apps:
  - name: admin-panel
    allowed_roles: [OWNER, ADMIN]
  - name: dashboard
    allowed_roles: [OWNER, ADMIN, MEMBER]
  - name: public-docs
    allowed_roles: [OWNER, ADMIN, MEMBER, VIEWER]
```

A VIEWER cannot access the admin panel. Not because the admin panel checks roles (it does not need to). But because volta's ForwardAuth rejects the request before it reaches the admin panel. Access is denied at the gateway, not at the application.

### Assume breach

volta's architecture assumes that any component might be compromised:

- **JWT expiry is 5 minutes.** If a token leaks, the window of exploitation is small.
- **Session limits (max 5 per user)** mean a compromised credential cannot generate unlimited sessions.
- **Rate limiting** means a brute force attack hits a wall quickly.
- **Audit logging** of every auth event means anomalies can be detected.
- **Tenant isolation** means a breach in one tenant does not give access to another. API path tenant ID must match JWT claim -- it is structurally prevented, not just policy-prevented.

### The network is not trusted

volta's ForwardAuth does not care where the request comes from. Internal network, external network, VPN, direct connection -- every request gets the same verification. There is no IP allowlist that says "requests from 10.0.0.0/8 are trusted." The session cookie is the identity. The session verification is the trust boundary. Nothing else.

---

## The honest gap

volta is not a complete zero trust implementation in the NIST 800-207 sense. Full zero trust includes device posture assessment, continuous behavioral analytics, micro-segmentation at the network layer, and integration with endpoint detection and response (EDR) systems.

volta handles the identity and access control portion. That is the part that matters most for a SaaS application, but it is not the complete picture. If you need full zero trust compliance for a regulated industry, volta is one piece of the puzzle, not the whole puzzle.

---

## It is OK not to know this

If you thought zero trust was a product you could buy, or a vague principle about not trusting things, you are in the majority. The term has been so aggressively marketed that its actual meaning has been buried under sales pitches.

The core idea is simple: verify every request, grant minimum access, assume the worst. volta does this through ForwardAuth without ever using the phrase "zero trust" in its architecture documents. Sometimes the best way to implement a principle is to just do it, rather than name it.

Now you know.
