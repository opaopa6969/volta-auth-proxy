# URL

[日本語版はこちら](url.ja.md)

---

## What is it?

**A URL is the address of a web page -- like a street address, but for the internet.**

It's OK not to know this! You use URLs every day when you type something into your browser's address bar or click a link.

---

## A real-world analogy

Think of a street address:

```
  Street address:    123 Main Street, Apt 4B, Tokyo, Japan
  URL:               https://shop.example.com:443/shoes?color=red
```

Just like a street address has parts (country, city, street, apartment), a URL has parts too. Let's break one down.

---

## The parts of a URL

```
  https://shop.example.com:443/shoes/sneakers?color=red&size=28
  ─────   ────────────────  ─── ─────────────── ───────────────
    │            │            │        │               │
    │            │            │        │               └─ Query: filters/options
    │            │            │        └─ Path: which page
    │            │            └─ Port: which door (usually hidden)
    │            └─ Domain: which building
    └─ Protocol: how to get there
```

Let's use a shopping mall analogy:

| URL Part | Mall Analogy | Example |
|----------|-------------|---------|
| **Protocol** (`https`) | How you travel there (car, bus) | `https` = secure, `http` = not secure |
| **Domain** (`shop.example.com`) | The name of the mall | Like "Sunshine Shopping Center" |
| **Port** (`:443`) | Which entrance door | Usually hidden (443 is the default for https) |
| **Path** (`/shoes/sneakers`) | Which store and section inside | "Go to the shoe store, sneakers section" |
| **Query** (`?color=red&size=28`) | What you're looking for | "I want red ones, size 28" |

---

## You already know this!

When you tell a friend where to meet you:

```
  "Meet me at Sunshine Mall, second floor, Café Delicious, ask for a table by the window"

  That's like:
  https://sunshine-mall.com/floor-2/cafe-delicious?table=window
```

---

## Why the "s" in "https" matters

```
  http://   = Like sending a postcard -- anyone can read it along the way
  https://  = Like sending a sealed letter -- only you and the recipient can read it
```

The "s" stands for "secure." Always look for `https://` when entering passwords or personal information!

---

## In volta-auth-proxy

**In volta-auth-proxy:** URLs are used everywhere -- the login page has a URL, the callback after login has a URL, and the redirect URIs that control where users go after signing in are all URLs that must be carefully configured.
