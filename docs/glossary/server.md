# Server

[日本語版はこちら](server.ja.md)

---

## What is it?

**A server is a computer that's always on, always waiting, ready to help other computers when they ask -- like a restaurant kitchen that's always ready to cook when an order comes in.**

It's OK not to know this! You interact with servers every single day without realizing it.

---

## A real-world analogy

Think of a restaurant:

```
  ┌──────────────────────────────────────────────────┐
  │                                                  │
  │   Customers          Kitchen                     │
  │   (browsers)         (server)                    │
  │                                                  │
  │   "I'd like a   -->  Receives order              │
  │    pizza!"            Makes the pizza             │
  │                       Sends it back               │
  │   "Here's your  <--                              │
  │    pizza!"                                       │
  │                                                  │
  │   The kitchen doesn't decide when to cook.       │
  │   It WAITS for orders, then responds.            │
  │                                                  │
  └──────────────────────────────────────────────────┘
```

A server works exactly like this kitchen:

1. It sits and **waits** for requests
2. A request comes in (someone wants a web page, or wants to log in)
3. It **processes** the request (looks up the page, checks the password)
4. It **sends back** a response (the page, or "login successful!")
5. Goes back to waiting

---

## Server is just a role, not a special machine

Here's a secret: a server is just a regular computer. Your laptop could be a server! The word "server" describes what the computer *does* (it serves others), not what it *is*.

```
  Your laptop browsing Google    = Your laptop is a CLIENT (asking for things)
  Your laptop running volta      = Your laptop is a SERVER (answering requests)
```

However, in professional settings, servers are usually special computers that:
- Are always turned on (24/7)
- Have reliable internet connections
- Are kept in secure, temperature-controlled rooms

---

## Client and Server

The computer asking for something is called the **client**. The computer answering is called the **server**. It's like the customer-waiter relationship:

```
  Client (your browser)  ---"Give me the login page"--->  Server (volta)
  Client (your browser)  <--"Here's the login page"----  Server (volta)
```

---

## In volta-auth-proxy

**In volta-auth-proxy:** volta-auth-proxy IS a server -- it waits for login requests, processes them (checking passwords, creating sessions), and sends back responses to your browser, just like a kitchen waiting for and fulfilling orders.
