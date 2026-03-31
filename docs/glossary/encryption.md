# Encryption

[日本語版はこちら](encryption.ja.md)

---

## What is it?

**Encryption is turning readable text into scrambled gibberish that only someone with the right key can unscramble -- like writing in a secret code that only your best friend knows.**

It's OK not to know this! Encryption protects you every day -- when you shop online, send messages, or log into a website. It works silently in the background.

---

## A real-world analogy

Think of a diary with a lock:

```
  ┌────────────────────────────────────────────────────┐
  │                                                    │
  │  Your diary (original):    "I like pizza"          │
  │                                                    │
  │         │                                          │
  │         ▼  LOCK (encrypt with a key)               │
  │                                                    │
  │  Locked diary:             "xK9$mQ2!pL"            │
  │                                                    │
  │  Someone finds it:         "What does xK9$mQ2!pL   │
  │                             mean?? I have no idea!" │
  │                                                    │
  │         │                                          │
  │         ▼  UNLOCK (decrypt with the key)           │
  │                                                    │
  │  Your friend (has key):    "I like pizza"           │
  │                            "Ah, got it!"           │
  │                                                    │
  └────────────────────────────────────────────────────┘
```

- **Encryption** = locking the diary (turning readable text into gibberish)
- **Decryption** = unlocking the diary (turning gibberish back into readable text)
- **Key** = the thing that locks and unlocks it

Without the key, the scrambled text is useless -- even if someone steals it.

---

## A simple example

Imagine a very basic encryption: shift every letter by 3 positions.

```
  Original:   HELLO
  Encrypted:  KHOOR    (H→K, E→H, L→O, L→O, O→R)
```

If you know the rule ("shift by 3"), you can decrypt it. If you don't, "KHOOR" means nothing to you.

Real encryption is MUCH more complex than this (computers use mathematical puzzles that would take millions of years to crack), but the idea is the same.

---

## Where is encryption used?

Encryption is everywhere:

```
  🔒 Online shopping    - Your credit card number is encrypted
  🔒 Messaging apps     - Your messages are encrypted end-to-end
  🔒 Website logins     - Your password is encrypted in transit
  🔒 Banking apps       - All your financial data is encrypted
  🔒 Wi-Fi passwords    - Your home Wi-Fi encrypts your traffic
```

That little padlock icon in your browser's address bar? It means the connection between you and the website is encrypted.

---

## Why does encryption matter?

Without encryption:

```
  You:     "My password is 'sushi123'"
  Internet: passes through 20 different computers to reach the server
  Hacker:  "I can read this! Their password is 'sushi123'!"
```

With encryption:

```
  You:     "My password is 'sushi123'"  →  encrypted to "aX$9kL!mP2"
  Internet: passes through 20 different computers
  Hacker:  "I can see... aX$9kL!mP2. That means nothing to me."
  Server:  decrypts "aX$9kL!mP2" → "sushi123" → "Login successful!"
```

---

## In volta-auth-proxy

**In volta-auth-proxy:** Encryption is used to protect signing keys that verify login tokens -- even if someone gained access to the database, the encrypted keys would be useless without the decryption key, keeping your authentication system secure.
