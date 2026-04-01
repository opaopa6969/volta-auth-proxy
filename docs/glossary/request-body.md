# Request Body

[日本語版はこちら](request-body.ja.md)

---

## What is it?

A request body is the main content (the "payload") that a client sends to a server as part of an HTTP request. It is the data inside the envelope, as opposed to the headers, which are the writing on the outside of the envelope.

Think of it like sending a package through the mail. The package has a label on the outside (headers): who it is from, who it is to, how heavy it is, "fragile" warnings. And it has stuff inside (the body): a birthday present, a stack of documents, a 10-pound bag of coffee. The postal worker who sorts the package reads the label -- they do not open the box. The label is enough to route it correctly.

---

## Which requests have bodies?

Not all HTTP requests have bodies:

```
GET /api/users              ← No body. Just "give me the list."
DELETE /api/users/123       ← No body (usually). Just "delete this one."

POST /api/users             ← Has a body: { "name": "Taro", "email": "taro@..." }
PUT /api/users/123          ← Has a body: { "name": "Updated Name" }
PATCH /api/users/123        ← Has a body: { "email": "new@..." }
```

POST and PUT requests carry data -- a new user to create, a form submission, a file upload, a JSON payload. This data can be tiny (a few bytes of JSON) or huge (a 500MB video upload).

### What bodies look like

```
A JSON body (most common in APIs):
{
  "name": "Taro Yamada",
  "email": "taro@example.com",
  "role": "ADMIN"
}

A form body (HTML form submissions):
name=Taro+Yamada&email=taro%40example.com&role=ADMIN

A file upload body:
--boundary
Content-Disposition: form-data; name="avatar"
Content-Type: image/png

[... binary image data, potentially megabytes ...]
--boundary--
```

---

## Why volta never sees the body

This is one of the most important properties of the ForwardAuth pattern. When Traefik sends a ForwardAuth subrequest to volta, it sends only the headers -- not the request body.

```
What the user sends:
  POST /api/documents
  Cookie: __volta_session=abc123
  Content-Type: application/json

  { "title": "Secret Report", "content": "Confidential data..." }

What Traefik sends to volta (ForwardAuth subrequest):
  GET /auth/verify
  Cookie: __volta_session=abc123
  X-Forwarded-Host: docs.example.com
  X-Forwarded-Uri: /api/documents
  X-Forwarded-Method: POST

  (NO BODY)

What Traefik sends to the app (after volta approves):
  POST /api/documents
  X-Volta-User-Id: user-uuid
  X-Volta-Tenant-Id: tenant-uuid
  Content-Type: application/json

  { "title": "Secret Report", "content": "Confidential data..." }
```

volta sees the cookie (to identify the user), the forwarded headers (to know which app and endpoint), and nothing else. The actual document content -- "Secret Report" with its confidential data -- goes directly from Traefik to the app without ever passing through volta.

### Why this matters

1. **Privacy.** Request bodies often contain sensitive data: personal information, financial records, medical data, private messages. volta never sees this data, so it cannot leak it, log it, or be compelled to produce it.

2. **Performance.** A file upload might be 100MB. If volta had to receive, process, and forward that 100MB, it would be slow and use enormous memory. Instead, volta processes a few hundred bytes of headers while Traefik streams the body directly to the app.

3. **Security surface.** Every piece of data a system handles is a potential attack vector: injection attacks, buffer overflows, malformed input. By never touching request bodies, volta eliminates an entire category of potential vulnerabilities.

4. **Simplicity.** volta does not need to understand JSON parsing, multipart form parsing, file upload handling, or content encoding. It reads headers, checks a session, and returns headers. That is it.

---

## Comparison: reverse proxy vs ForwardAuth

```
Full reverse proxy (auth proxy sees everything):
  Browser  ──[full request]──►  Auth Proxy  ──[full request]──►  App
  The auth proxy receives and forwards the entire request,
  including the body. It is a bottleneck and a privacy risk.

ForwardAuth (volta sees headers only):
  Browser  ──[full request]──►  Traefik  ──[full request]──►  App
                                   │
                              [headers only]
                                   │
                                   ▼
                              volta-auth-proxy
                              (checks session,
                               returns headers,
                               never touches body)
```

---

## In volta-auth-proxy

volta uses the ForwardAuth pattern, which means it never receives, processes, or has access to request bodies -- it only sees HTTP headers, keeping it fast, private, and minimal in attack surface.

---

## Further reading

- [forwardauth.md](forwardauth.md) -- The full ForwardAuth flow, including what volta sees and does not see.
- [header.md](header.md) -- The "writing on the envelope" that volta does read.
- [network-hop.md](network-hop.md) -- Why sending less data over hops matters.
- [proxy-types.md](proxy-types.md) -- How different proxy types handle request bodies.
