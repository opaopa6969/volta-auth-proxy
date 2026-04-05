# DRE Incident Report: Workflow Failure in Glossary Count Governance

**Date:** 2026-04-02  
**Category:** Workflow Failure / Documentation Governance  
**Severity:** Medium (trust/consistency impact, high recurrence risk)  
**Scope:** `docs/glossary/README.md`, `docs/target-audience.md`, `docs/target-audience.ja.md`

---

## Incident Summary

用語集記事数の表記が文書間で長期間ズレたまま運用され、
手動レビューでしか検知されませんでした。

- 旧表記が混在: `86` と `283`
- 実体: `EN 293 / JA 292 / 合計 585`（2026-04-02 時点）

これは「単発の編集ミス」ではなく、**ドキュメント数値ガバナンスの欠落**によるワークフロー失敗です。

---

## Why This Is a Workflow Failure

1. **Canonical source 不在**
   - 「どの数字を正」とするかの規約がない。
2. **Hardcoded number 運用**
   - 複数文書で固定数値を直書きし、更新同期が破綻。
3. **CI/チェック不在**
   - 数値整合性を検証する自動チェックがない。
4. **DDE後の反映ループ未定義**
   - DDEセッションで記事数が増えても、公開文書の更新手順が標準化されていない。

---

## Evidence Snapshot

### Previously conflicting statements
- `docs/glossary/README.md` : `86 articles`
- `docs/target-audience.ja.md` : `283 記事`（複数箇所）
- `docs/target-audience.md` : `283 articles`（複数箇所）

### Observed actual counts (2026-04-02)
- EN: `find docs/glossary -maxdepth 1 -type f -name '*.md' ! -name '*.ja.md' | wc -l` => `293`
- JA: `find docs/glossary -maxdepth 1 -type f -name '*.ja.md' | wc -l` => `292`
- Total: `find docs/glossary -maxdepth 1 -type f -name '*.md' | wc -l` => `585`

### Current status
- 公開文書の数値は `585 (EN 293 + JA 292)` へ統一済み。
- ただし、**再発防止の仕組みは未導入**。

---

## Corrective Actions (Immediate)

1. Count表示の canonical 表現を決定し、全公開文書で統一。  
2. 変更時に DDE セッションへ「count update check」結果を必須記録。  
3. release/checklist に「glossary count consistency」を追加。

---

## Preventive Actions (Systemic)

1. **Single Source of Truth 化**
   - 例: `docs/glossary/README.md` 冒頭を正とし、他文書はそこへの参照文言に寄せる。  
2. **自動生成化**
   - `scripts/glossary-count.sh` で EN/JA/Total を算出し、READMEヘッダを更新。  
3. **CI Guard**
   - `86 articles`, `283 記事` などの旧固定値を検出したら CI fail。  
4. **DDE Completion Gate**
   - DDE完了条件に「公開文書の数値整合チェック通過」を追加。

---

## DRE Follow-up Request

次回 DRE 実行時に以下を評価対象へ追加してください。

- [ ] Glossary count single-source policy exists
- [ ] Count sync automation exists
- [ ] CI guard for stale numeric claims exists
- [ ] DDE workflow includes post-update consistency gate

