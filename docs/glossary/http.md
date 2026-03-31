# HTTP

[日本語版はこちら](http.ja.md)

---

## What is it?

**HTTP is how your web browser talks to websites -- like sending letters back and forth, but instantly.**

It's OK not to know this! HTTP happens automatically every time you visit a website. You've been using it every day without knowing it.

---

## A real-world analogy

Imagine you're at a restaurant:

```
  You (the browser):     "Can I see the menu?"        --> GET request
  Waiter (the server):   "Here's the menu!"           --> Response

  You:                   "I'd like to order a pizza."  --> POST request
  Waiter:                "Got it! Pizza coming up."    --> Response
```

HTTP works exactly like this. Your browser asks the server for something (a web page, an image, data), and the server sends it back. Every interaction is a **request** followed by a **response**.

---

## The two most common types

### GET -- "Can I have something?"

When you type a website address and press Enter, your browser sends a **GET** request. It's asking: "Please give me this page."

```
  You type: https://example.com/about

  Your browser sends:
  GET /about

  The server responds with:
  "Here's the About page!"  (200 OK)
```

GET is for **reading**. You're not changing anything, just looking.

### POST -- "Here's something for you"

When you fill out a form and click Submit, your browser sends a **POST** request. It's saying: "Here's my information, please do something with it."

```
  You fill in a login form and click "Sign In"

  Your browser sends:
  POST /login
  username=tanaka&password=****

  The server responds with:
  "Welcome! You're logged in."  (200 OK)
```

POST is for **sending data**. You're changing something (logging in, placing an order, submitting a form).

---

## Status codes -- the short answer

When the server responds, it includes a number that tells you how things went:

```
  200  =  "Everything is fine!" (OK)
  301  =  "This page has moved. Go look over there."
  404  =  "I can't find what you're looking for." (Not Found)
  500  =  "Something broke on my end, sorry." (Server Error)
```

You've probably seen "404 Not Found" before -- now you know what it means!

---

## In volta-auth-proxy

**In volta-auth-proxy:** Every login attempt, every page load, and every token check happens over HTTP. When you type your password and click "Sign In," your browser sends an HTTP POST request to the volta server, which checks your credentials and responds.
