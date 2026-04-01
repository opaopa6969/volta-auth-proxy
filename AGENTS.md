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
