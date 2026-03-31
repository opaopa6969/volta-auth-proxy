# DGE Session Round 4 — Keycloak は要らない？最小構成で制御を取る
- **Date**: 2026-03-31
- **Flow**: quick (round 4)
- **Structure**: roundtable
- **Theme**: Keycloak のフルスペックは不要 — 最小限に絞る、小さい OSS、自前の制御性
- **Characters**: ヤン, Auth 専門家(ad-hoc), SaaS 専門家(ad-hoc), リヴァイ
- **Previous**: round1-3 (58 gaps, 3 最重要判断)

---

## Keycloak 機能分解と必要性

```
絶対必要:
  A1. Authorization Code Flow（ブラウザログイン）
  A2. Token 発行・署名（JWT）
  A3. JWKS エンドポイント
  A4. Client Credentials（M2M、将来）

あると便利（将来）:
  D. IdP ブローカー（Google / SAML）
  F. MFA

自前でやる:
  B. ユーザーストア → Gateway DB
  C. ログイン UI → 自前
  E. セッション → Gateway Cookie
  G. 管理 → Gateway API
```

## 3 つのアプローチ

```
1. 軽量 OIDC サーバー（Ory Hydra / Dex）
2. モジュラー ID スタック（Ory Kratos + Hydra）
3. ライブラリで自前（nimbus-jose-jwt）
```

## Keycloak vs Ory Hydra

```
                Keycloak         Ory Hydra
OIDC/OAuth2     ✅ フル          ✅ フル
ユーザー管理    ✅ 内蔵          ❌ 外部委譲
ログイン UI     ✅ 内蔵          ❌ 自前
MFA             ✅ 内蔵          ❌ 自前
起動            30秒+            数秒
メモリ          512MB+           ~50MB
```

## Phase ごとの最小構成

```
Phase 1:
  Gateway (Javalin) + Postgres + nimbus-jose-jwt
  Google OIDC を Gateway が直接叩く
  外部サーバー: なし

Phase 2:
  + Ory Hydra（M2M Client Credentials）
  または Client Credentials も自前

Phase 3:
  選択肢 A: Hydra + Kratos
  選択肢 B: Keycloak 最小構成
  選択肢 C: Gateway で IdP 拡張

Phase 4:
  SAML / SCIM → ここで初めて Keycloak 級が正当化
```

## 自前構成のメリット（制御しやすいは正義）

```
1. 起動速い（200ms vs 30s+）
2. デバッグしやすい
3. 依存少ない（Gateway + Postgres だけ）
4. カスタマイズ自由
5. バージョンアップ影響ゼロ
6. 障害ポイント少ない
7. 学習コスト低い
```

## 自前 JWT セキュリティ必須事項

```
1. RS256 以上（HS256 避ける）
2. alg: none 絶対拒否
3. kid を JWT ヘッダに入れる
4. exp 短く（5-15 分）
5. iss / aud 検証
6. 秘密鍵は環境変数 or シークレット管理
7. JWKS は公開鍵のみ公開
```

---

## Gap 一覧（Round 4）

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| R4-1 | Keycloak の利用範囲が OIDC のみなら過剰 — ランタイムコスト不釣合い | Ops gap | 🟠 High |
| R4-2 | Gateway 直接 Google OIDC パターンが未検討 — 中間 IdP 前提が過剰な可能性 | Spec-impl mismatch | 🟠 High |
| R4-3 | Phase 1 最小要件 vs 将来要件のトレードオフ分析が欠落 | Spec-impl mismatch | 🔴 Critical |
| R4-4 | Hydra + 自前 Gateway で oauth2-proxy 問題解消 — Hydra 運用コスト評価必要 | Ops gap | 🟡 Medium |
| R4-5 | Phase 1 は Gateway + nimbus-jose-jwt で成立する可能性 — OIDC サーバー不要かの判断 | Spec-impl mismatch | 🔴 Critical |
| R4-6 | 自前構成のリスク軽減 — セキュリティチェックリストが未文書化 | Safety gap | 🟠 High |
| R4-7 | 自前 JWT セキュリティ要件の明文化が必要 | Safety gap | 🟠 High |
| R4-8 | Google OIDC 直接連携のセキュリティチェックリスト（state/nonce/PKCE/TLS）が必要 | Safety gap | 🟠 High |
| R4-9 | 「ZITADEL で Gateway 最小化」案 vs 「Gateway 自前で厚く持つ」案の比較が未実施 | Spec-impl mismatch | 🟠 High |

---

## Auto-merge: 素の LLM（OSS 調査）結果

### 軽量 OSS 比較

| ソリューション | メモリ | 起動 | マルチテナント | OIDC |
|---|---|---|---|---|
| Keycloak | 512MB+ | 30s+ | △ | ◎ |
| ZITADEL | 150-300MB | 3-5s | ◎ | ◎ |
| Ory Hydra | 50-100MB | 数秒 | △ | ◎ |
| Dex | 30-50MB | 1-2s | ❌ | ○ |
| SuperTokens | 200-300MB | 5-10s | ◎ | △ |
| Logto | 150-250MB | 3-5s | ◎ | ◎ |
| Authelia | 20-50MB | 1-2s | ❌ | △ |
| nimbus-jose-jwt | ~0 | ~0 | - | ❌ |

### 推奨方向性
1. 最小構成: Ory Hydra + Oathkeeper
2. バランス型: ZITADEL
3. JVM 重視: SuperTokens
4. JWT のみ: nimbus-jose-jwt

### マージ統合: Round 4 全 9 Gap（DGE 7 + 追加 2）
### 累計: Round 1-4 = 67 Gap
