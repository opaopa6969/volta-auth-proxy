# Docker

[日本語版はこちら](docker.ja.md)

---

## What is it?

**Docker is a way to package a program along with everything it needs to run, all in one box -- like a shipping container that works the same no matter what ship carries it.**

It's OK not to know this! Docker is a tool that mostly developers use behind the scenes to make software easier to set up and run.

---

## A real-world analogy

Imagine you want to share your grandmother's cake recipe with a friend in another country:

**Without Docker (the old way):**
```
  "Here's the recipe! Oh, but you need THIS specific brand of flour,
  and YOUR oven works differently than mine, and you need to install
  a special mixer, and the altitude where you live changes the baking
  time, and..."

  Result: After 3 hours of troubleshooting, the cake still doesn't
  taste right.
```

**With Docker:**
```
  "Here's a magic box. It contains the recipe, all the ingredients,
  the exact oven, the mixer, and even the right altitude. Just
  press START."

  Result: Perfect cake, every time, anywhere.
```

Docker is that magic box. It packages a program with everything it needs -- the right versions of all software, the right settings, everything -- so it works the same on any computer.

---

## The shipping container analogy

```
  Before shipping containers:        With shipping containers:

  ┌─────────────────────┐            ┌─────────────────────┐
  │ Load cargo by hand  │            │ ┌─────┐ ┌─────┐    │
  │ Different for every │            │ │ Box │ │ Box │    │
  │ type of goods       │            │ │  1  │ │  2  │    │
  │ Breaks during       │            │ └─────┘ └─────┘    │
  │ transport           │            │ Standard size       │
  │ Takes forever       │            │ Works on any ship   │
  └─────────────────────┘            │ Works on any truck  │
                                     └─────────────────────┘
```

Before real shipping containers were invented, loading a ship was chaos -- every package was different. Shipping containers standardized everything: same size, same way to load, works on any ship, truck, or train.

Docker does the same thing for software.

---

## Why developers love Docker

The most famous problem in software:

```
  Developer: "It works on my computer!"
  Other developer: "Well, it doesn't work on mine."
```

Docker eliminates this problem. If it works in the Docker container, it works everywhere.

---

## Docker vocabulary (simplified)

| Word | Meaning |
|------|---------|
| **Image** | The recipe + ingredients (a blueprint) |
| **Container** | A running copy of the image (the actual cake) |
| **Dockerfile** | Instructions for building the image (the recipe card) |
| **docker-compose** | A way to run multiple containers together (a whole meal plan) |

---

## In volta-auth-proxy

**In volta-auth-proxy:** Docker is used to run the entire development environment -- the volta server, the PostgreSQL database, and supporting services all run in Docker containers, so you can start everything with a single command regardless of what computer you're using.
