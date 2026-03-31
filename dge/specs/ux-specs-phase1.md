# volta-auth-proxy UI/UX Specs — Phase 1
> ⚠️ DGE 生成 — 人間レビュー必須
> status: draft
> source: DGE UI/UX Design Review + Auto-iterate + LLM merge, 2026-03-31

## 技術スタック

```
サーバー:          Javalin 6.x
テンプレート:      jte 3.x (型安全、コンパイル時チェック)
CSS:              単一ファイル volta.css (モバイルファースト、media queries)
JS:               volta-sdk-js (~150行、vanilla JS)
フレームワーク:    なし (server-rendered HTML)
```

## ディレクトリ構造

```
src/main/
  jte/
    layout/base.jte
    auth/login.jte, callback.jte, tenant-select.jte,
         invite-consent.jte, sessions.jte
    error/error.jte
  resources/public/
    css/volta.css
    js/volta.js
```

## 全ルート一覧

| Method | Path | 説明 | 応答 |
|--------|------|------|------|
| before | /* | Content Negotiation filter | - |
| GET | /login | ログインページ | HTML |
| GET | /callback | OIDC コールバック処理 → リダイレクト | 302 |
| POST | /auth/refresh | セッションリフレッシュ | JSON |
| POST | /auth/logout | ログアウト | 302 |
| GET | /select-tenant | テナント選択画面 | HTML |
| POST | /auth/switch-tenant | テナント切替 | JSON |
| GET | /invite/{code} | 招待着地ページ | HTML |
| POST | /invite/{code}/accept | 招待承諾 | 302 |
| GET | /settings/sessions | セッション管理 | HTML |
| DELETE | /auth/sessions/{id} | セッション個別削除 | JSON |
| POST | /auth/sessions/revoke-all | 全セッション削除 | JSON |
| POST | /dev/token | テスト用 JWT (DEV_MODE のみ) | JSON |

---

## UC-UX-01: ログイン後リダイレクト (🔴 Critical)

**Trigger**: ユーザーが App にアクセスし、未認証で Gateway にリダイレクトされる

**Flow**:
1. App が JWT なし検知 → `https://auth.example.com/login?return_to={current_url}`
2. Gateway が `return_to` を session に保存（ホワイトリスト検証後）
3. Google OIDC フロー
4. 認証成功 → session 作成 → JWT 発行
5. `return_to` へ 302 リダイレクト

**Exceptions**:
- return_to 未指定 → デフォルトページ
- return_to がホワイトリスト外 → 無視（オープンリダイレクト防止）
- return_to が別テナント → テナント切替確認画面

**Tech**:
- `GET /login?return_to={url}` — session に保存
- `ALLOWED_REDIRECT_DOMAINS` 環境変数でホワイトリスト管理
- sessions テーブルに `return_to VARCHAR(2048)` カラム追加

---

## UC-UX-02: 認証切れ → 再認証 → 元画面復帰 (🔴 Critical)

**重要**: Gateway は `Accept: application/json` リクエストには **絶対に 302 リダイレクトを返さない**。常に 401 JSON を返す。302 はブラウザの HTML リクエストのみ。これにより SPA の fetch が Google ログイン HTML を受け取る問題を防止。

**Trigger**: SPA の API 呼び出しで 401 受信

**Flow**:
1. 401 受信 → volta-sdk-js がインターセプト
2. `POST /auth/refresh`（Cookie session）→ 新 JWT → 元リクエストリトライ
3. session も無効 → `/login?return_to={current_url}` にリダイレクト
4. 再ログイン後、元の画面に復帰

**SDK 責務**:
- volta-sdk (Java): JWT 検証、VoltaContext
- volta-sdk-js (Browser): 401 インターセプト、refresh、ログインリダイレクト

**Tech**:
```
POST /auth/refresh
  Cookie: __volta_session
  200 → { "token": "<new-jwt>" }
  401 → { "error": { "code": "SESSION_EXPIRED" } }

volta-sdk-js API:
  new VoltaClient({ gatewayUrl })
  volta.setupInterceptor(axiosInstance)
  volta.fetch(url, options)
```

---

## UC-UX-03: 招待リンク初回体験 (🟠 High)

**Flow（3 画面以内）**:

画面 1 — 招待着地ページ:
- テナント名 / ロゴ
- 「{招待者}さんが {テナント名} に招待しています」
- ロール表示
- [Google でログインして参加] ボタン

画面 2 — Google ログイン（Google 側）

画面 3 — 参加完了:
- 「✅ {テナント名} に参加しました」
- [App を開く] ボタン → 3 秒後自動リダイレクト

**Tech**:
```
GET /invite/{code}
  有効 → 着地ページ HTML（テナント名・招待者名・ロール）
  期限切れ → 期限切れページ（再招待リクエストボタン）
  使用済み → 「すでに使用されています」
  不正 → 404

POST /invite/{code}/accept
  Cookie 必須 → membership 作成 → リダイレクト
```

---

## TECH-UX-04: エラー画面設計 (🟠 High)

| code | メッセージ | 次のアクション |
|------|----------|---------------|
| AUTHENTICATION_REQUIRED | ログインが必要です | [ログイン] |
| SESSION_EXPIRED | セッションの有効期限が切れました | [再ログイン] |
| SESSION_REVOKED | セッションが無効化されました | [再ログイン] + 管理者連絡先 |
| FORBIDDEN | アクセスする権限がありません | [戻る] + 権限リクエスト |
| TENANT_ACCESS_DENIED | ワークスペースへのアクセス権がありません | [招待リクエスト] / [切替] |
| TENANT_SUSPENDED | ワークスペースは一時停止中です | 管理者連絡先 |
| ROLE_INSUFFICIENT | この操作の権限がありません | [戻る] + 管理者連絡先 |
| INVITATION_EXPIRED | 招待リンクの有効期限が切れました | [再招待リクエスト] |
| INVITATION_EXHAUSTED | この招待リンクは使用済みです | [再招待リクエスト] / [ログイン] |
| RATE_LIMITED | しばらくお待ちください（{N}秒後） | カウントダウンタイマー |

原則: 技術用語を使わない / 必ず次のアクションを示す / モバイル対応

---

## TECH-UX-05: テナント表示 + 切替 (🟠 High)

**JWT 追加 claims**:
```json
{ "volta_tname": "ACME Corp", "volta_tslug": "acme" }
```

**SDK-js API**:
```javascript
volta.getCurrentTenant() → { id, name, slug }
volta.switchTenant(tenantId) → POST /auth/switch-tenant → reload
```

切替時: 新セッション + JWT 発行 → `window.location.reload()`

---

## TECH-UX-06: 開発者 DX — テスト用トークン (🟠 High)

```
POST /dev/token  (DEV_MODE=true かつ localhost のみ)
  Body: { "userId": "test-user", "tenantId": "test-tenant", "roles": ["ADMIN"] }
  → テスト用 JWT 返却
```

デフォルト: DEV_MODE=false（本番では無効）

---

## TECH-UX-07: メールなし招待 UX (🟠 High)

- 招待リンクのワンクリックコピー（Clipboard API）
- QR コード表示（対面招待用）
- 有効期限の明示
- 招待一覧: ⏳ 未使用 / ✅ 使用済み / ❌ 期限切れ

---

## Auto-merge: 素の LLM で追加発見された Gap

### TECH-UX-08: Content Negotiation 厳密化 (🔴 Critical, LLM Gap 4)

**ルール**:
```
Accept ヘッダ判定:
  application/json を含む → 必ず JSON レスポンス（302 禁止）
  text/html or なし → HTML or リダイレクト

Gateway の全エンドポイントでこのルールを適用。
SPA の fetch() が 302 で Google HTML を受け取る事故を防止。
```

### UC-UX-09: 招待参加の明示的同意 (🟠 High, LLM Gap 12)

招待リンク → Google ログイン → **確認画面（テナント名・ロール・招待者を表示、[参加する] ボタン）** → membership 作成。
auto_join の場合も初回は確認画面を表示（GDPR 同意）。

### TECH-UX-10: OIDC コールバック中のインタースティシャル (🟠 High, LLM Gap 10)

Google コールバック処理中（ユーザー検索・作成・セッション発行）に「ログイン処理中...」画面を表示。
→ state/nonce 不整合によるリロード事故を防止。

### TECH-UX-11: モバイル対応方針 (🟠 High, LLM Gap 5)

- 全画面レスポンシブ（viewport meta、タッチターゲット 44x44px）
- Safari ITP: `SameSite=Lax` で対応可能だが、WebView では Cookie 制限あり → Phase 1 は「モバイルブラウザ対応、WebView は対象外」
- Google OIDC のモバイルブラウザ動作は Google 側が対応済み

### TECH-UX-12: セッション管理 UI (🟠 High, LLM Gap 9)

- `GET /api/me/sessions` → アクティブセッション一覧（デバイス・IP・最終アクセス）
- `DELETE /api/me/sessions/{id}` → 個別セッション失効
- `DELETE /api/me/sessions` → 全デバイスログアウト
- 同時上限到達時: 「ログイン上限に達しています。不要なセッションを終了してください」+ セッション一覧表示

### Phase 2+ 検討 (🟡 Medium)

- UX-17: ローカライゼーション (ja/en) — users テーブルに locale カラム追加、Accept-Language フォールバック
- UX-18: アクセシビリティ — WCAG 2.1 AA 準拠、aria-label、キーボードナビ
- UX-19: テナント別ブランディング — tenants に logo_url, primary_color 追加、ThemeProvider interface
