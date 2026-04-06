# DGE Session Round 2: 認証 2 層 StateMachine 詳細設計

- **日付**: 2026-04-06
- **テーマ**: Session SM（認証ライフサイクル）× Flow SM（認証フロー）の 2 層構造を具体化する
- **キャラクター**: 今泉、ヤン、リヴァイ、千石、ハウス
- **入力**: Round 1 の Gap-17 を起点に

---

## 確定事項

### Session SM（4 state + 2 terminal）

```
States:
  AUTHENTICATING              — Flow 開始済み、認証未完了
  AUTHENTICATED_MFA_PENDING   — IdP 認証済み、MFA 未完了
  FULLY_AUTHENTICATED         — 全認証完了、アクティブ
  EXPIRED                     — TTL 超過（terminal）
  REVOKED                     — 明示的無効化（terminal）

遷移:
  AUTHENTICATING → AUTHENTICATED_MFA_PENDING  (IdP OK, MFA enabled)
  AUTHENTICATING → FULLY_AUTHENTICATED        (IdP OK, MFA disabled / Passkey)
  AUTHENTICATING → [destroyed]                (認証失敗)
  AUTHENTICATED_MFA_PENDING → FULLY_AUTHENTICATED  (MFA OK)
  AUTHENTICATED_MFA_PENDING → EXPIRED              (MFA TTL 超過)
  FULLY_AUTHENTICATED → EXPIRED                    (session TTL)
  FULLY_AUTHENTICATED → REVOKED                    (logout / admin revoke)

Step-up: scope ベース（SM 外）
  session_scopes テーブル (session_id, scope, granted_at, expires_at)
```

### 設計方針

- Flow → Session 接続: Engine routing table（宣言的 YAML + Java Predicate）
- 1 session = 最大 1 active flow（後勝ち）
- SM 境界判断: ブロック=state / 許可=scope / 情報=attribute
- 認証失敗: audit_log に書いて session は削除。brute force は rate limiter の責務

---

## Gap 一覧（Round 2）

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 18 | Session が "存在するが無効" な状態の分類不足 | Missing logic | High |
| 19 | Step-up authentication の state が未検討 → scope で解決 | Missing logic | High |
| 20 | 認証失敗セッションの監査保持期間 | Ops gap | Medium |
| 21 | Brute force detection の責務境界 | Missing logic | Medium |
| 22 | 上位・下位 SM の接続プロトコル | Integration gap | Critical |
| 23 | Engine への routing logic 集中問題 | Missing logic | High |
| 24 | Routing 条件の表現方法 | Missing logic | High |
| 25 | 外部 interaction による processor-less transition の型表現 | Type/coercion gap | High |
| 26 | Guard failure 時の遷移ポリシー | Missing logic | High |
| 27 | Guard の retry/rate-limit 戦略の置き場所 | Integration gap | Medium |
| 28 | マルチタブでの並行 Flow 管理 | Missing logic | Critical |
| 29 | Flow キャンセル通知メカニズム | Integration gap | Medium |
| 30 | Step-up を state vs scope でモデリング → scope に決定 | Missing logic | Critical |
| 31 | State machine の境界判断基準 | Missing logic | Critical |

## Gap 詳細（Critical）

### Gap-22: 上位・下位 SM の接続プロトコル
- **Observe**: Flow SM の完了が Session SM をどう遷移させるかの責務が未定義
- **Suggest**: Engine が routing table で mapping。Flow SM は exit state のみ返す
- **Act**: `FlowResult { exitState, context }` → Engine routing → `SessionEvent` → Session SM

### Gap-28: マルチタブ並行 Flow
- **Observe**: 同一 session で複数タブが独立に Flow を起動可能
- **Suggest**: 1 session = 最大 1 active flow。後勝ち
- **Act**: `sessions.active_flow_id` カラム。Flow 開始時に排他 + 旧 Flow cancel

### Gap-30: Step-up — state vs scope → scope に決定
- **Observe**: Step-up を Session SM の state にするとループ発生
- **Suggest**: 時限 scope として SM 外で管理
- **Act**: `session_scopes` テーブル。SM は 4 state に簡素化

### Gap-31: SM 境界判断基準
- **Observe**: 何を state にするか明確な基準がない
- **Suggest**: ブロック=state / 許可=scope / 情報=attribute
- **Act**: この基準を設計ドキュメントに記載し、レビュー時のチェックポイントにする
