# API (Application Programming Interface)

[日本語版はこちら](api.ja.md)

---

## What is it in one sentence?

An API is a set of rules and endpoints that lets one piece of software talk to another -- like a restaurant menu that tells you what you can order and how to order it.

---

## The restaurant menu analogy

When you go to a restaurant, you do not walk into the kitchen and start cooking. Instead:

1. **You read the menu** -- It tells you what is available (pasta, steak, salad)
2. **You place an order** -- "I'd like the pasta, please" (a request)
3. **The kitchen prepares it** -- You do not see or care how it is made
4. **You get your food** -- The result arrives at your table (a response)

An API works the same way:

- **The menu** = the API documentation (lists what you can ask for)
- **Placing an order** = sending a request to an endpoint (a URL)
- **The kitchen** = the server that processes your request
- **Your food** = the response data you get back

You never interact with the server's internal code, just like you never go into the kitchen. You use the menu (API) to communicate.

---

## REST basics (how most APIs work)

Most modern APIs follow a style called REST (Representational State Transfer). Do not worry about the fancy name -- it just means:

1. **Everything is a URL** -- Each thing you can interact with has its own address
2. **You use HTTP verbs** -- Different actions for different needs
3. **Data comes back as JSON** -- A simple text format for structured data

The HTTP verbs (actions you can take):

| Verb | What it means | Restaurant analogy |
|---|---|---|
| **GET** | "Give me information" | "Can I see the menu?" |
| **POST** | "Create something new" | "I'd like to place a new order" |
| **PUT** | "Replace something entirely" | "Change my entire order to something else" |
| **PATCH** | "Update part of something" | "Actually, make that pasta with extra cheese" |
| **DELETE** | "Remove something" | "Cancel my order" |

---

## What is JSON?

JSON (JavaScript Object Notation) is the format most APIs use to send data back and forth. It is a simple, readable way to represent structured information:

```json
{
  "name": "Taro Yamada",
  "email": "taro@acme.com",
  "role": "MEMBER",
  "tenant": {
    "id": "acme-uuid",
    "name": "ACME Corp"
  }
}
```

You can read this even if you have never seen JSON before. It is just key-value pairs (the name is "Taro Yamada", the email is "taro@acme.com", etc.) organized with curly braces and colons.

---

## How volta's Internal API works

volta-auth-proxy has an Internal API that your downstream apps can call to get information about users, tenants, and memberships. Think of it as a phone line from your app back to the front desk (volta).

Here are some examples:

**Get the current user's profile:**
```
GET /api/v1/users/me

Response:
{
  "id": "taro-uuid",
  "email": "taro@acme.com",
  "display_name": "Taro Yamada"
}
```

**List members of a tenant:**
```
GET /api/v1/tenants/acme-uuid/members

Response:
{
  "members": [
    { "user_id": "taro-uuid", "role": "OWNER", "email": "taro@acme.com" },
    { "user_id": "hanako-uuid", "role": "MEMBER", "email": "hanako@acme.com" }
  ]
}
```

**Invite someone to a tenant:**
```
POST /api/v1/tenants/acme-uuid/invitations
Body:
{
  "email": "newperson@example.com",
  "role": "MEMBER"
}

Response:
{
  "invitation_id": "inv-uuid",
  "status": "sent"
}
```

These API calls require authentication -- your app must include a service token (like a password for apps) in the request header. This proves to volta that your app is authorized to ask for this information.

---

## A simple example

Say your wiki app needs to show a list of all team members in the sidebar. Instead of querying the database directly (which might be wrong or stale), it asks volta's API:

```
  Wiki app needs member list:

  1. Wiki app → GET /api/v1/tenants/acme-uuid/members
     Header: Authorization: Bearer <service-token>

  2. volta-auth-proxy processes the request:
     - Checks the service token (is this app authorized?)
     - Looks up ACME Corp's members in the database
     - Returns the list as JSON

  3. Wiki app receives:
     [
       { "name": "Taro", "role": "OWNER" },
       { "name": "Hanako", "role": "ADMIN" },
       { "name": "Jiro", "role": "MEMBER" }
     ]

  4. Wiki app displays the member list in the sidebar
```

---

## Further reading

- [http-status-codes.md](http-status-codes.md) -- What the numbers in API responses mean (200, 400, 401, etc.).
- [sdk.md](sdk.md) -- Libraries that make calling volta's API easier.
- [downstream-app.md](downstream-app.md) -- The apps that call volta's API.
