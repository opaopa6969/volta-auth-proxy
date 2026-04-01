# Clone

[日本語版はこちら](clone.ja.md)

---

## What is it?

`git clone` is a command that downloads a complete copy of a [git](git.md) [repository](repository.md) to your local machine. It copies every file, every branch, and every commit in the project's history. After cloning, you have a fully independent copy that you can work with offline.

Think of it like photocopying an entire filing cabinet. You take every folder, every document, and every revision note, and make a complete copy for your own desk. The original filing cabinet still exists at the office (the remote repository), and your copy is fully functional -- you can read, edit, add, and reorganize files. When you want to share your changes, you push them back to the original.

Cloning is the first step to working with volta-auth-proxy. Before you can [build](build.md), run, or modify the code, you need a local copy.

---

## Why does it matter?

Cloning matters because:

- **It is the entry point** -- The first thing any developer does with a new project is clone it
- **Full history** -- You get every commit, so you can understand how the code evolved, who changed what, and when
- **Offline work** -- After cloning, you do not need internet access to browse code, read history, or make changes
- **Independence** -- Your clone is yours. You can experiment, break things, and reset without affecting anyone else
- **Collaboration** -- Cloning is how multiple developers work on the same project. Everyone has their own copy, and [git](git.md) handles merging changes

---

## How does it work?

### The clone command

```bash
git clone https://github.com/your-org/volta-auth-proxy.git
```

This creates a directory called `volta-auth-proxy/` containing the full repository.

### What clone downloads

```
  Remote (GitHub)                    Your machine (after clone)
  ───────────────                    ──────────────────────────
  ┌─────────────────┐               ┌─────────────────┐
  │ All files        │               │ All files        │  (exact copy)
  │ All branches     │  git clone    │ All branches     │
  │ All commits      │ ─────────>   │ All commits      │
  │ All tags         │               │ All tags         │
  │                  │               │ .git/ directory  │  (metadata)
  └─────────────────┘               └─────────────────┘
```

### Clone vs. download ZIP

GitHub offers a "Download ZIP" button. This is NOT the same as cloning:

| Feature | git clone | Download ZIP |
|---------|-----------|-------------|
| Full history | Yes (every commit) | No (only latest files) |
| Branches | All branches | Only default branch |
| Git metadata (.git/) | Yes | No |
| Can push changes | Yes | No |
| Can pull updates | Yes | No |
| Can see who changed what | Yes | No |

Always use `git clone`, never download a ZIP.

### The .git directory

After cloning, the repository has a hidden `.git/` directory. This is where git stores all its metadata:

```
  volta-auth-proxy/
  ├── .git/                    ← git metadata (DO NOT edit manually)
  │   ├── objects/             ← every version of every file
  │   ├── refs/                ← branch and tag pointers
  │   ├── HEAD                 ← current branch
  │   └── config               ← remote URL, settings
  ├── pom.xml                  ← working copy of files
  ├── src/
  └── ...
```

### Clone with SSH vs. HTTPS

```bash
# HTTPS (works immediately, prompts for password)
git clone https://github.com/your-org/volta-auth-proxy.git

# SSH (requires SSH key setup, no password prompts)
git clone git@github.com:your-org/volta-auth-proxy.git
```

SSH is preferred for regular development because it does not require typing credentials.

### Clone into a specific directory

```bash
# Default: creates volta-auth-proxy/ directory
git clone https://github.com/your-org/volta-auth-proxy.git

# Custom directory name
git clone https://github.com/your-org/volta-auth-proxy.git my-volta
```

### After cloning: the typical workflow

```
  1. Clone the repository
     git clone https://github.com/your-org/volta-auth-proxy.git
     cd volta-auth-proxy

  2. Build the project
     mvn package

  3. Set up environment
     export DATABASE_URL=jdbc:postgresql://localhost:5432/volta
     export GOOGLE_CLIENT_ID=...
     export GOOGLE_CLIENT_SECRET=...

  4. Run the application
     java -jar target/volta-auth-proxy.jar

  5. Make changes, commit, push
     git add -A
     git commit -m "Fix JWT expiry check"
     git push
```

---

## How does volta-auth-proxy use it?

### Getting started with volta

The first step in the volta-auth-proxy README is:

```bash
git clone https://github.com/your-org/volta-auth-proxy.git
cd volta-auth-proxy
```

This gives you the complete source code, including:

```
  After cloning, you have:
  ├── pom.xml                  → Maven build configuration
  ├── volta-config.yaml        → Runtime configuration template
  ├── Dockerfile               → Container build instructions
  ├── docker-compose.yml       → Local dev environment
  ├── src/main/java/           → Java source code
  │   └── dev/volta/
  │       ├── Main.java        → Application entry point
  │       ├── JwtService.java  → JWT creation/verification
  │       └── ...
  ├── src/main/jte/            → jte templates (login, etc.)
  ├── src/main/resources/
  │   └── db/migration/        → Flyway SQL migrations
  ├── src/test/java/           → Test source code
  └── docs/glossary/           → This glossary
```

### Local development with docker-compose

After cloning, you can spin up the entire development environment:

```bash
git clone https://github.com/your-org/volta-auth-proxy.git
cd volta-auth-proxy
docker-compose up -d    # Starts PostgreSQL, etc.
mvn package
java -jar target/volta-auth-proxy.jar
```

The [docker-compose](docker-compose.md) file in the cloned repository defines the local [database](database.md) and any other services needed for development.

### Keeping your clone up to date

After the initial clone, use `git pull` to get the latest changes:

```bash
cd volta-auth-proxy
git pull origin main    # Downloads and merges latest changes

# Then rebuild
mvn package
```

### Cloning for contributing

If you do not have write access to the main repository, the typical contribution flow is:

```
  1. Fork the repository (on GitHub)
  2. Clone YOUR fork
     git clone https://github.com/YOUR-NAME/volta-auth-proxy.git

  3. Make changes, commit, push to YOUR fork
  4. Create a Pull Request from your fork to the main repo
```

---

## Common mistakes and attacks

### Mistake 1: Cloning into the wrong directory

Cloning inside another git repository creates nested repositories, which causes confusion:

```bash
# WRONG: cloning inside another repo
cd ~/my-other-project/    # this is already a git repo
git clone https://github.com/.../volta-auth-proxy.git
# Now you have a repo inside a repo -- confusing!

# RIGHT: clone into a clean directory
cd ~/projects/
git clone https://github.com/.../volta-auth-proxy.git
```

### Mistake 2: Editing files in .git/

The `.git/` directory is managed by git. Manually editing files inside it can corrupt the repository. If you need to change git configuration, use `git config` commands.

### Mistake 3: Not cloning before trying to build

Downloading individual files from GitHub and trying to build them manually will fail. Maven needs the pom.xml, git needs the .git directory, and the project structure must be intact. Always start with `git clone`.

### Mistake 4: Shallow cloning for development

```bash
git clone --depth 1 https://github.com/.../volta-auth-proxy.git
```

Shallow clone downloads only the latest commit, saving bandwidth. But you lose history, cannot switch branches easily, and `git blame` does not work. Use shallow cloning only for CI/CD pipelines, not for development.

### Attack 1: Clone from a malicious repository

If an attacker provides a fake repository URL that looks like the real one, you might clone malicious code. Always verify the repository URL:

```bash
# Verify: is this the real volta repository?
# Check: organization name, repository name, HTTPS certificate
git clone https://github.com/REAL-ORG/volta-auth-proxy.git  # ✓
git clone https://github.com/re4l-org/volta-auth-proxy.git  # ✗ typosquat!
```

---

## Further reading

- [git.md](git.md) -- The version control system that clone is part of.
- [repository.md](repository.md) -- What you are cloning.
- [build.md](build.md) -- The next step after cloning.
- [docker-compose.md](docker-compose.md) -- Setting up the dev environment after cloning.
- [environment-variable.md](environment-variable.md) -- Configuring volta after cloning.
- [maven.md](maven.md) -- Building the project after cloning.
