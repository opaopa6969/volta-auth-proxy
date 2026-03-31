# Open Source

[日本語版はこちら](open-source.ja.md)

---

## What is it?

**Open source software is software where anyone can see the recipe -- the instructions (code) are public, free to use, and free to modify.**

It's OK not to know this! Open source is a concept, not a technology. Once you understand the idea, it's very simple.

---

## A real-world analogy

Think about recipes:

```
  Closed source (proprietary):        Open source:

  ┌─────────────────────┐             ┌─────────────────────┐
  │  SECRET Recipe       │             │  PUBLIC Recipe       │
  │                      │             │                      │
  │  Coca-Cola's formula │             │  Your grandmother's  │
  │  is locked in a      │             │  cookie recipe that  │
  │  vault. You can      │             │  she shares with     │
  │  buy the drink,      │             │  everyone. Anyone    │
  │  but you can't see   │             │  can make it, change │
  │  how it's made or    │             │  it, or improve it.  │
  │  make your own.      │             │                      │
  └─────────────────────┘             └─────────────────────┘
```

Open source software is like grandma's cookie recipe: anyone can read it, use it, modify it, and share their improvements with others.

---

## What "open source" means in practice

| You CAN: | You CANNOT (usually): |
|----------|----------------------|
| Read all the code | Claim you wrote it (must give credit) |
| Use it for free | (Some licenses have specific rules) |
| Modify it for your needs | |
| Share it with others | |
| Learn from it | |

---

## Why do people give away software for free?

Great question! There are many reasons:

- **Community improvement** -- Thousands of people can find and fix bugs, making the software better than any single company could
- **Trust** -- When the code is public, anyone can verify it's safe (no hidden spying)
- **Learning** -- New developers learn by reading real code
- **Giving back** -- Many developers benefited from open source and want to help others
- **Business models** -- Companies often make money from support, hosting, or premium features while keeping the core software free

---

## Famous open source projects

You probably use open source every day without knowing it:

```
  Linux       - Runs most of the internet's servers
  Firefox     - Web browser
  Android     - Phone operating system (based on Linux)
  Wikipedia   - The software behind it is open source
  PostgreSQL  - Database (used by volta!)
  WordPress   - Powers ~40% of all websites
```

---

## In volta-auth-proxy

**In volta-auth-proxy:** All of volta's dependencies are open source -- the web server (Javalin), the database (PostgreSQL), the template engine (jte), and many other libraries. This means the entire stack can be inspected, trusted, and improved by anyone.
