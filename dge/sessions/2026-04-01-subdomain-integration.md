# DGE Session — サブドメイン解決 + ルーティング統合
- **Date**: 2026-04-01
- **Flow**: design-review (tribunal)
- **Theme**: volta-auth-proxy にサブドメイン解決・App ルーティング設定を統合。Zero Config 思想。
- **Characters**: 金八(素人), Auth 専門家, ヤン, リヴァイ, SaaS 専門家

---

## 核心の決定

### 1. volta はリバースプロキシにならない（ForwardAuth 維持）

```
❌ volta がリバースプロキシ: ブラウザ → Traefik → volta → App
✅ volta は設定の集約点:    ブラウザ → Traefik → App
                                        ↓
                                    volta (ForwardAuth)

volta は "設定の集約点" であって "トラフィックの集約点" ではない
```

### 2. volta-config.yaml → Traefik 動的設定を自動生成

```
volta-config.yaml (入力)
  → volta が起動時/リロード時に生成
  → traefik-dynamic.yaml (出力)
  → Traefik が File Provider で watch して反映
```

### 3. サブドメイン = App（Phase 1）

```
Phase 1: wiki.example.com → Wiki App
Phase 2: acme.wiki.example.com → ACME 社の Wiki App（テナントサブドメイン）
```

### 4. .env はシークレットだけ。設定は全部 YAML。

```
.env: DB 接続、Google OAuth、暗号化キー、サービストークン
volta-config.yaml: App 登録、テナント解決、デフォルト値、サポート連絡先
```

## Gap 一覧

### DGE で発見 (8 件)

| # | Gap | Severity |
|---|-----|----------|
| SC-1 | サブドメインの用途 Phase 分け | 🟠 High |
| SC-2 | ForwardAuth 維持 + Traefik 設定自動生成 | 🔴 Critical → 解決済 |
| SC-3 | 設定リロード方式 | 🟡 Medium |
| SC-4 | public_paths セキュリティ検証 | 🟠 High |
| SC-5 | .env と YAML の役割分担 | 🟠 High → 解決済 |
| SC-6 | CORS の App 固有設定 | 🟡 Medium |
| SC-7 | health_check の活用 | 🟡 Medium |
| SC-8 | ALLOWED_REDIRECT_DOMAINS 自動導出 | 🟡 Medium |

### LLM で追加発見 (12 件)

| # | Gap | Severity |
|---|-----|----------|
| LM-1 | テナントサブドメインと App サブドメインの名前空間衝突 | 🟠 High |
| LM-2 | リバースプロキシ化のセキュリティ面 → ForwardAuth 維持で解決 | 🔴 → 解決済 |
| LM-3 | SSL 終端の責任者 → Traefik に維持 | 🟠 High → 解決済 |
| LM-4 | WebSocket/SSE 対応 → protocol フィールドをスキーマに追加 | 🟠 High → 解決済 |
| LM-5 | サーキットブレーカー → Phase 2 | 🟡 Medium |
| LM-6 | SPOF リスク → ForwardAuth 維持で軽減 | 🟠 High → 解決済 |
| LM-7 | Cloudflare Zero Trust 移行パス → allowed_email_domains で代替 | 🟠 High → 解決済 |
| LM-8 | upstream SSRF 防止 → Traefik がルーティングするので volta は関係なし | 🟡 Medium → 解決済 |
| LM-9 | パフォーマンスバジェット → ForwardAuth 維持で 10ms/50ms 維持 | 🟡 → 解決済 |
| LM-10 | ホットリロード + バージョニング → version フィールド追加 | 🟡 Medium |
| LM-11 | テナント別 App 可視性 → Phase 2 | 🟡 Medium |
| LM-12 | 不足フィールド → timeout_ms, protocol, max_body_size 等追加済 | 🟠 → 解決済 |

### DGE + LLM の合意

**volta はリバースプロキシにならない。ForwardAuth を維持。**
**設定の集約は volta-config.yaml → Traefik 動的設定の自動生成で実現。**
