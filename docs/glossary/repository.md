# Repository

[日本語版はこちら](repository.ja.md)

---

## What is it?

A repository (often shortened to "repo") is a storage location for code or software artifacts. There are two common types: a **[git](git.md) repository** stores source code with its full version history, and a **package repository** (like Maven Central) stores compiled [libraries](library.md) that your project depends on.

Think of it like a filing cabinet with a time machine. A git repository stores every version of every file, so you can go back to any point in the past. Imagine a filing cabinet where you can pull out not just the current version of a document, but the version from last Tuesday, or the version from six months ago. A package repository is more like a warehouse -- it stores finished goods (compiled libraries) that anyone can order (download).

When someone says "clone the volta repo," they mean downloading the git repository that contains volta-auth-proxy's source code. When Maven downloads dependencies, it is pulling from a package repository.

---

## Why does it matter?

Repositories are fundamental to software development:

- **Collaboration** -- Multiple developers work on the same codebase through a shared git repository
- **Version history** -- Every change is recorded. If something breaks, you can see exactly what changed and when.
- **Dependency management** -- [Maven](maven.md) downloads [libraries](library.md) from Maven Central, a package repository. Without it, you would manually download JAR files from random websites.
- **Backup** -- A remote git repository (on GitHub, GitLab, etc.) is a backup of your code. If your laptop dies, the code survives.
- **Deployment** -- The repository is the single source of truth. What is in the repo is what gets [built](build.md) and deployed.

---

## How does it work?

### Git repository

A git repository tracks changes to files over time:

```
  ┌────────────────────────────────────────────────────────────┐
  │                  Git Repository                             │
  │                                                             │
  │  Commit history:                                            │
  │                                                             │
  │  abc1234  ← HEAD (latest)                                  │
  │  │  "Add rate limiting to login endpoint"                  │
  │  │  changed: src/main/java/AuthMiddleware.java             │
  │  │                                                          │
  │  def5678                                                    │
  │  │  "Fix JWT expiry check"                                 │
  │  │  changed: src/main/java/JwtService.java                 │
  │  │                                                          │
  │  ghi9012                                                    │
  │  │  "Initial commit"                                       │
  │  │  added: all files                                       │
  │  ▼                                                          │
  └────────────────────────────────────────────────────────────┘
```

### Local vs. remote repository

```
  Your machine                          GitHub/GitLab
  ┌──────────────────┐                  ┌──────────────────┐
  │  Local repo       │                  │  Remote repo      │
  │  (full copy)      │   git push ───> │  (full copy)      │
  │                   │   <─── git pull  │                   │
  │  .git/ directory  │                  │  Shared with team │
  │  working files    │                  │  CI/CD reads from │
  └──────────────────┘                  └──────────────────┘

  Both contain the FULL history.
  Either can work offline.
```

### Package repository (Maven Central)

```
  pom.xml says:                     Maven Central
  need HikariCP 5.1.0              ┌────────────────────┐
           │                        │ com/zaxxer/         │
           │   mvn compile          │   HikariCP/         │
           └───────────────────────>│     5.1.0/          │
                                    │       HikariCP.jar  │
           <───────────────────────│       HikariCP.pom  │
           downloaded to            │       checksums     │
           ~/.m2/repository/        └────────────────────┘
```

### Repository structure of volta-auth-proxy

```
  volta-auth-proxy/              (git repository root)
  ├── .git/                      (git metadata -- DO NOT touch)
  ├── pom.xml                    (Maven build config + dependencies)
  ├── volta-config.yaml          (runtime configuration)
  ├── Dockerfile                 (container build instructions)
  ├── docker-compose.yml         (local dev environment)
  ├── src/
  │   ├── main/
  │   │   ├── java/              (Java source code)
  │   │   │   └── dev/volta/     (package structure)
  │   │   │       ├── Main.java
  │   │   │       ├── JwtService.java
  │   │   │       └── ...
  │   │   ├── jte/               (jte templates)
  │   │   └── resources/         (static files, SQL migrations)
  │   └── test/
  │       └── java/              (test source code)
  ├── docs/                      (documentation)
  │   └── glossary/              (you are reading this)
  └── target/                    (build output -- NOT in git)
```

### What is NOT in the repository

| Excluded | Why | How excluded |
|----------|-----|-------------|
| `target/` | Build output, regenerated from source | `.gitignore` |
| `.env` | Contains secrets (passwords, API keys) | `.gitignore` |
| `node_modules/` | Downloaded dependencies (if any) | `.gitignore` |
| `~/.m2/repository/` | Local Maven cache | Not in project dir |

---

## How does volta-auth-proxy use it?

### Git repository for source code

volta-auth-proxy's source code lives in a git repository. Developers [clone](clone.md) it to work locally:

```bash
git clone https://github.com/your-org/volta-auth-proxy.git
cd volta-auth-proxy
mvn package
java -jar target/volta-auth-proxy.jar
```

### Maven repositories for dependencies

volta's pom.xml declares dependencies. Maven resolves them from repositories:

```
  Resolution order:
  1. Check local cache (~/.m2/repository/)
  2. If not found, download from Maven Central (https://repo.maven.apache.org)
  3. Cache locally for future builds
```

### Local Maven repository cache

```
  ~/.m2/repository/
  ├── com/zaxxer/HikariCP/5.1.0/
  │   ├── HikariCP-5.1.0.jar
  │   └── HikariCP-5.1.0.pom
  ├── com/github/ben-manes/caffeine/caffeine/3.1.8/
  │   ├── caffeine-3.1.8.jar
  │   └── caffeine-3.1.8.pom
  └── ...
```

This cache means subsequent builds do not need network access. The first build downloads everything; future builds use the cache.

---

## Common mistakes and attacks

### Mistake 1: Committing secrets to the repository

Once a secret (API key, database password) is committed to git, it is in the history forever -- even if you delete the file in a later commit. Use [environment variables](environment-variable.md) for secrets, never commit them.

```bash
# BAD: password in code, committed to repo
String dbPassword = "s3cret123";

# GOOD: password from environment, not in repo
String dbPassword = System.getenv("DATABASE_PASSWORD");
```

### Mistake 2: Committing build artifacts

The `target/` directory should never be committed. It contains generated files that are rebuilt from source. Including it bloats the repository and causes merge conflicts.

### Attack 1: Dependency confusion

An attacker publishes a malicious package to a public repository with the same name as an internal package. If Maven checks the public repository before the private one, it downloads the attacker's package. Defense: configure Maven to check private repositories first, and use explicit repository declarations in pom.xml.

### Mistake 3: Not using .gitignore

Without a proper `.gitignore`, developers accidentally commit IDE files (`.idea/`, `.vscode/`), OS files (`.DS_Store`), and secrets. volta's `.gitignore` is set up to exclude these.

### Attack 2: Repository compromise

If an attacker gains write access to the git repository, they can inject malicious code that gets built and deployed. Defense: require code review (pull requests), enable branch protection on main, and use signed commits.

---

## Further reading

- [git.md](git.md) -- The version control system that manages the repository.
- [clone.md](clone.md) -- How to download a copy of a repository.
- [maven.md](maven.md) -- The build tool that reads from package repositories.
- [library.md](library.md) -- What is stored in package repositories.
- [environment-variable.md](environment-variable.md) -- How to keep secrets out of the repository.
- [docker.md](docker.md) -- Container images are stored in container registries (another type of repository).
