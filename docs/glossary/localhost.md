# localhost

[日本語版はこちら](localhost.ja.md)

---

## What is it?

**localhost is your own computer pretending to be a website -- it's the address you use to visit a web page that's running on your own machine, without using the internet.**

It's OK not to know this! localhost is mainly used by developers when they're building and testing software before sharing it with the world.

---

## A real-world analogy

Imagine you're a chef developing a new recipe:

```
  Testing in your home kitchen  =  localhost
  Opening a restaurant          =  deploying to a real server
```

You wouldn't invite 100 customers to taste your new dish the very first time you make it! Instead, you cook it at home first, taste it yourself, adjust the seasoning, and only when it's perfect do you serve it at the restaurant.

**localhost is your home kitchen for software.** You run the program on your own computer, test it, fix problems, and only then put it on a real server for others to use.

---

## The address: 127.0.0.1

Every computer has a special address that always means "myself":

```
  localhost = 127.0.0.1 = "this computer right here"
```

These two are the same thing -- just different ways to say "me":

```
  http://localhost:7070     -- volta running on YOUR computer
  http://127.0.0.1:7070    -- exactly the same thing!
```

No matter what computer you're on, `localhost` always points to THAT computer. It never goes to the internet. It's like saying "my house" -- no matter who says it, it means THEIR house.

---

## Why test on localhost first?

```
  ┌─────────────────────────────────────────────────────┐
  │                                                     │
  │  Step 1: Build and test on localhost                │
  │          (just you, safe to make mistakes)           │
  │                    │                                │
  │                    ▼                                │
  │  Step 2: Show teammates on a staging server         │
  │          (small group, still private)                │
  │                    │                                │
  │                    ▼                                │
  │  Step 3: Deploy to production                       │
  │          (everyone can use it!)                      │
  │                                                     │
  └─────────────────────────────────────────────────────┘
```

Testing on localhost is safe because:
- Only YOU can see it (no one else can access your localhost)
- Mistakes don't affect anyone
- You don't need an internet connection
- It's fast (no network delays)

---

## In volta-auth-proxy

**In volta-auth-proxy:** During development, you run volta on localhost (typically at `http://localhost:7070`), which lets you test login flows, page designs, and security features on your own computer before deploying to a real server.
