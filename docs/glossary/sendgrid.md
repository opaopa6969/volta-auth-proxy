# SendGrid

[日本語版はこちら](sendgrid.ja.md)

---

## What is it?

SendGrid (now part of Twilio) is a cloud-based email delivery service that lets applications send transactional and marketing emails through an API. Instead of running your own mail server (which is notoriously difficult to get right), you hand your emails to SendGrid and they handle the delivery, spam filtering, bounce management, and deliverability reputation.

Think of SendGrid like a professional courier service for letters. You could walk to each recipient's house yourself (run your own SMTP server), but you would deal with locked gates (spam filters), wrong addresses (bounces), and a bad reputation if you accidentally deliver to the wrong neighborhood. The courier service (SendGrid) has established trust with all the post offices (email providers), knows the routes, and tracks every delivery. You just hand them the letter and the address.

Email delivery sounds simple but is actually one of the hardest infrastructure problems. Getting email past spam filters requires IP reputation management, SPF/DKIM/DMARC authentication, feedback loop processing, and compliance with anti-spam regulations. SendGrid handles all of this.

---

## Why does it matter?

volta-auth-proxy needs to send emails for several authentication-related flows:

| Email type | When it is sent |
|-----------|----------------|
| **Invitation** | A tenant admin invites a new user to join |
| **Password reset** | A user requests a password reset (if email/password auth is enabled) |
| **MFA recovery** | A user needs to recover MFA access |
| **Security alert** | Unusual login detected (new device, new location) |
| **Account notifications** | Subscription changes, plan upgrades (from [Stripe](stripe.md) webhooks) |
| **Admin alerts** | Brute force detected, rate limit exceeded, tenant issues |

These are **transactional emails** -- triggered by user actions, not bulk marketing. They must arrive quickly and reliably. A password reset email that takes 30 minutes to arrive (or never arrives) is a broken product.

---

## How does it work?

### Sending an email via SendGrid API

```
  volta-auth-proxy
       │
       │  POST https://api.sendgrid.com/v3/mail/send
       │  Authorization: Bearer SG.xxxxxxxx
       │  {
       │    "personalizations": [{
       │      "to": [{"email": "alice@acme.com"}],
       │      "dynamic_template_data": {
       │        "invite_link": "https://auth.example.com/invite/abc123",
       │        "tenant_name": "Acme Corp"
       │      }
       │    }],
       │    "from": {"email": "noreply@example.com"},
       │    "template_id": "d-abc123template"
       │  }
       │
       ▼
  SendGrid
       │
       │  1. Validate API key
       │  2. Render template with dynamic data
       │  3. Sign with DKIM
       │  4. Queue for delivery
       │  5. Retry on soft bounce
       │  6. Track open/click/bounce
       │
       ▼
  alice@acme.com's inbox
```

### Email authentication (SPF, DKIM, DMARC)

For emails to reach the inbox (not spam), three authentication mechanisms must be configured:

| Mechanism | What it does | How to set up |
|-----------|-------------|---------------|
| **SPF** | Tells receiving servers which IPs are allowed to send email for your domain | DNS TXT record: `v=spf1 include:sendgrid.net ~all` |
| **DKIM** | Cryptographically signs the email so it cannot be tampered with | DNS CNAME records provided by SendGrid |
| **DMARC** | Tells receiving servers what to do if SPF/DKIM fail (reject, quarantine, none) | DNS TXT record: `v=DMARC1; p=quarantine; ...` |

### SendGrid vs alternatives

| Feature | SendGrid | Amazon SES | Mailgun | Postmark |
|---------|----------|-----------|---------|----------|
| Pricing | Free (100/day), then $15+/month | $0.10/1000 emails | $0.80/1000 emails | $1.25/1000 emails |
| API quality | Good | Basic | Good | Excellent |
| Templates | Dynamic templates (Handlebars) | Basic | Yes | Yes |
| Deliverability | Good | Good (but you manage reputation) | Good | Excellent (transactional-only) |
| Webhooks (delivery events) | Yes | Yes (via SNS) | Yes | Yes |
| Dedicated IP | Available (paid) | Default | Available (paid) | Shared (managed) |
| Ease of setup | Easy | Moderate (sandbox mode, verification) | Easy | Easy |

---

## How does volta-auth-proxy use it?

SendGrid is an **optional notification channel** for volta-auth-proxy. volta can be configured to send emails via SendGrid, but it also supports other providers or can skip email entirely (for deployments where email is not needed).

### Integration

volta uses SendGrid's REST API (not SMTP) for reliability and tracking:

```env
# .env configuration
EMAIL_PROVIDER=sendgrid
SENDGRID_API_KEY=SG.xxxxxxxxxxxxxxxxxxxxxxxx
EMAIL_FROM=noreply@yourdomain.com
EMAIL_FROM_NAME=Your SaaS Name
```

### Email templates

volta uses SendGrid dynamic templates with Handlebars syntax. Each email type has a template in SendGrid:

| Template | Variables | Purpose |
|----------|----------|---------|
| Invitation | `{{invite_link}}`, `{{tenant_name}}`, `{{inviter_name}}` | Invite users to a tenant |
| Security alert | `{{user_name}}`, `{{event_type}}`, `{{ip}}`, `{{location}}` | Alert on unusual activity |
| Admin notification | `{{tenant_name}}`, `{{alert_type}}`, `{{details}}` | Alert tenant admins |

### Why SendGrid specifically?

volta does not mandate SendGrid -- it is one option among several. SendGrid is mentioned as the default because:

1. **Free tier**: 100 emails/day for free (sufficient for small deployments)
2. **API simplicity**: A single REST call to send an email
3. **Template system**: Dynamic templates keep email formatting out of volta's code
4. **Delivery tracking**: Webhooks for bounces, opens, and clicks

volta's email integration is abstracted behind an interface, so switching from SendGrid to Amazon SES, Mailgun, or even a local SMTP server requires changing only the configuration, not the code.

---

## Common mistakes and attacks

### Mistake 1: Using a shared sending domain

If you send from `noreply@yourdomain.com` without configuring SPF, DKIM, and DMARC, emails will land in spam. Always authenticate your sending domain in SendGrid's dashboard.

### Mistake 2: Not handling bounces

If you keep sending to email addresses that bounce (hard bounces), SendGrid will reduce your sender reputation. volta should process SendGrid bounce webhooks and mark invalid addresses.

### Mistake 3: Leaking the API key

The SendGrid API key allows sending emails as your domain. If leaked (in code, logs, or client-side), attackers can send phishing emails that appear to come from your domain. Store the key in environment variables, never in source code.

### Mistake 4: Sending too many emails

Invitation or notification emails should be rate-limited per tenant. An attacker who gains access to the "invite user" feature could spam thousands of invitations, burning through your SendGrid quota and potentially getting your domain flagged as spam.

### Attack: Email-based account takeover

If an attacker can trigger a password reset email and intercept it (e.g., via DNS hijacking of the recipient's domain), they can reset the password and take over the account. Mitigate with:
- Short-lived reset tokens (10-15 minutes)
- One-time use tokens
- Notification to the user's other channels (e.g., in-app notification)

---

## Further reading

- [SendGrid documentation](https://docs.sendgrid.com/) -- Official reference.
- [SendGrid API v3](https://docs.sendgrid.com/api-reference/mail-send/mail-send) -- Mail send endpoint.
- [Email authentication (SPF/DKIM/DMARC)](https://docs.sendgrid.com/ui/account-and-settings/how-to-set-up-domain-authentication) -- Setting up domain authentication.
- [stripe.md](stripe.md) -- Billing events that trigger notification emails.
