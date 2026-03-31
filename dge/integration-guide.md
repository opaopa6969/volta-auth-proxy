# DGE Integration Guide

## あなたのプロジェクトに開発 workflow がある場合

DGE は `dge/` フォルダ内で完結します。
プロジェクトの `docs/` や既存ファイルを直接変更しません。

## DGE の出力を既存 workflow に組み込む方法

1. DGE session を実行 → Gap を発見
2. 「実装する」→ `dge/specs/` に Spec が生成される（status: draft）
3. Spec をレビューする（status: reviewed に更新）
4. レビュー済みの Spec を、あなたの workflow に従って正式な `docs/` に転記する
5. 転記したら `dge/specs/` のファイルの status を `migrated` に変更し、`migrated_to` に正本パスを記入

## 既存 workflow の phase との対応

| あなたの phase | DGE のアクション |
|---|---|
| PLAN / DESIGN | DGE session を実行。Gap 発見 → Spec 生成 |
| IMPLEMENT | `dge/specs/` の reviewed Spec を元に実装 |
| VERIFY | DGE の Gap が全て対応されたか確認 |

## Source of Truth ルール

**DGE の Spec と既存 docs が矛盾する場合、既存 docs が Source of Truth です。**

DGE の Spec は「提案」であり、プロジェクトの正式な仕様ではありません。
DGE が生成した API 定義と、あなたの `api-surface.md` が異なる場合、`api-surface.md` が正しいです。

## DGE が触るもの / 触らないもの

| 触る | 触らない |
|------|---------|
| `dge/sessions/` | `docs/` |
| `dge/specs/` | `.claude/rules/` |
| `dge/custom/` | 既存の spec ファイル |
| `.claude/skills/dge-*.md` | CLAUDE.md（初回提案のみ） |

## Spec ライフサイクル

```
draft       DGE が自動生成した状態。未レビュー。
            → 実装しない。レビューが必要。

reviewed    人間がレビュー済み。実装可能。
            → あなたの workflow に従って実装する。

migrated    プロジェクトの正式な docs/ に転記済み。
            → このファイルは参照用。正本は migrated_to を参照。
```

## workflow がないプロジェクトの場合

`dge/specs/` の reviewed Spec をそのまま実装の根拠として使えます。
正式な `docs/` への転記は必須ではありません。
