# Build

[日本語版はこちら](build.ja.md)

---

## What is it?

A build is the complete process of turning source code into a runnable application. It includes [compiling](compile.md) the code, running tests, resolving dependencies, and packaging everything together. For volta-auth-proxy, the build takes `.java` files, `.jte` templates, and a `pom.xml` dependency list and produces a single executable [fat JAR](fat-jar.md) file.

Think of it like assembling a piece of furniture from IKEA. You have the raw materials (wood panels = source code), screws and bolts (dependencies = [libraries](library.md)), and assembly instructions (build configuration = pom.xml). The build process follows the instructions to cut, drill, fasten, and test (does the drawer open smoothly?) until you have a finished, usable product. If a piece does not fit (compile error) or the drawer jams (test failure), the process stops.

The build command for volta is `mvn package`, which runs the full pipeline from source code to deployable artifact.

---

## Why does it matter?

Without a build process:

- You would manually [compile](compile.md) dozens of files in the right order
- You would manually download and place [library](library.md) JAR files
- You would forget to run tests before deploying
- Different developers would produce different outputs from the same code
- Deployment would be error-prone and unrepeatable

A build tool like [Maven](maven.md) makes the process **reproducible** -- the same source code always produces the same output, on any machine. This is critical for production deployment.

---

## How does it work?

### The build pipeline

```
  Source Code                   Build Tool (Maven)              Output
  ───────────                   ──────────────────              ──────

  src/main/java/*.java    ──>   1. Resolve dependencies   ──>  target/
  src/main/jte/*.jte      ──>   2. Compile Java source         volta-auth-proxy.jar
  src/main/resources/*    ──>   3. Compile jte templates        (single fat JAR,
  src/test/java/*.java    ──>   4. Run unit tests               ~30MB, contains
  pom.xml                 ──>   5. Run integration tests        everything needed
                                6. Package into fat JAR         to run)
```

### Maven build phases

Maven organizes the build into sequential phases. Each phase includes all previous phases:

```
  mvn compile     ──>  1. compile (Java + jte templates)
  mvn test        ──>  1. compile → 2. test
  mvn package     ──>  1. compile → 2. test → 3. package (create JAR)
  mvn verify      ──>  1. compile → 2. test → 3. package → 4. verify
  mvn install     ──>  1. compile → 2. test → 3. package → 4. verify → 5. install to local repo
```

For volta, `mvn package` is the standard build command. It produces the deployable fat JAR.

### What happens during each phase

```
  Phase 1: compile
  ┌─────────────────────────────────────────────────┐
  │  javac compiles .java files to .class files      │
  │  jte plugin compiles .jte templates to .class    │
  │  Resources copied to target/classes/             │
  │                                                   │
  │  If ANY file has a type error → BUILD FAILS       │
  └─────────────────────────────────────────────────┘
           │
           ▼
  Phase 2: test
  ┌─────────────────────────────────────────────────┐
  │  JUnit runs all test classes in src/test/java/   │
  │  Tests verify: auth logic, JWT creation,         │
  │  role checks, session handling, etc.             │
  │                                                   │
  │  If ANY test fails → BUILD FAILS                  │
  └─────────────────────────────────────────────────┘
           │
           ▼
  Phase 3: package
  ┌─────────────────────────────────────────────────┐
  │  maven-shade-plugin creates the fat JAR:         │
  │  1. Takes all compiled .class files              │
  │  2. Unpacks all dependency JARs                  │
  │  3. Merges everything into one JAR               │
  │  4. Sets Main-Class in MANIFEST.MF               │
  │                                                   │
  │  Output: target/volta-auth-proxy.jar             │
  └─────────────────────────────────────────────────┘
```

### Build artifacts

| Artifact | Location | Purpose |
|----------|----------|---------|
| Compiled classes | `target/classes/` | Intermediate bytecode |
| Test results | `target/surefire-reports/` | Test pass/fail records |
| Fat JAR | `target/volta-auth-proxy.jar` | The deployable application |
| Dependency tree | `mvn dependency:tree` output | Audit dependencies |

### Reproducible builds

The same source code + same pom.xml + same JDK version = same output JAR. This is why:

- pom.xml pins exact dependency versions (`5.1.0`, not `5.+`)
- The JDK version is specified (Java 21)
- No external state affects the build output

---

## How does volta-auth-proxy use it?

### The standard volta build

```bash
# Full build: compile + test + package
mvn package

# Quick compile only (no tests, no packaging)
mvn compile

# Run tests without packaging
mvn test

# Skip tests (use sparingly -- tests exist for a reason)
mvn package -DskipTests
```

### Running the built application

After building, volta runs as a single [process](process.md):

```bash
# Build
mvn package

# Run
java -jar target/volta-auth-proxy.jar
```

That single command starts the entire application: HTTP server, database connections, session management, JWT services, everything.

### Build in Docker

volta's [Docker](docker.md) build uses a multi-stage Dockerfile:

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
COPY pom.xml .
COPY src/ src/
RUN mvn package -DskipTests

# Stage 2: Run (smaller image)
FROM eclipse-temurin:21-jre
COPY --from=build target/volta-auth-proxy.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

Stage 1 has Maven and the full JDK (large image, ~500MB). Stage 2 has only the JRE and the JAR (small image, ~200MB). The build tools are not included in the production image.

### Build environment requirements

| Requirement | Version | Why |
|-------------|---------|-----|
| JDK | 21+ | Java language features (records, pattern matching) |
| Maven | 3.9+ | Build tool |
| PostgreSQL | -- | Not needed for build, only for running |
| Docker | -- | Not needed for build, only for containerization |

### Dependency resolution during build

Maven downloads dependencies from the [Maven Central repository](repository.md) on the first build:

```
  First build:
  ┌─────────────────────────────────────────┐
  │  pom.xml says: need HikariCP 5.1.0      │
  │  Maven checks: ~/.m2/repository/        │
  │  Not found → download from Maven Central │
  │  Cache locally for future builds         │
  └─────────────────────────────────────────┘

  Subsequent builds:
  ┌─────────────────────────────────────────┐
  │  pom.xml says: need HikariCP 5.1.0      │
  │  Maven checks: ~/.m2/repository/        │
  │  Found → use cached version              │
  │  No network needed                       │
  └─────────────────────────────────────────┘
```

---

## Common mistakes and attacks

### Mistake 1: Building without tests

```bash
mvn package -DskipTests  # Tempting but dangerous
```

Skipping tests saves a few seconds but risks deploying broken code. A test that catches a security bug (like wrong role checking) is worth the wait.

### Mistake 2: Inconsistent build environments

"It works on my machine" happens when developers use different JDK versions, different OS configurations, or different Maven versions. volta mitigates this by:

- Specifying Java 21 in pom.xml
- Providing a Dockerfile for reproducible builds
- Pinning exact dependency versions

### Attack 1: Build-time dependency poisoning

If an attacker compromises a dependency in Maven Central, every build that downloads it gets malicious code. Defenses:

- Maven verifies checksums on downloaded artifacts
- volta pins exact versions (not version ranges)
- Regular dependency audits with `mvn dependency:tree`

### Mistake 3: Committing build artifacts

The `target/` directory should never be in [git](git.md). It contains generated files that can be rebuilt from source. volta's `.gitignore` excludes `target/`.

### Mistake 4: Not cleaning before release builds

```bash
mvn clean package  # Deletes target/ first, then builds fresh
```

Without `clean`, stale compiled classes from deleted source files might remain in `target/` and end up in the JAR.

---

## Further reading

- [compile.md](compile.md) -- The compilation step within the build.
- [maven.md](maven.md) -- The build tool volta uses.
- [fat-jar.md](fat-jar.md) -- The output format of the build.
- [docker.md](docker.md) -- Containerizing the build output.
- [library.md](library.md) -- Dependencies resolved during the build.
- [jte.md](jte.md) -- Templates compiled during the build.
- [flyway.md](flyway.md) -- Database migrations that run at application start (not build time).
- [environment-variable.md](environment-variable.md) -- Configuration that is NOT part of the build.
