# DGE Dialogue Patterns — 23 パターン + 5 プリセット

## 概要

パターンは「どういう角度で設計の穴を攻めるか」を決める。テンプレート（テーマ）× パターン（角度）の組み合わせで会話劇が生成される。

## パターン早見表

### Category A: 対比パターン（5）+ 網羅パターン（1）
2 つの状況を並べて差異から発見を得る / 網羅的に契約の穴を探す。

| ID | パターン | 発見するもの |
|---|---|---|
| A1 | `before-after` 導入前後対比 | 導入効果、残存する手動ステップ |
| A2 | `role-contrast` ロール対比 | 権限問題、ロール別 UX の欠如 |
| A3 | `app-type-variation` アプリ種別変奏 | 種別固有の制約、テンプレの限界 |
| A4 | `expertise-contrast` 習熟度対比 | 初心者ガイダンス不足、パワーユーザー最適化 |
| A5 | `platform-contrast` プラットフォーム対比 | レスポンシブ対応、タッチ操作、API 設計 |
| A6 | `protocol-design` Protocol 設計 | サービス間の通信契約の穴 |

### Category B: 探索パターン（9）
特定の状況を設定して、通常の spec 検討では見えない問題を炙り出す。

| ID | パターン | 発見するもの |
|---|---|---|
| B1 | `zero-state` 空状態起動 | オンボーディング、デフォルト値、初期 UX |
| B2 | `return-after-absence` 不在復帰 | サマリー機能、コンテキスト回復 |
| B3 | `escalation-chain` エスカレーション連鎖 | エラー回復、フォールバック、最終手段 |
| B4 | `cross-persona-conflict` ペルソナ間衝突 | 排他制御、優先度ルール、通知 |
| B5 | `migration-path` 移行パス | データ変換、互換性、ロールバック |
| B6 | `multi-tenant` マルチテナント分離 | データリーク、権限バイパス |
| B7 | `concurrent-operation` 同時操作 | 楽観ロック、last-write-wins、競合 |
| B8 | `delegation-matrix` 移譲マトリクス | 自前 vs 外部の最適な責務分担 |
| B9 | `phase-minimization` Phase 最小化 | 各 Phase で本当に必要な最小構成 |

### Category C: 限界探索パターン（8）
システムの限界点やワークフロー自体の弱点を意図的に探す。

| ID | パターン | 発見するもの |
|---|---|---|
| C1 | `scale-break` スケール破綻 | ページネーション、タイムアウト、メモリ |
| C2 | `hallucination-probe` 幻覚探査 | LLM 生成の信頼性、存在しない機能の補完 |
| C3 | `convergence-test` 収束テスト | DGE 反復の収束判定、矛盾する修正 |
| C4 | `drift-detection` 乖離検出 | 実装後の spec 乖離、ドキュメント陳腐化 |
| C5 | `security-adversary` セキュリティ攻撃者 | 入力バリデーション、認証バイパス |
| C6 | `accessibility-barrier` アクセシビリティ障壁 | スクリーンリーダー、キーボード操作 |
| C7 | `disaster-recovery` 災害復旧 | バックアップ、データ整合性、フェイルオーバー |
| C8 | `i18n-mismatch` 国際化不一致 | 文字化け、日付/通貨、翻訳漏れ |

## プリセット（推奨パターンセット）

| プリセット | パターン | 用途 |
|---|---|---|
| 🆕 `new-project` | zero-state, role-contrast, escalation-chain | 新規プロジェクト |
| 🔧 `feature-extension` | before-after, cross-persona-conflict, expertise-contrast | 機能追加 |
| 🚀 `pre-release` | scale-break, security-adversary, concurrent-operation, disaster-recovery | リリース前チェック |
| 📢 `advocacy` | before-after, app-type-variation, role-contrast | 社内提案・導入説得 |
| 🔍 `comprehensive` | zero-state, role-contrast, escalation-chain, cross-persona-conflict, scale-break, security-adversary, migration-path | 網羅的 DGE |
| 🔥 `hotfix` | escalation-chain, disaster-recovery, drift-detection | 障害対応・振り返り |
| 🤝 `onboarding` | zero-state, expertise-contrast, return-after-absence | 新メンバー受け入れ |
| 💰 `monetization` | role-contrast, scale-break, cross-persona-conflict | 課金・ビジネスモデル検証 |
| 🧹 `tech-debt` | drift-detection, before-after, convergence-test | リファクタリング前の洗い出し |

## テンプレート → プリセット 自動推奨

| テンプレート | 推奨プリセット |
|---|---|
| api-design | feature-extension |
| feature-planning | new-project |
| go-nogo | advocacy |
| incident-review | hotfix |
| security-review | pre-release |

パターンを指定しなければ、テンプレートに応じたプリセットが自動的に使われます。

## 詳細

各パターンの詳細な説明と実績は [docs/dge-v2/dge-dialogue-patterns.md](../docs/dge-v2/dge-dialogue-patterns.md) を参照。
