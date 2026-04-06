# DGE Session Round 6: 並行性・Step-up・運用監視

- **日付**: 2026-04-06
- **テーマ**: Flow 並行アクセス制御、Step-up scope の E2E、State machine 監視
- **キャラクター**: 今泉、ヤン、リヴァイ、千石、ハウス

---

## 確定事項

### 並行アクセス制御
- `SELECT ... FOR UPDATE` で flow record をロック
- 2 つ目のリクエストは待ち → 1 つ目が commit → exit_state IS NULL で not found → 409
- `SET LOCAL lock_timeout = '5s'`

### Rate Limiting
```
/login?start=1:         10 req/min per IP
/auth/mfa/verify:       5 req/min per session
/auth/passkey/finish:   5 req/min per session
/callback:              10 req/min per IP
/invite/*/accept:       3 req/min per session
```

### Step-up (SM 外)
- Flow SM は使わない。直接 challenge → verify → scope grant
- session_scopes テーブル + step_up_log テーブル
- ForwardAuth: X-Volta-Required-Scope → scope check → 403 + step_up_url
- Frontend: X-Volta-Scope-Expires でカウントダウン表示

### 監視
- Phase 1: SQL ベース（auth_flow_transitions テーブルから集計）
- Phase 2+: Micrometer → Prometheus → Grafana
- Key metrics: flow duration, guard failures, orphan flows, state distribution

### Phase 1 スコープ
```
振る舞い変更なし。テスト + 図の生成のみ。
1-4: Enum + 遷移テーブル (Session, OIDC, Passkey, MFA)
5: MermaidGenerator + CI
6-7: FlowContext + Processor interfaces
8-9: FlowEngine + FlowStore
10-12: 検証 + テスト harness
```

---

## Gap 一覧 (Round 6)

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 65 | 同一 flow 並行アクセス (FOR UPDATE) | Safety gap | Critical |
| 66 | lock_timeout 設定 | Ops gap | High |
| 67 | 未認証 flow 大量生成 DoS | Safety gap | Critical |
| 68 | エンドポイント別 rate limit | Safety gap | High |
| 69 | Step-up 監査テーブル | Ops gap | Medium |
| 70 | Scope 期限フロントエンド表示 | Integration gap | Medium |
| 71 | 監視の段階的導入 | Ops gap | Medium |
| 72 | Orphan flow 監視 + GC health | Ops gap | Medium |

---

## 全 6 Round 累計

- Critical: 13
- High: 30
- Medium: 25
- Low: 4
- **合計: 72 件**
