# Java

[日本語版はこちら](java.ja.md)

---

## What is it?

**Java is a programming language -- a way to write instructions that tell a computer what to do. It's one of the most popular languages in the world because programs written in Java can run on almost any computer.**

It's OK not to know this! Java is used behind the scenes in countless apps and websites. You've probably used Java-powered software today without knowing it.

---

## A real-world analogy

Imagine you write a cookbook in English. The problem is:
- French chefs can't read it
- Japanese chefs can't read it
- Only English-speaking chefs can use it

Now imagine you write a **universal cookbook** that automatically translates itself into whatever language the chef speaks. Write it once, and it works in every kitchen in the world.

That's Java's big idea: **"Write once, run anywhere."**

```
  ┌───────────────────────────────────────────┐
  │  Java code                                │
  │  (written once)                           │
  │         │                                 │
  │         ▼                                 │
  │  ┌─────────────┐                          │
  │  │ Java Virtual │  (the automatic         │
  │  │ Machine (JVM)│   translator)           │
  │  └─────────────┘                          │
  │    │       │       │                      │
  │    ▼       ▼       ▼                      │
  │  Windows  Mac    Linux                    │
  │  ✓ Works  ✓ Works ✓ Works                 │
  └───────────────────────────────────────────┘
```

---

## What does Java look like?

Here's a tiny Java program:

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello! Welcome to volta.");
    }
}
```

This just prints "Hello! Welcome to volta." on the screen. Don't worry about all the extra words -- Java is a bit verbose (it uses more words than some other languages), but it's very clear and organized.

---

## What does "Java 21" mean?

Like a car model, Java gets new versions over time:

```
  Java 8   (2014) - The "classic" version, still widely used
  Java 11  (2018) - A popular long-term version
  Java 17  (2021) - Another long-term version
  Java 21  (2023) - The latest long-term version (volta uses this!)
```

Each version adds new features and improvements. "Java 21" just means "the version of Java released in 2023." It's like saying "iPhone 15" -- same product, newer model.

---

## Why is Java popular?

- **Runs everywhere** -- Windows, Mac, Linux, phones, even smart toasters
- **Huge community** -- millions of developers, tons of free libraries
- **Reliable** -- banks, hospitals, and governments trust it for critical systems
- **Fast enough** -- handles heavy workloads well
- **Job market** -- one of the most in-demand programming languages

---

## Why did volta choose Java?

- Java runs on any operating system
- Java 21 has modern features that make the code clean and fast
- The Java ecosystem has excellent libraries for web servers, databases, and security
- It's well-understood by many developers, making the project accessible

---

## In volta-auth-proxy

**In volta-auth-proxy:** The entire server is written in Java 21, which handles everything from processing login requests to managing sessions and communicating with the database.
