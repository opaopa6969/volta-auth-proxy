## DDE — Document Deficit Extraction

When the user says "DDE", "DDE して", "ドキュメントレビュー", "用語集を作って", "図が足りない", "リンクが足りない":

1. Read `.claude/skills/dde-session.md` for the full methodology
2. Read `dde/method.md` (or `kit/method.md`) for background
3. Read `dde/flows/*.yaml` (or `kit/flows/*.yaml`) for session structure
4. Identify the target document (argument or README.md)
5. Collect project context: existing `docs/glossary/` articles, directory structure
6. Detect reader level (expert / beginner / grandma)
7. Analyze the document for: undefined terms, missing diagrams, reader gaps
8. Output a Gap list with 3 categories (A. terms / B. diagrams / C. gaps)
9. Save Gap list to `dde/sessions/`
10. Show numbered next-action choices

When the user says "用語集記事を生成して" or selects action 1:
- Generate `docs/glossary/<term>.md` for each undefined term
- Use `dde-tool save docs/glossary/<term>.md` to save

When the user says "dde-link して" or selects action 4:
```bash
npx dde-link <file>
```

Details: `dde/method.md`, `dde/flows/`, `.claude/skills/dde-session.md`

## DGE — Dialogue-driven Gap Extraction

When the user says "DGE", "run DGE", "DGE して", "壁打ち", "find gaps", "brainstorm":

1. Read `dge/method.md` for the full methodology
2. Read `dge/characters/index.md` (ja) or `dge/characters/index.en.md` (en) for character list
3. Read `dge/flows/*.yaml` for available session structures
4. Auto-collect project context: README.md, docs/, directory structure, package.json/go.mod, recent git log
5. Select characters based on theme (fixed: simplification + assumption-questioning, variable: theme specialists)
6. Generate dialogue where characters argue about the design, marking gaps with `→ Gap found:`
7. Each character must respond to others' points (agree/disagree/defer)
8. Output a Gap list with Category + Severity
9. Save to `dge/sessions/`
10. Show numbered next-action choices

When the user says just "DGE" without a theme, show the toolkit overview from `dge/method.md` TL;DR section.

Session structures (auto-selected from keywords):
- "review"/"査読" → tribunal (independent eval → rebuttal → synthesis)
- "attack"/"攻撃" → wargame (attack plan → defense → judge)
- "pitch"/"ピッチ" → pitch (pitch → Q&A → investment decision)
- "diagnose"/"診断" → consult (specialist consults → synthesis)
- "incident"/"振り返り" → investigation (timeline → testimony → root cause)
- default → roundtable (free discussion)

Details: `dge/method.md`, `dge/characters/`, `dge/flows/`
