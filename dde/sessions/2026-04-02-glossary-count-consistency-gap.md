# DDE Gap Session: Glossary Count Consistency (DRE Signal)

**Date:** 2026-04-02  
**Target docs:** `docs/glossary/README.md`, `docs/target-audience.ja.md`, `README.ja.md`  
**Purpose:** DRE/DDE レビューで検出された「用語集記事数」の不整合を通知

---

## Summary

用語集の総記事数に関する表記が文書間で一致していません。

- `docs/glossary/README.md` は **86 articles** と記載
- `docs/target-audience.ja.md` は **283 記事** と記載（複数箇所）
- 既存 DDE セッション `dde/sessions/2026-04-01-dde-session.md` では **~293 EN + ~292 JA** と記録
- 実ファイル数（`docs/glossary/`）は現時点で **EN 293 / JA 292 / 合計 585 ファイル**

この不整合は、読者とメンテナ双方に「どれが正しい基準値か」を不明確にします。

---

## Evidence

### Stated counts

- `docs/glossary/README.md:11`
  - `**86 articles** covering authentication, security, and architecture concepts`
- `docs/target-audience.ja.md:35`
  - `283 記事の用語集`
- `docs/target-audience.ja.md:74`
  - `DSL + 283 記事 + DGE 設計`
- `docs/target-audience.ja.md:116`
  - `283 記事、3 レベル`
- `docs/target-audience.ja.md:161`
  - `283 記事の用語集だけで、`
- `dde/sessions/2026-04-01-dde-session.md:125`
  - `Post-session: ~293 articles (EN) + ~292 articles (JA)`

### Actual file counts (2026-04-02)

- `find docs/glossary -maxdepth 1 -type f -name '*.md' ! -name '*.ja.md' | wc -l` -> **293**
- `find docs/glossary -maxdepth 1 -type f -name '*.ja.md' | wc -l` -> **292**
- `find docs/glossary -maxdepth 1 -type f -name '*.md' | wc -l` -> **585**

---

## Gap Classification (DDE)

### A. Terms
- 新規用語不足は今回の主因ではない（既存記事数の告知不整合が主因）。

### B. Diagrams
- 図の不足ではない。

### C. Reader Gaps (Critical)
- 「用語集記事数」が文書ごとに異なるため、読者がプロジェクト規模を誤認する。
- DRE が文書の鮮度/整合性を判断する際の基準がぶれる。

---

## Recommended Action

1. **単一の正規値ソースを定義**する（例: `docs/glossary/README.md` を canonical に固定）。
2. `docs/target-audience.ja.md` の「283 記事」表記を canonical 値へ更新。
3. 可能なら CI で「記事数ハードコード文言」を検出（`86 articles`, `283 記事` など）し、更新漏れを防止。

---

## Proposed Canonical Wording (example)

- JA: `用語集（EN 293 / JA 292、合計 585 ファイル。随時更新）`
- EN: `Glossary (EN 293 / JA 292, 585 files total; updated continuously)`

