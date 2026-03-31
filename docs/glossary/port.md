# Port Number

[日本語版はこちら](port.ja.md)

---

## What is it?

**A port number is like an apartment number in a building -- it tells the computer exactly which program should receive the message.**

It's OK not to know this! Port numbers work behind the scenes, and most people never need to think about them.

---

## A real-world analogy

Imagine a big apartment building:

```
  ┌─────────────────────────────────┐
  │  123 Internet Street            │  <-- This is the computer
  │  (the computer's address)       │      (like an IP address)
  │                                 │
  │   Apt 80   - Web Server         │  <-- HTTP (web pages)
  │   Apt 443  - Secure Web Server  │  <-- HTTPS (secure web pages)
  │   Apt 5432 - Postgres Database  │  <-- Database
  │   Apt 7070 - volta-auth-proxy   │  <-- volta!
  │   Apt 3000 - Your App           │  <-- Your application
  │                                 │
  └─────────────────────────────────┘
```

The building address (123 Internet Street) gets you to the right building, but the apartment number gets you to the right door. A port number works the same way -- it gets the message to the right program on the computer.

---

## Why can't two programs use the same port?

Just like two families can't live in the same apartment, two programs can't use the same port number at the same time. If volta is already using port 7070, another program can't also use 7070.

If you try, you'll get an error like:

```
  "Port 7070 is already in use!"
```

This is like showing up to an apartment and finding someone else already lives there.

---

## Common port numbers

Some port numbers are like famous addresses that everyone knows:

```
  Port 80    =  Web pages (HTTP)         -- like "Main Street"
  Port 443   =  Secure web pages (HTTPS) -- like "Main Street, VIP entrance"
  Port 5432  =  PostgreSQL database      -- the default for Postgres
  Port 3306  =  MySQL database           -- another database
  Port 22    =  SSH (remote login)       -- for accessing servers remotely
```

---

## How you see port numbers

You've probably never typed a port number because browsers hide the common ones:

```
  What you type:         https://example.com
  What actually happens: https://example.com:443   (port 443 is assumed)
```

But during development, you often see ports:

```
  http://localhost:7070   -- volta running on your computer, apartment 7070
  http://localhost:3000   -- your app running on your computer, apartment 3000
```

---

## In volta-auth-proxy

**In volta-auth-proxy:** The proxy server listens on port 7070, and the PostgreSQL database uses port 54329 -- different "apartment numbers" so they don't conflict with each other or with other programs on your computer.
