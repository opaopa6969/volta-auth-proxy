# FreeMarker

[日本語版はこちら](freemarker.ja.md)

---

## What is it?

**FreeMarker is a tool that fills in blanks in a template -- like mail merge in Microsoft Word.**

It's OK not to know this! FreeMarker is a niche tool, and most people have never heard of it. Let's start with something familiar.

---

## A real-world analogy

Imagine you work at a school and need to send the same letter to 200 parents, but each letter needs the child's name and grade:

```
Dear ______,

Your child ______ in class ______ has been doing great this semester!

Sincerely,
The School
```

You wouldn't write 200 letters by hand. Instead, you'd use **mail merge**: you create ONE template with blanks, then a computer fills in the blanks from a list of names and classes.

**FreeMarker does exactly this, but for web pages instead of letters.**

---

## What does it look like?

Here's a tiny FreeMarker template for a login page:

```
Welcome back, ${username}!
You have ${messageCount} new messages.
```

If the username is "Tanaka" and they have 3 messages, FreeMarker produces:

```
Welcome back, Tanaka!
You have 3 new messages.
```

The `${...}` parts are the "blanks" that get filled in automatically.

---

## Why does Keycloak use FreeMarker?

Keycloak (the login system that volta-auth-proxy replaces) uses FreeMarker to build its login pages, error pages, and email templates. The idea is: you write a page template once, and Keycloak fills in the user's name, error messages, and other details.

Sounds reasonable, right? The problem is...

---

## Why is FreeMarker painful?

Imagine the school letter template, but now:

- The template is 500 lines long
- The blanks have confusing names like `${realm.displayNameHtml!''}`
- There are 47 hidden rules about which blanks are available on which pages
- If you make a tiny mistake, the whole page breaks with a cryptic error
- There's no good way to preview what the letter will look like before sending it

```
  A simple Keycloak FreeMarker template:

  <#if realm.password && social.providers??>     <-- What does this mean?!
    <div id="${properties.kcFormSocialAccountSectionClass!}">
      <#list social.providers as p>
        <a href="${p.loginUrl}">${p.displayName!}</a>
      </#list>
    </div>
  </#if>
```

For a professional developer, this is annoying. For someone who just wants to change the color of a button on the login page, it's a nightmare.

---

## What does volta use instead?

volta-auth-proxy uses **jte** (Java Template Engine), which is simpler, faster, and catches mistakes before the page is shown to users. Think of it as a smarter mail merge that tells you "Hey, you misspelled the child's name field!" before you send 200 letters.

---

## In volta-auth-proxy

**In volta-auth-proxy:** FreeMarker is NOT used at all. volta replaced Keycloak's FreeMarker templates with jte templates, making login pages much easier to customize and much harder to break.
