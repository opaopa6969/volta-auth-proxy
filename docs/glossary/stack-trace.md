# Stack Trace

[日本語版はこちら](stack-trace.ja.md)

---

## What is it?

A stack trace is a list of all the function calls your program was in the middle of when something went wrong -- like a trail of breadcrumbs showing exactly how your code got to the point where it crashed.

Think of it like retracing your steps after getting lost. You left home, turned left on Main Street, went through the park, took the alley behind the bakery, and now you are in an unfamiliar place. The stack trace is the list of turns you took. Without it, you are just "lost somewhere" with no idea how you got there or how to get back.

---

## How to read one

Here is a simplified stack trace from a Java application:

```
java.lang.NullPointerException: Session not found
    at com.volta.auth.SessionService.validateSession(SessionService.java:47)
    at com.volta.auth.ForwardAuthHandler.verify(ForwardAuthHandler.java:23)
    at com.volta.auth.Router.handle(Router.java:15)
    at io.javalin.Javalin.serve(Javalin.java:102)
```

Read it from top to bottom:

1. **The error:** `NullPointerException: Session not found` -- what went wrong.
2. **Where it happened:** `SessionService.java`, line 47 -- the exact spot.
3. **Who called it:** `ForwardAuthHandler.java`, line 23 -- the caller.
4. **Who called that:** `Router.java`, line 15 -- the caller's caller.
5. **The starting point:** `Javalin.java`, line 102 -- where the whole chain began.

Each line is a "frame" in the stack. The top is where the error occurred. The bottom is where the request entered the system. Everything in between is the path the code took.

---

## Why readable stack traces matter

Not all stack traces are created equal. Some are helpful; some are cryptic walls of noise.

**A good stack trace (single process, your code):**

```
NullPointerException: Session not found
    at SessionService.validateSession(SessionService.java:47)
    at ForwardAuthHandler.verify(ForwardAuthHandler.java:23)
```

You see the error, you see your file, you see the line number. You open the file, look at line 47, and you find the bug. This takes seconds.

**A bad stack trace (multiple services, framework magic):**

```
ERROR: upstream connect error or disconnect/reset before headers
    ... 47 lines of Envoy proxy internals ...
    ... 23 lines of service mesh configuration ...
    ... no reference to your code at all ...
```

The error happened somewhere across a network boundary. The stack trace shows you the proxy's internals, not your code. You have no idea which service failed, which line of your code triggered it, or even what the actual error was. This takes hours.

---

## volta's philosophy: "choose the hell where you can read the stack trace"

volta-auth-proxy runs as a single Java process. When something goes wrong, the stack trace shows you exactly what happened, in your code, with line numbers. There is no "the error is somewhere in the service mesh" mystery.

This is a deliberate design choice. volta could have been built as microservices (one service for sessions, one for JWT, one for OIDC). Each service would have its own stack traces, but errors that cross service boundaries would produce useless traces like "connection refused" or "timeout after 5000ms."

By keeping everything in one process, volta guarantees that every error produces a readable stack trace that points to the exact line of code where the problem is. When you are debugging an auth issue at 3 AM, this is the difference between a 5-minute fix and a 5-hour investigation.

---

## In volta-auth-proxy

volta runs as a single Java process specifically so that every error -- from OIDC callback failures to session validation bugs -- produces a complete, readable stack trace that points directly to the line of code where the problem occurred.

---

## Further reading

- [debugging.md](debugging.md) -- Why debugging in one place matters.
- [single-process.md](single-process.md) -- Why volta is a single process.
- [microservice.md](microservice.md) -- The alternative architecture volta chose not to use.
- [java.md](java.md) -- The language that produces volta's stack traces.
