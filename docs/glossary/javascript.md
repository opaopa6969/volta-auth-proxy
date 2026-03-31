# JavaScript

[日本語版はこちら](javascript.ja.md)

---

## What is it?

**JavaScript is what makes web pages interactive -- the buttons that do things when you click them, the forms that check your input, and the pages that update without reloading.**

It's OK not to know this! JavaScript runs quietly in the background on almost every website you visit.

---

## A real-world analogy

Remember our house analogy?

```
  Building a house:

  HTML        = The walls, floors, and rooms (structure)
  CSS         = The paint, wallpaper, and furniture (appearance)
  JavaScript  = The electricity, plumbing, and doorbell (behavior)
```

Without JavaScript, a web page is like a beautiful poster -- you can look at it, but you can't interact with it. JavaScript makes things *happen*:

- Click a button and a menu appears
- Type in a search box and suggestions pop up
- Fill out a form and it tells you "Please enter a valid email" before you submit
- A countdown timer ticks down in real time

---

## What does JavaScript look like?

```javascript
// When someone clicks the "Login" button...
button.addEventListener("click", function() {
  // Check if they typed a username
  if (username.value === "") {
    alert("Please enter your username!");
  }
});
```

You don't need to understand the details! The point is: JavaScript says "When THIS happens, do THAT."

---

## JavaScript is NOT Java

This confuses everyone, even experienced people! Despite the similar names:

```
  Java        = A programming language for building servers and apps
  JavaScript  = A programming language for making web pages interactive

  They are completely different languages!
```

It's like "car" and "carpet" -- similar names, totally different things. (JavaScript was named to ride the popularity of Java in the 1990s. Marketing!)

---

## Where does JavaScript run?

JavaScript runs right inside your web browser (Chrome, Safari, Firefox). When you visit a web page, your browser:

1. Downloads the HTML (structure)
2. Downloads the CSS (appearance)
3. Downloads the JavaScript (behavior)
4. Puts it all together into the page you see and interact with

---

## In volta-auth-proxy

**In volta-auth-proxy:** A file called `volta.js` handles interactive behavior on the login page, such as automatically refreshing an expired session so users don't lose their work.
