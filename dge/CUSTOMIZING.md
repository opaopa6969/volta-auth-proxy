# DGE toolkit カスタマイズガイド

## カスタマイズ戦略

```
A. そのまま使う（大多数）
   → npm install + YAML / ファイル追加で十分

B. 全面カスタマイズ（本気勢）
   → git fork + このガイドに従い変更
```

「A で足りなければ fork」。共存の仕組みは提供しない。fork したら `npx dge-update` は使わず `git fetch upstream` で差分管理。

---

## Level 1: 設定変更（fork 不要）

### フロー追加
`dge/flows/` に YAML ファイルを追加。構造は `flows/design-review.yaml` を参考に。

```yaml
name: my-flow
display_name: "📖 My Flow"
extract:
  type: custom
  marker: "→ 発見:"
  # ...
generate:
  types:
    - id: SCENE
      display_name: "📖 シーン"
  output_dir: dge/output/
post_actions:
  - id: again
    display_name: "もう一回"
  - id: generate
    display_name: "生成する"
  - id: later
    display_name: "後で"
```

### キャラ追加
「キャラを追加して」で対話式作成。または `dge/custom/characters/` に手動でファイル作成。

### テンプレート追加
`dge/templates/` にマークダウンファイルを追加。既存テンプレートを参考に。

### パターン
`dge/patterns.md` にカスタムプリセットを追加。

---

## Level 2: skill 書き換え（fork 推奨）

git fork してから `.claude/skills/` のファイルを編集。

### dge-session.md

| セクション | 行 | 変更内容 |
|-----------|-----|---------|
| MUST ルール | 19行〜 | 強制する行動を変更 |
| SHOULD ルール | 31行〜 | 推奨事項を変更 |
| 判断ルール | 44行〜 | auto-decide の条件を変更 |
| Step 3.5 | パターン選択 | プリセット一覧を変更 |
| Step 4 | キャラ推奨 | 推奨ロジックを変更 |
| Step 5 | 会話劇生成 | 先輩ナレーションを廃止・変更 |
| Step 6 | 抽出 | マーカーテキストを変更（flows/ YAML でも可） |
| Step 8 | 選択肢 | 選択肢の構成を変更（flows/ YAML でも可） |
| Step 10 | Spec 生成 | 成果物テンプレートを変更（flows/ YAML でも可） |

### dge-character-create.md

| セクション | 変更内容 |
|-----------|---------|
| wizard 質問 | 質問の内容・順序を変更 |
| axes 定義 | 新しい軸を追加（例: creativity, empathy） |
| 保存フォーマット | Backstory のセクション構成を変更 |

詳細は [INTERNALS.md](./INTERNALS.md) の Hook ポイント一覧を参照。

---

## Level 3: サーバー変更（fork 推奨）

| ファイル | 変更内容 |
|---------|---------|
| server/src/recommend.ts | 推奨アルゴリズム（keyword マップ、coverage ベクトル） |
| server/src/index.ts | API エンドポイントの追加・変更 |
| server/migrations/ | DB スキーマの変更 |

---

## fork のベストプラクティス

```bash
# 1. fork
git clone https://github.com/YOUR/DGE-toolkit.git
cd DGE-toolkit

# 2. upstream を追加
git remote add upstream https://github.com/xxx/DGE-toolkit.git

# 3. 定期的に upstream を確認
git fetch upstream
git log upstream/main --oneline -10

# 4. 必要な変更だけ cherry-pick
git cherry-pick <commit-hash>

# 5. 自分のパッケージとして publish（任意）
cd kit
# package.json の name を変更: "@your-org/dge-toolkit-custom"
npm publish --access public
```

---

## 関連ドキュメント

- [INTERNALS.md](./INTERNALS.md) — フロー図・データフロー図・ステート図・Hook 一覧
- [flows/design-review.yaml](./flows/design-review.yaml) — デフォルトフロー定義
- [integration-guide.md](./integration-guide.md) — 既存 workflow との統合
