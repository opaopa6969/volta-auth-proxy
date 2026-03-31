# JSON

[日本語版はこちら](json.ja.md)

---

## What is it?

**JSON is a way to write down information so that both people and computers can read it -- like filling out a standardized form.**

It's OK not to know this! JSON is everywhere on the internet, and once you see the pattern, it's very easy to read.

---

## A real-world analogy

When you go to the doctor, you fill out a form:

```
  +-------------------------------+
  | Patient Information Form      |
  |-------------------------------|
  | Name:    Yuki Tanaka          |
  | Age:     28                   |
  | Allergies: Peanuts, Dust      |
  | Smoker:  No                   |
  +-------------------------------+
```

JSON is like this form, but written in a way that computers can also understand. The form above, written as JSON, looks like this:

```json
{
  "name": "Yuki Tanaka",
  "age": 28,
  "allergies": ["Peanuts", "Dust"],
  "smoker": false
}
```

---

## The rules are simple

JSON has just a few rules:

1. **Names go in quotes** on the left side: `"name"`
2. **A colon** separates the name from the value: `"name": "Yuki"`
3. **Text values go in quotes**: `"Yuki Tanaka"`
4. **Numbers don't need quotes**: `28`
5. **Yes/No is written as true/false**: `false`
6. **Lists use square brackets**: `["Peanuts", "Dust"]`
7. **Curly braces wrap the whole thing**: `{ ... }`

That's it! You now know JSON.

---

## Why do computers use JSON?

Computers need to send information to each other all the time. They need a format that:

- Is the same everywhere (standardized)
- Is easy for computers to read quickly
- Is readable enough for humans to check

JSON is that format. When you check the weather on your phone, the weather service sends JSON to your phone:

```json
{
  "city": "Tokyo",
  "temperature": 22,
  "unit": "celsius",
  "forecast": "sunny"
}
```

Your phone then turns this into the pretty weather screen you see.

---

## In volta-auth-proxy

**In volta-auth-proxy:** JSON is used throughout the system -- tokens that prove you're logged in are JSON, API responses are JSON, and configuration data is exchanged as JSON between services.
