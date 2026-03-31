# DGE Project Feedback — volta-auth-proxy

---
project: volta-auth-proxy
date: 2026-03-31
dge_version: 3.0.0
rounds: 8 sessions + 3 auto-iterations
gaps_found: 106
gaps_resolved: 106
convergence: auto-iterate 全て 1/5 で収束
---

## Project Summary

Identity Gateway（認証プロキシ）の Phase 1 設計。Traefik + 自前 Gateway (Javalin) + Postgres の最小構成で、auth / signup / tenant / role / invitation を一手に引き受け、下流 App は identity context を受け取るだけにする設計。

## Session Log

| # | Session | Gaps | 主な成果 |
|---|---------|------|----------|
| 1 | Quick: 基本設計 | 22 | 基礎的な設計穴の発見 |
| 2 | Quick: SaaS + Auth 専門家 | 16 | M2M 欠落、SDK 必要性、テナント LC |
| 3 | Quick: 移譲 vs 自前 | 20 | 仕分けマトリクス、oauth2-proxy リスク発見 |
| 4 | Quick: Keycloak 排除 | 9 | Phase 1 外部サーバーゼロ構成 |
| 5 | Auto: バックエンド | 18 解決 | DB スキーマ、JWT 仕様、全環境変数 |
| 6 | Design-review: UI/UX | 22 | 画面一覧、エラー UX、volta-sdk-js |
| 7 | Auto: UX | 13 解決 | jte 選定、Content Negotiation、モバイル |
| 8 | Quick: Protocol | 17 | ForwardAuth 採用、Internal API、ヘッダ契約 |
| 9 | Auto: Protocol | 13 解決 | tenantId チェック、aud 配列化、サービストークン |

## DGE vs 素の LLM — 採用比較

```
DGE 案採用:   13 件（主にアーキテクチャ判断・方針決定）
LLM 案採用:   11 件（主にセキュリティ詳細・具体的仕様値）
両方一致:     残り全て
```

### DGE が強かったもの
- アーキテクチャの劇的な簡素化（5 コンポーネント → 2 コンポーネント）
- 「やらない」判断（Keycloak 不要、oauth2-proxy 不要）
- 責務分担の仕分け（DELEGATE / HYBRID / BUILD）
- Phase 構成の段階的移行設計
- Protocol パターン（ForwardAuth 採用）
- プロジェクト scope の再定義（"Auth Proxy" → "Tenant Context Gateway"）

### LLM が強かったもの
- セキュリティの見落とし（CSRF、email_verified バイパス、tenantId 不整合）
- 具体値の決定（同時ログイン上限 5、Rate Limit 値）
- DB スキーマの完全な SQL 定義
- Java コードレベルの実装例
- 環境変数一覧の網羅
- Safari ITP 等のプラットフォーム固有問題

### 補完関係の結論
DGE は「何を作り何を作らないか」を決める。LLM は「作ると決めたものの詳細」を埋める。両方ないと片手落ち。

## DGE が出した重要な決定事項（10 件）

### アーキテクチャを根本的に変えた決定（5 件）

| # | 決定 | Round | Character | Impact |
|---|------|-------|-----------|--------|
| 1 | 「Keycloak の再発明じゃない？」→ Keycloak 不要 | R3 | ☕ ヤン | 構成を 5→2 コンポーネントに削減 |
| 2 | Phase 1 は外部サーバーゼロ（Gateway + Postgres だけ） | R4 | ⚔ リヴァイ | docker-compose 最小化、起動 200ms |
| 3 | Gateway の正体は "Tenant Context Gateway" | R3 | 💼 SaaS専門家 | プロジェクト scope 再定義 |
| 4 | ForwardAuth パターン採用（Proxy は認証チェックのみ） | Protocol | 💼 SaaS専門家 | App 独立スケール、Proxy 非ボトルネック |
| 5 | 仕分けマトリクス（DELEGATE 7 / HYBRID 4 / BUILD 7） | R3 | 🎩 千石 | 責務分担の判断基準確立 |

### 設計方針を決めた決定（5 件）

| # | 決定 | Round | Character |
|---|------|-------|-----------|
| 6 | 「制御しやすいは正義」→ 自前構成のメリット 7 つ | R4 | ⚔ リヴァイ |
| 7 | Phase ごとの段階的移行（A: 自前 → B: Hydra → C: Keycloak） | R4 | 💼 SaaS専門家 |
| 8 | JWT 発行は自前（nimbus-jose-jwt）、OIDC サーバーは Phase 1 不要 | R4 | 🔐 Auth専門家 |
| 9 | Google OIDC を直接叩く（中間 IdP 不要） | R4 | ☕ ヤン |
| 10 | App のやることは 2 つだけ（ヘッダ読む or API 叩く） | Protocol | ⚔ リヴァイ |

## Character Effectiveness

| Character | 評価 | 理由 |
|-----------|------|------|
| ☕ ヤン | ★★★★★ | MVP。「Keycloak 要らない」「oauth2-proxy 要らない」「インタースティシャル要らない」。削る力が最強 |
| ⚔ リヴァイ | ★★★★★ | 自動反復で具体解を出すのに最強。「決めろ」で曖昧さを排除 |
| 🔐 Auth専門家 (ad-hoc) | ★★★★☆ | Round 2 以降の品質を支えた。Keycloak の内部を知っているからこそ「何が要らないか」も言えた |
| 💼 SaaS専門家 (ad-hoc) | ★★★★☆ | テナント LC、プラン、データ削除、ForwardAuth。量産視点が他キャラにない角度 |
| 🎩 千石 | ★★★☆☆ | 品質基準の提示は有効だったが、出番が少なかった |
| 👤 今泉 | ★★★☆☆ | 「そもそも」の問いは有効。ただし専門家キャラが入ると役割が薄れる |
| 😰 僕 | ★★☆☆☆ | DX 指摘（docker-compose 重い）は良かったが出番少 |
| 🎨 深澤 | ★★★★☆ | UI/UX Round で真価発揮。投入が遅すぎた。もっと早く入れるべき |
| 🎰 利根川 | ★★★☆☆ | 「ユーザーの 8 割はスマホ」等の現実指摘は鋭い |

### Character 構成の教訓

```
最適構成（今回の実感）:
  基本設計: ヤン + 今泉 + 僕（削る + 問う + 縮小）
  専門深堀: ヤン + 専門家 2 名（削る + 専門知識）
  自動反復: リヴァイ + 専門家 1 名（決める + 検証）
  UI/UX:   深澤 + 利根川（感情 + 現実）

教訓:
  - 専門家キャラは Round 2 から投入すべき（Round 1 は広く浅く）
  - 深澤は Round 1 から入れるべきだった（UI/UX が後回しになった）
  - ヤンは全ラウンドに入れるべき（削る力は常に必要）
  - リヴァイは自動反復フェーズで最強
```

## What Worked Well

1. **auto_merge が極めて有効** — DGE のアーキテクチャ判断 + LLM のセキュリティ詳細で完成
2. **専門家キャラ（Auth, SaaS）の投入** — built-in だけでは出ない深さ
3. **ヤンの「やるな」** — アーキテクチャを 5→2 コンポーネントに劇的簡素化
4. **自動反復の収束が早い** — 全て 1/5 で収束。解決策が明確になれば Gap は一気に消える
5. **仕分けマトリクス（DELEGATE/HYBRID/BUILD）** — 新しいパターンとして汎用性が高い
6. **Phase ごとの最小構成** — 「今」と「将来」を分離する設計手法として有効
7. **Protocol 設計を独立テーマとして実施** — App ↔ Proxy の契約が明確に

## What Didn't Work Well

1. **Round 1-2 の Gap が Round 3-4 で無効化** — アーキテクチャ変更時の Gap 棚卸しステップがなく、累計カウントが不正確に
2. **UI/UX が最後まで白紙** — 深澤の投入が遅すぎた。LLM に「バックエンド品質は極めて高いが、フロントエンドはほぼ白紙」と指摘された
3. **Protocol が別テーマとして後から発生** — 最初から「App との契約」を意識すべきだった
4. **DGE の解決策が方針レベルで止まりがち** — LLM が DB スキーマ・コード例まで出すのに対し、DGE は「こうすべき」で止まる。自動反復で補完できたが、もっと早く具体化すべき
5. **Javalin 選定が議論されなかった** — ユーザーの発言から暗黙に決定。技術選定もキャラで議論すべきだった

## New Patterns Discovered

### 1. delegation-matrix（移譲マトリクス）
```
Category: B（探索パターン）
発見するもの: 自前 vs 外部の最適な責務分担
手順:
  1. 機能を全列挙
  2. DELEGATE / HYBRID / BUILD に仕分け
  3. HYBRID が最も危険 → 重点レビュー
  4. 再生産のメリット/デメリットを明示
```

### 2. phase-minimization（Phase 最小化）
```
Category: B（探索パターン）
発見するもの: 各 Phase で本当に必要な最小構成
手順:
  1. 全機能を Phase 1-4 に分類
  2. Phase 1 の最小構成を極限まで削る
  3. 「これがないと動かない」だけ残す
  4. 将来の拡張点を Interface で残す
```

### 3. protocol-design（Protocol 設計）— テンプレート候補
```
Scene 構成:
  Scene 1: 通信方向の列挙（A→B, B→A, イベント, 登録）
  Scene 2: 各方向のデータ形式（ヘッダ / JWT / JSON / Schema）
  Scene 3: エラー契約
  Scene 4: バージョニング + 後方互換性
推奨キャラ: リヴァイ + ヤン + SaaS 専門家
```

## Suggested Method Changes

### 1. LLM マージの手順正式化
method.md に Step 5.5 として「マージフェーズ」を追加。DGE のみ / LLM のみ / 両方一致 の分類手順。不一致時の優先ルール（DGE: 方針判断、LLM: 具体値・見落とし）。

### 2. 専門家キャラのアドホック生成パターン
テーマに応じた専門家の自動提案（auth → Auth専門家、SaaS → SaaS専門家、infra → Infra専門家）。built-in 2-3 名 + 専門家 1-2 名が最適構成。セッション後に保存するか聞く。

### 3. 実装レディネス判定
自動反復の収束条件を「C/H = 0」だけでなく、「DB スキーマ・API 一覧・エラーコード・環境変数・SDK API・画面一覧」のチェックリストで判定。

### 4. アーキテクチャ変更時の Gap リセット
コンポーネント追加/削除時に既存 Gap をスキャンし、無効化された Gap に [VOID] マーク。有効 Gap のみ再カウント。

### 5. プロジェクトフィードバックの仕組み（このファイル自体）
dge/feedback/ ディレクトリにプロジェクト単位のフィードバックを保存。複数プロジェクトのフィードバックを集約して method 改善の素材にする。

### 6. 新パターン「delegation-matrix」の patterns.md への追加
DELEGATE / HYBRID / BUILD の仕分けパターン。

### 7. 新テンプレート「protocol-design」の templates/ への追加
サービス間契約の設計テンプレート。

## Raw Stats

```
Total gaps:           106
  - DGE only:          52
  - LLM only:          54
  - (overlap removed in total)

DGE solutions adopted: 13
LLM solutions adopted: 11
Both agreed:           remaining

Critical gaps:         ~15
High gaps:             ~45
Medium gaps:           ~30
Low gaps:              ~16

Characters used:       9 (built-in 7 + ad-hoc 2)
Templates used:        1 (feature-planning)
Patterns used:         before-after, expertise-contrast, zero-state + 3 new

Auto-iterate rounds:   3 (all converged in 1/5)
Design-review specs:   12

Files generated:       11 (10 sessions + 1 spec)
```
