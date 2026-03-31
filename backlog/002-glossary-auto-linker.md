# Backlog 002: Glossary Auto-Linker

## Phase: 2
## Priority: Medium
## Category: DX / Documentation

---

## 問題

docs/glossary/ の記事数が 240+ に達した。README の用語リンクは手動で貼っている。
新しい用語を glossary に追加しても、README や他のドキュメントに自動でリンクが貼られない。
手動リンクは 300 記事を超えたあたりで限界が来る。

## 着想

はてなブックマークのキーワード自動リンクと同じ仕組み。
記事中の用語が辞書（glossary）に存在したら自動でクリッカブルにする。

## 仕様

### glossary-linker CLI

```bash
# 使い方
npx glossary-linker README.md --glossary docs/glossary/ --lang en
npx glossary-linker README.ja.md --glossary docs/glossary/ --lang ja

# or CI で
npx glossary-linker --check  # リンク漏れを検出（dry-run）
npx glossary-linker --fix    # 自動リンク化
```

### 動作

```
1. docs/glossary/ の全 .md ファイル名を収集
   jwt.md → ["JWT", "jwt"]
   multi-tenant.md → ["マルチテナント", "multi-tenant", "Multi-tenant"]
   sliding-window-expiry.md → ["スライディング", "sliding window"]

2. 辞書ファイル生成（or glossary/README.md から抽出）
   用語 → ファイルパス のマッピング

3. 対象ファイル（README.md 等）を走査
   - コードブロック内はスキップ（``` で囲まれた部分）
   - テーブルヘッダはスキップ
   - ## 見出しはスキップ
   - 既にリンク済みの用語はスキップ [term](url)

4. 未リンクの用語を発見 → リンク化
   "OIDC" → "[OIDC](docs/glossary/oidc.md)"
   "マルチテナント" → "[マルチテナント](docs/glossary/multi-tenant.ja.md)"

5. リンク頻度ルール
   - 段落ごとに最大 1 回（同じ段落で 2 回リンクしない）
   - 最長一致（"session fixation" > "session"）
```

### 辞書定義ファイル

```yaml
# docs/glossary/dictionary.yaml（自動生成 or 手動管理）
terms:
  - file: jwt.md
    match:
      en: ["JWT", "JSON Web Token"]
      ja: ["JWT"]

  - file: multi-tenant.md
    match:
      en: ["multi-tenant", "multi-tenancy", "Multi-tenant"]
      ja: ["マルチテナント"]

  - file: sliding-window-expiry.md
    match:
      en: ["sliding window", "sliding expiry"]
      ja: ["スライディング", "スライディングウインドウ"]

  - file: oidc.md
    match:
      en: ["OIDC", "OpenID Connect"]
      ja: ["OIDC"]
```

### スキップルール

```
スキップする箇所:
  - ```code blocks```
  - `inline code`
  - [既存リンク](url)
  - ## 見出し行
  - | テーブルヘッダ |
  - HTML タグ内の属性

スキップしない箇所:
  - テーブルのセル内容（リンク化する）
  - > 引用ブロック（リンク化する）
  - リスト項目（リンク化する）
```

### CI 連携

```yaml
# GitHub Actions
- name: Check glossary links
  run: npx glossary-linker --check
  # 新しい用語が glossary にあるのに README にリンクされていなければ warning
```

### 対象ファイル

```
Phase 1: README.md, README.ja.md のみ
Phase 2: docs/**/*.md（設計ドキュメント、Spec、DSL のコメント等）
Phase 3: src/**/*.jte（テンプレート内のコメント — 将来的に）
```

## 実装技術

```
言語: Node.js (npx で実行可能)
or Java (Maven plugin として volta のビルドに統合)

依存:
  - YAML パーサー（辞書読み込み）
  - Markdown パーサー（コードブロック検出）
    → unified + remark がいい
  - 正規表現（用語マッチング）

サイズ: ~200-300 行
```

## テスト項目

- [ ] コードブロック内の用語はリンクされない
- [ ] インラインコード内の用語はリンクされない
- [ ] 既にリンク済みの用語は二重リンクされない
- [ ] 段落内で同じ用語は 1 回だけリンク
- [ ] 最長一致（"session fixation" が "session" より優先）
- [ ] en/ja でそれぞれ正しいファイルにリンク
- [ ] テーブルセル内の用語がリンクされる
- [ ] 見出し行の用語はリンクされない
- [ ] --check モードで diff が出力される
- [ ] --fix モードでファイルが更新される
- [ ] 辞書に用語を追加 → 次回実行でリンクされる
- [ ] 辞書から用語を削除 → 既存リンクは壊れない（リンク先ファイルがなくなるが）

---

## 参考

- はてなブックマークのキーワード自動リンク
- GitHub の autolink references
- WordPress の用語自動リンクプラグイン

## 備考

ユーザーは過去に blog system で同様の機能を実装した経験あり。
