# Google 認証 loop 調査メモ

調査日: 2026-04-28
調査元リポジトリ: volta-platform
対象: volta-auth-proxy (OidcFlowDef + AuthFlowHandler)

## 主張

Google 認証で **稀に loop が発生する事例がある**。これは tramli state machine の定義外の状況に陥っているためか？

## 結論

**No.** loop は state machine の定義外現象ではなく、**state machine の射程外 (HTTP/Cookie/ブラウザ層)** で発生している。

State machine 自体は健全:

- `OidcFlowDef` の遷移グラフはすべて terminal state (`COMPLETE`, `COMPLETE_MFA_PENDING`, `BLOCKED`, `TERMINAL_ERROR`) で終わる。
- 唯一の循環 `RETRIABLE_ERROR → INIT → REDIRECTED` も `flow.isCompleted()` チェックで補足され、`FLOW_INCOMPLETE` 例外として 500 を返す。
- 各 `flowId` は独立しており、複数 flow をまたいだ「ループ」は state machine の責務外。

## ログ証跡 (実例)

`logs/auth-proxy.log` (2026-04-27 11:47):

```
11:47:30  flow=ab3f4f6c  oidc INIT → REDIRECTED   (Google へリダイレクト)
11:47:32  flow=5e74be8b  passkey CHALLENGE_ISSUED  (別フロー)
11:47:40  flow=1453af4c  oidc INIT → REDIRECTED   ← ユーザが「もう一度ログイン」を押下
11:47:44  flow=1453af4c  CALLBACK_RECEIVED → … → COMPLETE  (成功)
```

`ab3f4f6c` は `REDIRECTED` のまま callback が来ず孤児化 → 5分後 TTL で破棄。
state machine 視点ではこれら2つは独立した flow であり、**ユーザ視点では「2回ログインしたらやっと通った」** に見える。

## 真の loop が発生しうるシナリオ (state machine 外)

ユーザが体感する loop は次のいずれか:

| # | 原因 | 場所 | 確認方法 |
|---|------|------|---------|
| 1 | Cookie が次リクエストで届かない (SameSite / Domain / Secure 整合性) | `HttpSupport.setSessionCookie` + browser | DevTools Application → Cookies で `__volta_session` の有無 |
| 2 | CF Tunnel / Traefik が `Set-Cookie` を落とす | infra | レスポンスヘッダ (Set-Cookie) の有無 |
| 3 | Frontend が 401 で自動 `/login` へ飛ばす実装 (cookie commit 前に発火) | volta-console / volta-gateway | フロント側 401 ハンドラ確認 |
| 4 | TTL 5分超過後の callback → `FLOW_EXPIRED` | tramli engine | `FLOW_INCOMPLETE` / `FLOW_EXPIRED` ログ検索 |
| 5 | ユーザが Back/Forward / pre-fetch で `/login` を再度叩いて孤児 flow 量産 | browser | アクセスログで startOidc 連続発火 |

## 確認済み事項

- `.env`: `COOKIE_DOMAIN=.unlaxer.org`, `FORCE_SECURE_COOKIE=true`, `BASE_URL=https://auth.unlaxer.org`
- Cookie 属性: `HttpOnly; SameSite=Lax; Secure; Domain=.unlaxer.org` (cross-subdomain で正しく届く想定)
- volta-console frontend (`authStore.js`) には 401 自動リダイレクト処理 **無し** (logout のみ手動 redirect) → 上記 #3 は除外
- `OidcFlowDef.create()` の `ttl(Duration.ofMinutes(5))` → 5分以内に callback が来ないと expire

## State machine の射程明確化

`OidcFlowDef` (production の OIDC flow):

- 1 flow = 1 認証試行 (login click → callback → terminal state)
- 「孤児 flow」「Cookie が無効」「ブラウザが古い state を使う」は **モデル化されていない**
- 上位の `auth-machine.yaml` v3 は YAML spec として `no_deadlock` / `reachable_auth` 等の invariant を持つが、こちらは **AI 仕様/テスト生成のみで使用**、production runtime とは別。`AuthFlowDefinition.java` も同様に未使用 (Mermaid diagram 生成のみ)。

## 推奨アクション

1. **再現時の証跡保全**:
   - DevTools Network: `Set-Cookie` レスポンスヘッダ / 後続リクエストの `Cookie` ヘッダ
   - DevTools Application → Cookies: `__volta_session` の Domain / Expires
   - 同時刻帯の `auth-proxy.log` (`flowId` 遷移、`exit=` 値、`cookie=present|absent`)

2. **観測性向上 (要 issue 化)**:
   - 短時間内の連続 `startOidc` を warn ログ化 (孤児化検知)
   - `flow.isCompleted()` 失敗時のエラー文に `flowId`, `currentState` を含める
   - Cookie 受信前の verify で 401 を返した経路に identifying header を付与

3. **state machine 自体への変更は不要**。loop は state machine の bug ではなく、HTTP/Cookie 経路の bug。

## 参考ファイル

- `src/main/java/org/unlaxer/infra/volta/flow/oidc/OidcFlowDef.java` — runtime state machine
- `src/main/java/org/unlaxer/infra/volta/auth/AuthFlowHandler.java` — `/login`, `/callback`, `/auth/verify`
- `src/main/java/org/unlaxer/infra/volta/AuthService.java` — session cookie lookup
- `src/main/java/org/unlaxer/infra/volta/HttpSupport.java#setSessionCookie` — cookie 属性生成
- `dsl/auth-machine.yaml` — 上位 spec (runtime 非依存)
