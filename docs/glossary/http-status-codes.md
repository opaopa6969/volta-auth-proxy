# HTTP Status Codes

[日本語版はこちら](http-status-codes.ja.md)

---

## What is it in one sentence?

HTTP status codes are three-digit numbers that a server sends back to tell you whether your request succeeded, failed, or needs you to do something else -- like a waiter telling you "here's your food," "we're out of that," or "you need to pay first."

---

## The restaurant analogy

Every time you interact with a server (by visiting a URL or calling an API), the server responds with a status code. Think of it as the waiter's response at a restaurant:

| Code range | Category | Waiter says... |
|---|---|---|
| **2xx** | Success | "Here you go! Enjoy your meal." |
| **3xx** | Redirect | "We moved to a new location. Follow me." |
| **4xx** | Client error (your fault) | "You ordered something wrong." |
| **5xx** | Server error (their fault) | "Sorry, our kitchen is on fire." |

The first digit tells you the general category. Let us look at the specific codes that matter most for volta.

---

## 200 OK -- "Here's your food"

**What it means:** Everything worked. The server processed your request and is sending back the result.

**In volta:** When you successfully access a page or API endpoint. For example, when volta's ForwardAuth check passes and your app serves the page.

```
  GET /api/v1/users/me → 200 OK
  { "id": "taro-uuid", "email": "taro@acme.com" }

  Restaurant: "One pasta, as ordered. Enjoy!"
```

---

## 301 Moved Permanently -- "We moved, update your address book"

**What it means:** The thing you are looking for has permanently moved to a new URL. Your browser should use the new URL from now on.

**In volta:** If a URL structure changes permanently. For example, if `/login` permanently becomes `/auth/login`.

```
  GET /login → 301 Moved Permanently
  Location: /auth/login

  Restaurant: "We've permanently moved next door.
               Please update your GPS."
```

---

## 302 Found (Temporary Redirect) -- "Follow me to a different table"

**What it means:** The thing you want is temporarily at a different URL. Your browser should go there this time, but keep using the original URL in the future.

**In volta:** This is the most common redirect in volta. When you are not logged in and visit a protected page, volta redirects you to the login page. After login, you are redirected back.

```
  GET /wiki/home → 302 Found
  Location: /login?redirect=/wiki/home

  Restaurant: "Your table isn't ready yet.
               Wait at the bar and we'll call you."
```

---

## 400 Bad Request -- "I can't understand your order"

**What it means:** The server cannot process your request because something is wrong with it. Maybe you sent invalid data or left out a required field.

**In volta:** When an API call is missing required fields or has invalid data. For example, trying to create an invitation without specifying an email address.

```
  POST /api/v1/tenants/acme-uuid/invitations
  Body: { "role": "MEMBER" }
  → 400 Bad Request: "email is required"

  Restaurant: "You said 'I want the thing with the stuff.'
               I need a specific dish name."
```

---

## 401 Unauthorized -- "Who are you? Show me your ID"

**What it means:** The server does not know who you are. You need to log in (authenticate) first.

**In volta:** When you try to access a protected resource without being logged in, or when your session has expired.

```
  GET /api/v1/users/me (no session cookie)
  → 401 Unauthorized

  Restaurant: "This is a members-only club.
               Show me your membership card."
```

Note: Despite the name "Unauthorized," this code actually means "not authenticated" (not "not authorized"). This is a famous naming mistake in the HTTP specification. See [authentication-vs-authorization.md](authentication-vs-authorization.md) for more.

---

## 403 Forbidden -- "I know who you are, but you can't have that"

**What it means:** The server knows who you are (you are logged in), but you do not have permission to access this resource.

**In volta:** When a MEMBER tries to access an admin-only page, or when a VIEWER tries to edit something.

```
  GET /admin/settings (Taro is logged in as MEMBER)
  → 403 Forbidden

  Restaurant: "I know you're a regular customer,
               but this table is reserved for VIPs only."
```

---

## 404 Not Found -- "We don't have that on the menu"

**What it means:** The server cannot find what you are looking for. The URL does not exist.

**In volta:** When you try to access a page or API endpoint that does not exist. volta also returns 404 when a tenant or user is not found (instead of revealing that the resource exists but you cannot access it).

```
  GET /api/v1/tenants/nonexistent-uuid
  → 404 Not Found

  Restaurant: "We don't serve that here.
               Are you sure you're in the right restaurant?"
```

---

## 429 Too Many Requests -- "Slow down, you're ordering too fast"

**What it means:** You have sent too many requests in a short period. The server is rate-limiting you to prevent abuse.

**In volta:** When someone tries to log in too many times (possible brute force attack), or when an API client makes too many requests.

```
  POST /auth/login (10th attempt in 1 minute)
  → 429 Too Many Requests
  Retry-After: 60

  Restaurant: "You've sent back your order 10 times in a row.
               Please wait 60 seconds before ordering again."
```

---

## 500 Internal Server Error -- "Our kitchen is on fire"

**What it means:** Something went wrong on the server side. This is not your fault -- the server has a bug or encountered an unexpected situation.

**In volta:** When there is a database error, a configuration mistake, or any unhandled exception. If you see a 500, report it -- it is a bug.

```
  GET /api/v1/users/me
  → 500 Internal Server Error

  Restaurant: "I'm terribly sorry. The chef dropped your dinner
               on the floor. We'll fix this right away."
```

---

## Quick reference table

| Code | Name | Whose fault? | volta example |
|---|---|---|---|
| 200 | OK | Nobody's (success!) | Page loads, API returns data |
| 301 | Moved Permanently | Nobody's (just a redirect) | Old URL permanently changed |
| 302 | Found (Redirect) | Nobody's (just a redirect) | "Go log in first, then come back" |
| 400 | Bad Request | Client (you) | Missing or invalid data in request |
| 401 | Unauthorized | Client (you) | Not logged in, session expired |
| 403 | Forbidden | Client (you) | Logged in but wrong role |
| 404 | Not Found | Client (you) | URL or resource does not exist |
| 429 | Too Many Requests | Client (you) | Rate limit exceeded |
| 500 | Internal Server Error | Server (volta) | Bug or unexpected error |

---

## Further reading

- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- Understanding the difference between 401 and 403.
- [api.md](api.md) -- How APIs use status codes in responses.
- [rate-limiting.md](rate-limiting.md) -- How volta handles 429 responses.
