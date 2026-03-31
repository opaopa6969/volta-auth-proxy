# Content-Type

[日本語版はこちら](content-type.ja.md)

---

## What is it?

`Content-Type` is an HTTP header that tells the receiver what kind of data is being sent. When your browser sends a form, it says "this is form data." When an API sends a response, it says "this is JSON." Without it, the receiver would have to guess what the bytes mean.

Common content types:

| Content-Type | What it is | Used for |
|-------------|-----------|----------|
| `application/json` | JSON data | API requests and responses |
| `text/html` | HTML page | Browser pages |
| `application/x-www-form-urlencoded` | Form data (key=value&key=value) | HTML form submissions |
| `multipart/form-data` | Mixed data (text + files) | File uploads |

---

## Why does it matter?

Content-Type matters for both **functionality** and **security**.

**Functionality:** The server needs to know how to parse the request body. A JSON body needs `JSON.parse()`, a form body needs URL decoding, a multipart body needs boundary parsing. Wrong Content-Type = broken parsing.

**Security:** Content-Type affects how browsers and servers process requests. For example:
- CSRF attacks typically use `application/x-www-form-urlencoded` (because browsers send this from HTML forms)
- Requiring `application/json` on mutation endpoints blocks many CSRF attacks, because browsers can't send JSON from a plain HTML form
- The `Accept` header (related to Content-Type) helps servers decide whether to return JSON or HTML

---

## A simple example

```
# Sending JSON to an API
POST /api/v1/tenants/abc/invitations
Content-Type: application/json

{"email": "alice@example.com", "role": "MEMBER"}

# Sending a traditional HTML form
POST /login
Content-Type: application/x-www-form-urlencoded

username=alice&password=secret&_csrf=token123
```

---

## In volta-auth-proxy

volta uses Content-Type in a security-critical way through its `isJsonOrXhr()` function:

```java
private static boolean isJsonOrXhr(Context ctx) {
    String accept = ctx.header("Accept");
    String contentType = ctx.header("Content-Type");
    String xrw = ctx.header("X-Requested-With");
    return (accept != null && accept.toLowerCase().contains("application/json"))
            || (contentType != null && contentType.toLowerCase().contains("application/json"))
            || "XMLHttpRequest".equalsIgnoreCase(xrw);
}
```

This function is used for **CSRF protection**: POST/DELETE/PATCH requests that are NOT JSON and NOT XHR must include a CSRF token. This works because:
- HTML forms can only send `application/x-www-form-urlencoded` or `multipart/form-data`
- HTML forms cannot set custom headers like `Accept: application/json`
- So if a request has `Content-Type: application/json`, it must have come from JavaScript (which is subject to CORS), not a cross-site form

volta also uses the `Accept` header to decide response format via `wantsJson()`. If the client sends `Accept: application/json`, volta returns JSON errors. Otherwise, it renders HTML error pages. This lets the same endpoints serve both browsers and API clients gracefully.

---

## See also

- [bearer-scheme.md](bearer-scheme.md) -- Another HTTP header used for authentication
- [idempotency.md](idempotency.md) -- Why POST requests with different content types need care
