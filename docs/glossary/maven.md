# Maven

[日本語版はこちら](maven.ja.md)

---

## What is it?

**Maven is a recipe book and ingredient fetcher for Java projects -- it knows what ingredients (libraries) your project needs and automatically goes to the store to get them.**

It's OK not to know this! Maven is a tool that Java developers use. If you're not writing Java code, you'll rarely encounter it.

---

## A real-world analogy

Imagine you want to bake a fancy cake. You have two choices:

**Without Maven:**
```
  1. Read the recipe
  2. Drive to Store A for flour
  3. Drive to Store B for special chocolate (they didn't have it at Store A)
  4. Drive to Store C for vanilla extract
  5. Realize the chocolate needs a specific kind of butter
  6. Drive back to Store A for butter
  7. Finally start baking
  8. Realize you got the wrong version of the flour
  9. Start over...
```

**With Maven:**
```
  1. Write down what you need in a list (pom.xml)
  2. Maven goes to all the stores for you
  3. Maven gets the right versions of everything
  4. Maven even gets the ingredients that your ingredients need
  5. Start baking!
```

---

## The pom.xml file

The `pom.xml` file is Maven's recipe card. It lists everything the project needs:

```xml
<!-- This is a simplified pom.xml -->
<project>
  <name>volta-auth-proxy</name>

  <!-- Ingredients (dependencies) -->
  <dependencies>
    <!-- We need a web server library -->
    <dependency>
      <groupId>io.javalin</groupId>
      <artifactId>javalin</artifactId>
      <version>6.1.0</version>
    </dependency>

    <!-- We need a database library -->
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <version>42.7.0</version>
    </dependency>
  </dependencies>
</project>
```

Don't worry about the details! The point is: it's a list of "ingredients" the project needs, and Maven fetches them all automatically.

---

## What are "dependencies"?

A **dependency** is just an ingredient -- a piece of software that your project needs to work. Just like a cake needs flour, eggs, and sugar, a Java project needs libraries for things like:

- Talking to a database
- Running a web server
- Handling security

And just like flour needs wheat (which needs water and soil), dependencies sometimes need OTHER dependencies. Maven handles all of this automatically.

---

## Maven vs Gradle

You might hear about "Gradle" -- it's another tool that does the same job as Maven. Think of it like:

```
  Maven  = A reliable recipe book with a specific format
  Gradle = A more flexible recipe book with a different format
```

Both work fine. volta uses Maven because it's simpler and more widely understood.

---

## In volta-auth-proxy

**In volta-auth-proxy:** Maven manages all of the project's Java dependencies -- it automatically downloads the libraries volta needs (web server, database driver, security tools, template engine) and builds the project into a runnable application.
