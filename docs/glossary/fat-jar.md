# Fat JAR

[日本語版はこちら](fat-jar.ja.md)

---

## What is it?

A fat JAR (also called an uber JAR) is a single Java archive file (.jar) that contains your application code AND all of its dependencies bundled together, so you can run the entire application with one file.

Think of it like a lunchbox versus a grocery list. A normal JAR is like a grocery list: it tells you what ingredients you need, but you have to go find them yourself. A fat JAR is like a packed lunchbox: everything you need is already inside. Open it and eat. No shopping required.

In technical terms: a regular JAR contains only your code. A fat JAR contains your code plus every library your code depends on (web server, database driver, JSON parser, template engine -- everything), all packed into a single `.jar` file.

---

## Why does it matter?

Deploying Java applications used to be painful. You needed:

1. An application server (Tomcat, JBoss, WildFly)
2. Your application WAR file deployed into it
3. All the right library versions installed
4. Correct classpath configuration

This led to the infamous "it works on my machine" problem. Different environments had different library versions, different application server configs, and things would break in production that worked in development.

Fat JARs solve this by making deployment simple:

```
  Traditional deployment:              Fat JAR deployment:
  ┌─────────────────────┐              ┌─────────────────────┐
  │ Install Java         │              │ Install Java         │
  │ Install Tomcat       │              │ java -jar app.jar    │
  │ Configure Tomcat     │              │                      │
  │ Deploy WAR file      │              │ That's it.           │
  │ Configure classpath  │              └─────────────────────┘
  │ Pray it works        │
  └─────────────────────┘
```

### How a fat JAR is created

Build tools like Maven or Gradle can create fat JARs using plugins:

- **Maven Shade Plugin** -- Repackages all dependencies into a single JAR, "shading" (renaming) packages to avoid conflicts
- **Maven Assembly Plugin** -- Combines project output with dependencies into a single archive
- **Spring Boot Maven Plugin** -- Creates an executable JAR with a special class loader

Example with Maven Shade Plugin:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.5.0</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="...ManifestResourceTransformer">
            <mainClass>com.volta.authproxy.Main</mainClass>
          </transformer>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

After running `mvn package`, you get a single file like `volta-auth-proxy-0.1.0.jar` that contains everything.

---

## How volta deploys

volta-auth-proxy is a lightweight Java application built with Javalin (a micro web framework). It runs as a single Java process:

```bash
# Development: run directly with Maven
mvn exec:java -Dexec.mainClass=com.volta.authproxy.Main

# Production: run the packaged JAR
java -jar volta-auth-proxy.jar
```

The application includes everything it needs:

| Component | Role | Included in the JAR? |
|-----------|------|---------------------|
| Javalin | Web server + routing | Yes |
| [HikariCP](hikaricp.md) | Database connection pool | Yes |
| [Flyway](flyway.md) | Database migrations | Yes |
| [jte](jte.md) | Template engine | Yes |
| nimbus-jose-jwt | JWT signing/verification | Yes |
| PostgreSQL driver | Database communication | Yes |
| Jackson | JSON parsing | Yes |

No application server needed. No separate web server. No classpath configuration. Just `java -jar` and the application starts in ~200ms.

### Why this matters for self-hosting

The fat JAR approach makes [self-hosting](self-hosting.md) volta simple:

```
  What you need to run volta:
  ┌──────────────────────────┐
  │ 1. Java 21 (runtime)     │
  │ 2. PostgreSQL database   │
  │ 3. volta-auth-proxy.jar  │
  │ 4. .env + config YAML    │
  └──────────────────────────┘

  What you DON'T need:
  ┌──────────────────────────┐
  │ ✗ Tomcat                 │
  │ ✗ Application server     │
  │ ✗ Library management     │
  │ ✗ Complex deployment     │
  └──────────────────────────┘
```

In a Docker container, this becomes even simpler -- the Java runtime is baked into the image, so you just need `docker run volta-auth-proxy`.

---

## Fat JAR versus thin JAR

| | Fat JAR | Thin JAR |
|---|---------|----------|
| **Contains** | Your code + all dependencies | Your code only |
| **File size** | Larger (tens of MB) | Smaller (KB to MB) |
| **Deployment** | Single file, self-contained | Needs dependencies available separately |
| **Docker image** | Simpler Dockerfile | Needs dependency layer management |
| **Startup** | Potentially slower (unpacking) | Faster (already unpacked) |
| **Best for** | Microservices, simple deployment | Large shared environments |

volta uses the fat JAR approach because simplicity of deployment is a core value. When you are self-hosting, fewer moving parts means fewer things that can break.

---

## Further reading

- [self-hosting.md](self-hosting.md) -- Why simple deployment matters for volta's self-hosting model.
- [hikaricp.md](hikaricp.md) -- One of the libraries bundled inside volta's JAR.
- [flyway.md](flyway.md) -- Database migrations that run automatically when the JAR starts.
- [jte.md](jte.md) -- The template engine bundled in the JAR.
