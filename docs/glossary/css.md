# CSS

[日本語版はこちら](css.ja.md)

---

## What is it?

**CSS is what makes web pages look pretty -- the colors, fonts, spacing, and layout. If HTML is the skeleton of a building, CSS is the paint and decoration.**

It's OK not to know this! CSS works behind the scenes to make every website you visit look nice instead of ugly.

---

## A real-world analogy

Imagine you have a plain, unpainted house (that's HTML):

```
  Without CSS (bare HTML):         With CSS (styled):

  ┌──────────────────┐             ┌──────────────────┐
  │ Welcome           │             │ ✦ Welcome ✦      │
  │ Click here        │             │                  │
  │ Some text         │             │ [Click here]     │
  │                   │             │                  │
  │                   │             │ Some text in a   │
  │                   │             │ nice font...     │
  └──────────────────┘             └──────────────────┘
    Gray, boring,                     Colors, spacing,
    everything the same               buttons look nice
```

CSS is the interior decorator. It says: "Make the heading blue and big. Put the button in the center. Add some space between paragraphs. Use this nice font."

---

## What does CSS look like?

```css
/* Make all headings blue and large */
h1 {
  color: blue;
  font-size: 32px;
}

/* Make buttons look nice */
button {
  background-color: green;
  color: white;
  padding: 10px 20px;
  border-radius: 5px;
}

/* Add space between paragraphs */
p {
  margin-bottom: 16px;
}
```

The pattern is always:

```
  WHO to style {
    WHAT to change: NEW VALUE;
  }
```

For example: "For all buttons, make the background color green."

---

## CSS is like a dress code

Think of CSS like a school dress code:

- "All students (p) must wear blue shirts (color: blue)"
- "The principal's name tag (h1) should be in large font (font-size: 32px)"
- "Visitor badges (button) should have a green background (background-color: green)"

You write the rule once, and it applies to every matching element on the page.

---

## Why is CSS separate from HTML?

Imagine if every time you painted a room, you had to rebuild the walls. That would be silly! You want to be able to change the paint without touching the structure.

CSS works the same way. You can completely change how a website looks (colors, fonts, layout) without changing the HTML structure at all. This is why CSS lives in its own file, separate from HTML.

---

## In volta-auth-proxy

**In volta-auth-proxy:** A single file called `volta.css` controls the look and feel of all pages -- the login page, error pages, and account pages. To change the color scheme or fonts, you only need to edit this one CSS file.
