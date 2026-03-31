# YAML

[日本語版はこちら](yaml.ja.md)

---

## What is it?

**YAML is a way to write settings and data that looks like a neatly organized shopping list.**

It's OK not to know this! YAML is just a format for writing things down in a structured way. If you can read a shopping list, you can read YAML.

---

## A real-world analogy

Think of a shopping list. You could write it messy:

```
eggs, milk, bread, 3 apples, cheese (cheddar and mozzarella)
```

Or you could write it neatly with indentation:

```
Dairy:
  - Milk
  - Cheese:
      - Cheddar
      - Mozzarella

Bakery:
  - Bread

Produce:
  - Apples (3)
  - Eggs
```

YAML is like the second version -- information organized with indentation and dashes. Computers can read it, and humans can read it too without squinting.

---

## What does YAML look like?

Here's a simple YAML file describing a person:

```yaml
name: Yuki Tanaka
age: 28
hobbies:
  - Reading
  - Cooking
  - Hiking
address:
  city: Tokyo
  zip: 100-0001
```

Notice:
- No quotes needed (usually)
- No curly braces or brackets
- Indentation (spaces at the beginning) shows what belongs to what
- Dashes (`-`) make lists

---

## YAML vs JSON

YAML and JSON store the same information, but YAML is easier to read:

```
  JSON (more punctuation):          YAML (cleaner):

  {                                 name: Yuki
    "name": "Yuki",                 age: 28
    "age": 28,                      hobbies:
    "hobbies": [                      - Reading
      "Reading",                      - Cooking
      "Cooking"
    ]
  }
```

YAML is what many projects choose for configuration files because humans need to read and edit them.

---

## The one tricky thing about YAML

**Spaces matter!** In YAML, indentation must use spaces (not tabs), and the number of spaces matters. This is valid:

```yaml
address:
  city: Tokyo     # 2 spaces -- good!
  zip: 100-0001   # 2 spaces -- good!
```

This is broken:

```yaml
address:
  city: Tokyo     # 2 spaces
    zip: 100-0001 # 4 spaces -- oops! Doesn't match!
```

Think of it like an outline for a school essay -- things at the same level need the same indentation.

---

## In volta-auth-proxy

**In volta-auth-proxy:** YAML is used for configuration files like `docker-compose.yml`, which describes how to start up all the services (the database, the proxy, etc.) together.
