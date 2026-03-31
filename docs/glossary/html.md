# HTML

[日本語版はこちら](html.ja.md)

---

## What is it?

**HTML is the language that creates web pages -- it's the skeleton and structure of everything you see in a web browser.**

It's OK not to know this! Every website you've ever visited is made of HTML, even if you've never thought about it.

---

## A real-world analogy

Think of building a house:

```
  +--------------------------------------------------+
  |  A house has structure:                           |
  |                                                   |
  |  - The frame (walls, floors, roof) = HTML         |
  |  - The paint and decorations       = CSS          |
  |  - The electricity and plumbing    = JavaScript   |
  +--------------------------------------------------+
```

HTML is the **frame of the house**. It defines: "There's a heading here, a paragraph there, a button over there, and an image at the bottom." It doesn't say what color the heading is or what happens when you click the button -- that's the job of CSS and JavaScript.

---

## What does HTML look like?

```html
<h1>Welcome to Our Store</h1>

<p>We sell the freshest fruit in town!</p>

<ul>
  <li>Apples</li>
  <li>Bananas</li>
  <li>Oranges</li>
</ul>

<button>Add to Cart</button>
```

This creates:

```
  ┌─────────────────────────────────┐
  │  Welcome to Our Store           │  <-- big heading
  │                                 │
  │  We sell the freshest fruit     │  <-- paragraph
  │  in town!                       │
  │                                 │
  │  • Apples                       │  <-- list
  │  • Bananas                      │
  │  • Oranges                      │
  │                                 │
  │  [Add to Cart]                  │  <-- button
  └─────────────────────────────────┘
```

---

## The basic pattern

HTML uses **tags** -- words wrapped in angle brackets:

- `<h1>...</h1>` = a big heading (h1 = heading level 1)
- `<p>...</p>` = a paragraph of text
- `<ul>...</ul>` = a list (ul = unordered list)
- `<li>...</li>` = one item in the list (li = list item)
- `<button>...</button>` = a clickable button
- `<img>` = an image

Most tags come in pairs: an opening tag `<p>` and a closing tag `</p>`. The content goes in between.

Think of it like putting things in labeled boxes:

```
  [START OF PARAGRAPH]
     Here is some text.
  [END OF PARAGRAPH]
```

---

## Why does HTML matter?

Every web page you see -- Google, Amazon, your bank, this documentation -- is HTML. Your browser (Chrome, Safari, Firefox) reads HTML and turns it into the visual page you see.

When you "view source" on any web page (right-click > View Page Source), you'll see HTML.

---

## In volta-auth-proxy

**In volta-auth-proxy:** The login page, error pages, and account pages are all built with HTML. When you see the login form asking for your username and password, that's HTML defining the structure of that page.
