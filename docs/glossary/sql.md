# SQL

[日本語版はこちら](sql.ja.md)

---

## What is it?

**SQL is the language you use to talk to a database -- like asking a librarian to find, add, or update books for you.**

It's OK not to know this! SQL is used behind the scenes by almost every app and website, but most people never see it.

---

## A real-world analogy

Imagine a school librarian who manages a roster of all students:

```
  You:       "Show me all students in Class 3A"
  Librarian: (looks through the roster, gives you a list)

  You:       "Show me all students who are 9 years old"
  Librarian: (filters the roster, gives you a shorter list)

  You:       "Add a new student: Sakura, age 9, Class 3B"
  Librarian: (writes a new line in the roster)
```

SQL is just a formal way to make these requests so a computer can understand them.

---

## Real SQL examples

Using our school roster from the [database](database.md) article:

### "Show me all students in Class 3A"

```sql
SELECT * FROM students WHERE class = '3A';
```

Reading it like English: **SELECT** everything **FROM** the students table **WHERE** the class is 3A.

### "Show me just the names of 9-year-olds"

```sql
SELECT name FROM students WHERE age = 9;
```

Result:

```
  name
  ──────────────
  Yuki Tanaka
  Hiro Suzuki
  Ken Sato
```

### "Add a new student"

```sql
INSERT INTO students (name, age, favorite_color)
VALUES ('Sakura Ito', 9, 'Purple');
```

Reading it like English: **INSERT INTO** the students table these values: name is Sakura Ito, age is 9, favorite color is Purple.

### "Update Mei's age"

```sql
UPDATE students SET age = 10 WHERE name = 'Mei Watanabe';
```

Reading it: **UPDATE** the students table, **SET** age to 10 **WHERE** the name is Mei Watanabe.

---

## The four basic operations

Almost everything you do with data boils down to four actions:

```
  ┌──────────────┬────────────────────────────────────┐
  │  SQL Command  │  What it does (librarian version)  │
  ├──────────────┼────────────────────────────────────┤
  │  SELECT       │  "Show me..."                      │
  │  INSERT       │  "Add a new..."                    │
  │  UPDATE       │  "Change this..."                  │
  │  DELETE       │  "Remove this..."                  │
  └──────────────┴────────────────────────────────────┘
```

That's it! These four commands cover most of what SQL does.

---

## Why is it called "SQL"?

SQL stands for "Structured Query Language." A "query" is just a fancy word for "question." So SQL is really just a "structured way to ask questions."

It's pronounced either "S-Q-L" (each letter) or "sequel" -- both are correct!

---

## In volta-auth-proxy

**In volta-auth-proxy:** SQL is used behind the scenes to store and retrieve user accounts, check login sessions, and manage tenant data in the PostgreSQL database. You don't need to write SQL yourself -- volta handles it automatically.
