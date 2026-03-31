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
(上記参照)
