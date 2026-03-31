# Git

[日本語版はこちら](git.ja.md)

---

## What is it?

**Git is a time machine for files -- it lets you save your work at any point and go back to any previous save whenever you want.**

It's OK not to know this! Git is a tool that developers use to keep track of changes to their code. Think of it as the ultimate "undo" button.

---

## A real-world analogy

Imagine you're writing a novel:

```
  Monday:    You write Chapter 1.          [SAVE POINT 1]
  Tuesday:   You write Chapter 2.          [SAVE POINT 2]
  Wednesday: You rewrite Chapter 2 and     [SAVE POINT 3]
             it's much better.
  Thursday:  You accidentally delete        Oh no!
             everything and write
             something terrible.

  With Git:  "Take me back to Wednesday's version, please."
             Poof! Everything is restored.
```

Without Git, you'd have to remember every change you ever made, or save files like `novel_v1.doc`, `novel_v2.doc`, `novel_v2_FINAL.doc`, `novel_v2_FINAL_REALLY_FINAL.doc`...

We've all been there!

---

## Key concepts (no jargon!)

### Save points = "Commits"

A **commit** is like pressing "Save" in a video game. It captures exactly what everything looks like at that moment. You add a short note explaining what changed:

```
  Save Point 1: "Added login page"
  Save Point 2: "Fixed the bug where passwords weren't checked"
  Save Point 3: "Made the error messages friendlier"
```

### Sharing your work = "Push"

When you **push**, you send your save points to a shared place (like GitHub) where your teammates can see them. It's like putting your novel chapters in a shared folder.

```
  Your computer  ----push---->  Shared place (GitHub)
                                  (teammates can see it)
```

### Getting others' work = "Pull"

When you **pull**, you download your teammates' latest save points to your computer.

```
  Your computer  <----pull----  Shared place (GitHub)
                                  (get teammates' changes)
```

---

## Why developers use Git

```
  Without Git:                       With Git:

  report_final.doc                   One file, with full history
  report_final_v2.doc                of every change ever made.
  report_final_v2_FIXED.doc          Go back to any point in time.
  report_DONT_DELETE.doc              See who changed what and when.
  report_final_ACTUAL.doc            Multiple people can work at
                                     the same time without chaos.
```

---

## Git is NOT GitHub

A common confusion:

```
  Git    = The tool that tracks changes (runs on your computer)
  GitHub = A website where you store and share Git projects

  Git is the camera. GitHub is the photo album you share online.
```

---

## In volta-auth-proxy

**In volta-auth-proxy:** All of volta's source code is tracked with Git, so every change ever made is recorded, reviewable, and reversible -- if something goes wrong, we can always go back to a working version.
