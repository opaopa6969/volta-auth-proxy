# DGE Session — Documentation Toolkit 構想
- **Date**: 2026-04-01
- **Flow**: brainstorm
- **Theme**: DGE の兄弟プロダクト — ドキュメントの「伝わってないこと」を発見して補完するツールキット

---

## 着想

### volta-auth-proxy で実感した問題

```
1. README に専門用語を書く → 読者が分からない
2. 用語集を手動で作る → 地獄（241 ファイル）
3. 用語をリンク化する → もっと地獄（334 リンク手動）
4. 図がないと伝わらない → mermaid を手書き
5. 読者のレベルが違う → 専門家/初心者/おばあちゃん向けを全部書く

→ これ全部、ツールで自動化・半自動化できるのでは？
```

### DGE との関係

```
DGE (Design Gap Extraction):
  設計の「書いてないこと」を発見する
  キャラクターが議論する会話劇で Gap を抽出
  → 設計品質を上げる

??? (Document ??? Extraction):
  ドキュメントの「伝わってないこと」を発見する
  LLM がドキュメントを読んで理解度の穴を抽出
  → ドキュメント品質を上げる
```

---

## 機能構想

### 1. 用語抽出 + アーティクル生成

```
入力: README.md
処理:
  1. LLM がドキュメントを読む
  2. 「この読者層には分からないだろう」用語を抽出
  3. 用語ごとにアーティクル（解説記事）を生成
  4. レベル指定: expert / beginner / grandma
  5. ja/en 両方生成
出力: docs/glossary/*.md
```

### 2. 自動リンク（Glossary Linker）

```
入力: README.md + docs/glossary/
処理:
  1. glossary の全ファイル名 → 用語辞書を構築
  2. 文字数降順でソート（最長一致）
  3. README を走査、未リンクの用語を発見
  4. 段落ごとに 1 回リンク化
  5. コードブロック/見出し/既存リンクはスキップ
出力: README.md（リンク追加済み）
```

### 3. 図の自動提案

```
入力: README.md
処理:
  1. LLM がドキュメントを読む
  2. 「この段落は図がないと伝わらない」箇所を特定
  3. mermaid 図を自動生成（flowchart, sequence, state）
  4. 既存の図へのリンクも提案
出力: 図の提案 + mermaid コード
```

### 4. 読者レベル判定 + ギャップ検出

```
入力: README.md + 対象読者の指定
処理:
  1. 「この読者にとって分からない部分はどこか」を LLM が判定
  2. 分からない用語、暗黙の前提知識、飛躍した説明を検出
  3. 改善提案（用語集リンク追加、図追加、説明追加）
出力: ギャップレポート
```

### 5. 多言語同期

```
入力: README.md (en) + README.ja.md (ja)
処理:
  1. 差分検出（en にあって ja にない内容）
  2. 未翻訳セクションの特定
  3. 翻訳提案
出力: 同期レポート + 翻訳 draft
```

---

## ツールキット構成

```
@unlaxer/dde-toolkit (仮)
  ├── bin/dde-tool.js           ← CLI
  ├── skills/
  │   ├── dde-session.md        ← Claude Code skill（ドキュメントレビュー）
  │   ├── dde-glossary.md       ← 用語抽出 + 記事生成
  │   └── dde-linker.md         ← 自動リンク
  ├── templates/
  │   ├── glossary-article.md   ← 用語記事テンプレート
  │   ├── diagram-proposal.md   ← 図の提案テンプレート
  │   └── gap-report.md         ← ギャップレポートテンプレート
  ├── flows/
  │   ├── quick.yaml            ← サッとレビュー
  │   ├── full-review.yaml      ← 全面レビュー
  │   └── glossary-build.yaml   ← 用語集構築
  └── config/
      └── reader-levels.yaml    ← 読者レベル定義
```

### 使い方

```bash
# インストール
npm install @unlaxer/dde-toolkit
npx dde-install

# Claude Code で
「このドキュメントをレビューして」
「用語集を作って」
「図が足りないところを教えて」
「初心者向けに分かりにくいところは？」
```

---

## 名前候補

```
DDE — Document Deficit Extraction
  ドキュメントの欠損を抽出する
  DGE の兄弟。D*E シリーズ。

  Design-Gap Extraction     → DGE（設計の穴）
  Document-Deficit Extraction → DDE（ドキュメントの穴）
```

---

## 哲学

```
DGE の哲学:
  「書いてないことの発見」

DDE の哲学:
  「伝わってないことの発見」

  ドキュメントの仕事はユーザーの頭の中に図を作ること。
  全ての単語がクリッカブル。
  書くのは地獄。読むのは天国。
  おばあちゃんでも新人でもクリックすれば分かる。
```
