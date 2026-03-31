# DGE Session — Auth DSL 査読
- **Date**: 2026-04-01
- **Flow**: design-review (tribunal)
- **Theme**: 認証 DSL の設計査読
- **Characters**: Auth 専門家, ヤン, リヴァイ, 右京, 深澤

---

## 結論

### DSL の目的（確定）
1. AI への仕様伝達（最優先）
2. テスト自動生成
3. ドキュメント生成

### DSL の構造（改良版）
- auth-machine.yaml — 状態 + フロー統合（states + flows を統合）
- protocol.yaml — App との契約（ヘッダ、JWT、API、エラー）
- policy.yaml — 認可ルール（ロール、権限、制約）

### 主要な改良点
1. states + flows を 1 ファイルに統合
2. guard の式を context 変数で明示
3. action の種別を type で分類（side_effect / http / audit）
4. エラーを on_error ハンドラで処理
5. Phase タグで拡張性確保
6. タイムアウト表現を追加
7. next_if で条件付き遷移（上から順に評価）

## Gap 一覧
(上記参照)
