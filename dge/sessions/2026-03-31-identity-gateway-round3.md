# DGE Session Round 3 — 責務分担: 移譲 vs 自前
- **Date**: 2026-03-31
- **Flow**: quick (round 3)
- **Structure**: roundtable
- **Theme**: Keycloak / oauth2-proxy / Traefik に移譲できるもの vs Gateway で自前が必要なもの + 再生産のメリット
- **Characters**: ヤン, 千石, 🔐 Auth 専門家(ad-hoc), 💼 SaaS 専門家(ad-hoc)
- **Previous**: round1 (22 gaps), round2 (16 gaps)

---

## 仕分けマトリクス

```
┌─────────────────────────┬─────────┬────────────────────────────┐
│ 機能                     │ 判定     │ 理由                        │
├─────────────────────────┼─────────┼────────────────────────────┤
│ OIDC login              │ DELEGATE │ Keycloak + oauth2-proxy     │
│ セッション管理            │ DELEGATE │ oauth2-proxy Cookie         │
│ MFA                     │ DELEGATE │ Keycloak 標準               │
│ M2M 認証                │ DELEGATE │ Keycloak client credentials │
│ ログアウト伝播            │ DELEGATE │ Keycloak backchannel logout │
│ IdP ブローカー           │ DELEGATE │ Keycloak 標準               │
│ 監査ログ（認証イベント）   │ DELEGATE │ Keycloak event listener     │
├─────────────────────────┼─────────┼────────────────────────────┤
│ role mapping            │ HYBRID   │ Keycloak role + GW tenant role│
│ JWT 発行                │ HYBRID   │ 案 A or 案 B（要判断）       │
│ ユーザー属性管理          │ HYBRID   │ Keycloak 基本 + GW 業務属性  │
│ 管理 API                │ HYBRID   │ Keycloak Admin API + GW 拡張 │
├─────────────────────────┼─────────┼────────────────────────────┤
│ テナント解決              │ BUILD    │ 既存ツールにない             │
│ 招待フロー               │ BUILD    │ 既存ツールにない             │
│ テナントライフサイクル     │ BUILD    │ 既存ツールにない             │
│ プラン / エンタイトルメント│ BUILD    │ 既存ツールにない             │
│ API キー管理             │ BUILD    │ 既存ツールにない             │
│ App 向け SDK             │ BUILD    │ プロジェクト固有             │
│ エラー契約               │ BUILD    │ プロジェクト固有             │
└─────────────────────────┴─────────┴────────────────────────────┘

DELEGATE: 7 / HYBRID: 4 / BUILD: 7
```

## 再生産のメリット

```
メリット 1: Keycloak からの独立性（IdP 乗り換え自由）
メリット 2: テナント体験の完全制御（ログイン画面・エラー・管理画面）
メリット 3: データモデルの柔軟性（テナント × role × attribute）
メリット 4: セキュリティの透明性（自前コードの完全把握）
メリット 5: 軽量性（Javalin 200ms 起動 vs Keycloak 数十秒）
```

## 再生産のデメリット

```
デメリット 1: セキュリティの責任（OIDC 実装ミス = 即インシデント）
デメリット 2: 保守コスト（パッチ・脆弱性・仕様更新）
デメリット 3: 車輪の再発明の量
```

## JWT 発行の 2 つの案

```
案 A: Keycloak Token Exchange + Protocol Mapper
  Keycloak mapper で tenant_id / tenant_roles を claims に追加。
  メリット: 鍵管理ゼロ
  デメリット: Keycloak に業務知識が漏れる

案 B: Gateway が internal JWT を再発行
  Keycloak token で認証 → Gateway がコンテキスト付与 → 署名。
  メリット: 責務分離がきれい
  デメリット: 鍵管理が必要
```

---

## 会話劇

### Scene 1-6

(会話劇本文は上記の通り)

---

## Gap 一覧（Round 3）

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| R3-1 | テナント解決ロジックは既存ツールで実現不可 — Gateway の中核責務として設計が必要 | Spec-impl mismatch | 🟠 High |
| R3-2 | Keycloak MFA はレルム = テナント前提 — シングルレルム運用では移譲不可 | Spec-impl mismatch | 🟠 High |
| R3-3 | Keycloak role のフラット構造と「テナントスコープ付き role」のギャップ | Missing logic | 🔴 Critical |
| R3-4 | 二層 role モデル採用時、internal JWT 再発行が必須 — 「token そのまま通す」戦略が破綻 | Spec-impl mismatch | 🔴 Critical |
| R3-5 | JWT 発行戦略（案 A vs 案 B）の判断基準が未定義 | Spec-impl mismatch | 🔴 Critical |
| R3-6 | Keycloak 起動コストが DX 要件と衝突 — 軽量 IdP との比較未検討 | Ops gap | 🟡 Medium |
| R3-7 | 再生産 vs 移譲の判断フレームワークが明文化されていない | Spec-impl mismatch | 🟠 High |
| R3-8 | Gateway の責務が「テナントコンテキスト付与 + DX」であるという位置づけが明文化されていない | Spec-impl mismatch | 🟠 High |
| R3-9 | HYBRID 判定 4 機能の責務境界の具体的な線引き仕様が未定義 | Missing logic | 🔴 Critical |

---

## Auto-merge: 素の LLM レビュー結果（Round 3）

### 仕分け修正
- セッション管理: DELEGATE → **HYBRID**（oauth2-proxy backchannel logout 未対応）
- ログアウト伝播: DELEGATE → **HYBRID**（同上）
- メールドメイン制限: **HYBRID** 新規追加

### 修正後集計: DELEGATE 5 / HYBRID 7 / BUILD 7

### LLM のみで発見された Gap

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| L3-1 | oauth2-proxy / Keycloak セッション TTL 不整合 | Integration gap | 🟠 High |
| L3-2 | セッション二重管理（backchannel logout 未対応） | Safety gap | 🔴 Critical |
| L3-3 | Registration SPI vs Admin API の責務境界（verify-email 制御権） | Spec-impl mismatch | 🟠 High |
| L3-4 | ドメイン制限ルール二重定義 → Admin API バイパスリスク | Safety gap | 🟠 High |
| L3-5 | Realm-per-Tenant vs Single-Realm の不可逆な設計判断 | Spec-impl mismatch | 🔴 Critical |
| L3-6 | ロール変更イベント伝搬欠如 → キャッシュ不整合 | Missing logic | 🟠 High |
| L3-7 | トークン変換 claim スキーマ未定義 + 鍵ローテーション 2 系統化 | Ops gap | 🟠 High |
| L3-8 | M2M / ユーザートークン検証パス分岐が暗黙カスタムコード化 | Missing logic | 🟡 Medium |
| L3-9 | Single-Realm テナント別 MFA に Keycloak SPI 必要 | Spec-impl mismatch | 🟠 High |
| L3-10 | テナント停止即座遮断が JWT stateless 性で困難 | Safety gap | 🟠 High |
| L3-11 | RP-Initiated Logout 完全性（oauth2-proxy backchannel 未対応） | Safety gap | 🔴 Critical |

### 戦略的所見: oauth2-proxy がリスクポイント
L3-2, L3-1, L3-11 の 3 件が oauth2-proxy の制約に起因。
選択肢: (A) 制約受容 / (B) Gateway で OIDC RP 自前実装 / (C) Gateway を ForwardAuth 化

### マージ統合: Round 3 全 20 Gap（DGE 9 + LLM 11）
### 累計: Round 1 (22) + Round 2 (16) + Round 3 (20) = 58 Gap
