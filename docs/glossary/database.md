# Database

[日本語版はこちら](database.ja.md)

---

## What is it?

**A database is a super-organized filing cabinet for computers -- it stores information in a way that makes it easy to find, update, and manage.**

It's OK not to know this! Databases are everywhere, quietly storing everything from your bank balance to your social media posts.

---

## A real-world analogy

Think of a school class roster:

```
  ┌─────────────────────────────────────────────────────┐
  │  CLASS 3A - Student Roster                          │
  │                                                     │
  │  Student ID │ Name          │ Age │ Favorite Color  │
  │  ───────────┼───────────────┼─────┼──────────────── │
  │  001        │ Yuki Tanaka   │ 9   │ Blue            │
  │  002        │ Hiro Suzuki   │ 9   │ Red             │
  │  003        │ Mei Watanabe  │ 10  │ Green           │
  │  004        │ Ken Sato      │ 9   │ Blue            │
  └─────────────────────────────────────────────────────┘
```

This roster is basically a **table** in a database! Let's learn the vocabulary:

| Database Word | Class Roster Equivalent |
|--------------|------------------------|
| **Table** | The whole roster |
| **Row** | One student's information (one horizontal line) |
| **Column** | One category of information (Name, Age, etc.) |
| **Record** | Same as a row -- one complete entry |

---

## Why not just use a spreadsheet?

A spreadsheet (like Excel) works great for small amounts of data. But imagine:

- 10,000 students across 50 schools
- Hundreds of teachers editing at the same time
- Need to find "all 9-year-olds who like blue" instantly
- Must never lose any data, even if the power goes out

That's where a database shines. It's built for speed, safety, and handling many users at once.

---

## What can a database do?

Think of it as a very smart filing clerk:

```
  You:    "Find all students in Class 3A who are 9 years old"
  DB:     Here: Yuki, Hiro, and Ken!

  You:    "Change Mei's age to 10"
  DB:     Done! Updated.

  You:    "Add a new student: Sakura, age 9, likes Purple"
  DB:     Added! She's student 005.

  You:    "How many students like Blue?"
  DB:     2 students!
```

---

## What is PostgreSQL (Postgres)?

PostgreSQL (often just called "Postgres") is one of the most popular databases in the world. It's:

- **Free** -- it's open source (see [open-source](open-source.md))
- **Reliable** -- banks and governments trust it
- **Powerful** -- can handle millions of records

Think of it as the "Toyota" of databases -- reliable, well-built, widely trusted.

---

## In volta-auth-proxy

**In volta-auth-proxy:** PostgreSQL stores all the important data -- user accounts, login sessions, tenant configurations, and security settings. It's the "filing cabinet" that remembers everything volta needs to keep users safe.
