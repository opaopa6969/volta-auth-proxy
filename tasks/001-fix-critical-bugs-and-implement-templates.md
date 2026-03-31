# Task 001: 致命的バグ修正 + テンプレート本実装 + volta-sdk-js

## 背景

Codex で scaffolding + DB マイグレーションまで完了した。
しかし LLM コードレビューで致命的バグ 4 件 + セキュリティ問題 7 件が発見された。

設計ファイル:
- `dge/specs/implementation-all-phases.md` — 全体設計
- `dge/specs/ux-specs-phase1.md` — UI 仕様
- `dge/specs/ui-flow.md` — 画面遷移図 + Gap 一覧

## 制約（厳守）

- Maven（pom.xml）。Gradle は使わない
- Javalin 6.x + jte 3.x + nimbus-jose-jwt
- Keycloak / oauth2-proxy は使わない
- Google OIDC 直接連携
- ForwardAuth パターン（Proxy はボディを中継しない）
- ポート: App=7070, Postgres=54329

---

## 🔴 致命的バグ修正（最優先）

### Bug 1: 招待フローの membership 競合

**現状**: `/callback` の処理順序が:
```
1. upsertUser()
2. resolveTenant() ← 招待の tenantId を使う
3. findMembership() ← ★ここで死ぬ。初回ユーザーはまだ membership がない
```

**修正方針**:
招待コード付き（`invite_code` パラメータあり）の場合:
1. `/callback` では findMembership をスキップ
2. セッション作成時に tenant_id は招待の tenant_id を使うが、membership チェックはしない
3. `/invite/{code}/accept` の POST で初めて membership を作成
4. membership 作成後にセッションを再発行（テナント確定）

招待コードなし（通常ログイン）の場合:
- 従来通り findMembership を実行

### Bug 2: テンプレートが全部スキャフォールド

全テンプレートを本実装に差し替える。以下の仕様に従うこと。

#### login.jte
```html
<!-- base.jte を使う -->
内容:
  - タイトル: 「ログイン」
  - [Google でログイン] ボタン → GET /login（OIDC リダイレクト開始）
  - 招待コンテキストがある場合: 「{テナント名} に招待されています」表示
  - 「Google アカウントで認証します」説明テキスト

テンプレートに渡すモデル:
  title: String
  inviteContext: InviteContext? (tenantName, inviterName, role) — nullable
```

#### tenant-select.jte
```html
内容:
  - タイトル: 「ワークスペースを選択」
  - テナント一覧（カード形式）
    - テナント名
    - ロール
    - [前回] バッジ（最終アクセステナント）
  - テナント 6+ の場合: 検索バー
  - 各カードクリック → POST /auth/switch-tenant

テンプレートに渡すモデル:
  title: String
  tenants: List<TenantInfo> (id, name, slug, role, isLast)
  returnTo: String?
```

#### invite-consent.jte
```html
内容:
  - 未認証: 「{テナント名} に招待されています」+ [Google でログインして参加] ボタン
  - 認証済: 「{テナント名} に参加しますか？」
    - テナント名、招待者名、ロール表示
    - 同意文言: 「参加すると、管理者があなたのプロフィールを閲覧できます」
    - [参加する] ボタン（form POST /invite/{code}/accept）
    - [キャンセル] リンク

テンプレートに渡すモデル:
  title: String
  code: String
  tenantName: String
  inviterName: String
  role: String
  isLoggedIn: boolean
  csrfToken: String
```

#### sessions.jte
```html
内容:
  - タイトル: 「アクティブセッション」
  - セッション一覧
    - デバイスアイコン（🖥 / 📱）
    - ブラウザ名 + OS（UserAgent パース）
    - IP アドレス
    - 最終アクセス日時
    - [このデバイス] バッジ（現在セッション）
    - [ログアウト] ボタン（他セッション） → DELETE /auth/sessions/{id}
  - [他の全デバイスからログアウト] ボタン → POST /auth/sessions/revoke-all

テンプレートに渡すモデル:
  title: String
  sessions: List<SessionInfo> (id, device, browser, os, ip, lastActive, isCurrent)
  csrfToken: String
```

#### error/error.jte
```html
内容:
  - エラーメッセージ（人間向け、技術用語なし）
  - 次のアクション（エラーコードに応じたボタン/リンク）
  - 管理者連絡先（該当する場合）

テンプレートに渡すモデル:
  title: String
  errorCode: String
  message: String — 人間向けメッセージ
  actions: List<Action> (label, url, style)
  showSupport: boolean
```

エラーコードと人間向けメッセージの対応は `dge/specs/ui-flow.md` のエラーリカバリーフロー参照。

### Bug 3: 管理画面ルート未実装

Main.java に以下のルートハンドラを追加:

```
GET /admin/members → admin/members.jte
GET /admin/invitations → admin/invitations.jte
```

#### admin/members.jte
```html
内容:
  - テナントメンバー一覧テーブル
    - 名前、メール、ロール、参加日
    - ロール変更ドロップダウン（VIEWER/MEMBER/ADMIN）
      → PATCH /api/v1/tenants/{tid}/members/{uid} (fetch)
    - [削除] ボタン（確認ダイアログ付き）
      → DELETE /api/v1/tenants/{tid}/members/{uid} (fetch)
  - ADMIN+ のみアクセス可

テンプレートに渡すモデル:
  title: String
  tenantId: String
  members: List<MemberInfo> (userId, email, displayName, role, joinedAt)
  currentUserRole: String
  csrfToken: String
```

#### admin/invitations.jte
```html
内容:
  - 招待一覧テーブル
    - ステータス: ⏳ 未使用 / ✅ 使用済み / ❌ 期限切れ
    - コード（マスク表示）、ロール、有効期限、作成者
    - [取消] ボタン（未使用のみ）
  - [招待を作成] フォーム
    - メール（任意）、ロール選択
    - → POST /api/v1/tenants/{tid}/invitations (fetch)
    - → 成功: 招待リンク + [コピー] ボタン

テンプレートに渡すモデル:
  title: String
  tenantId: String
  invitations: List<InvitationInfo> (id, code, email, role, status, expiresAt, createdBy)
  csrfToken: String
```

### Bug 4: volta.js（volta-sdk-js）が空

`src/main/resources/public/js/volta.js` を本実装に差し替え。

```javascript
// volta-sdk-js — ~150 行
(function(global) {
  "use strict";

  var _gatewayUrl = "";
  var _refreshing = null;
  var _sessionExpiredCb = null;

  // セッションリフレッシュ（排他制御付き）
  function refreshToken() {
    if (_refreshing) return _refreshing;
    _refreshing = fetch(_gatewayUrl + "/auth/refresh", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Accept": "application/json" }
    }).then(function(res) {
      if (!res.ok) throw new Error("refresh_failed");
      return res.json();
    }).finally(function() {
      _refreshing = null;
    });
    return _refreshing;
  }

  // 認証付き fetch
  function voltaFetch(url, options) {
    options = options || {};
    options.credentials = options.credentials || "same-origin";
    options.headers = options.headers || {};
    options.headers["Accept"] = options.headers["Accept"] || "application/json";
    options.headers["X-Requested-With"] = "XMLHttpRequest";

    return fetch(url, options).then(function(res) {
      if (res.status !== 401) return res;

      // 401 → リフレッシュ → リトライ（1回だけ）
      if (options._retried) {
        var returnTo = encodeURIComponent(window.location.href);
        if (_sessionExpiredCb) {
          _sessionExpiredCb();
        } else {
          window.location.href = _gatewayUrl + "/login?return_to=" + returnTo;
        }
        throw new Error("session_expired");
      }

      return refreshToken().then(function() {
        options._retried = true;
        return fetch(url, options);
      }).catch(function() {
        var returnTo = encodeURIComponent(window.location.href);
        if (_sessionExpiredCb) {
          _sessionExpiredCb();
        } else {
          window.location.href = _gatewayUrl + "/login?return_to=" + returnTo;
        }
        throw new Error("session_expired");
      });
    });
  }

  // テナント切替
  function switchTenant(tenantId) {
    return voltaFetch(_gatewayUrl + "/auth/switch-tenant", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tenantId: tenantId })
    }).then(function(res) {
      if (res.ok) window.location.reload();
      return res;
    });
  }

  // ログアウト
  function logout() {
    return fetch(_gatewayUrl + "/auth/logout", {
      method: "POST",
      credentials: "same-origin"
    }).then(function() {
      window.location.href = _gatewayUrl + "/login";
    });
  }

  // セッション情報
  function getSession() {
    return voltaFetch(_gatewayUrl + "/auth/refresh", {
      method: "POST"
    }).then(function(res) { return res.json(); });
  }

  // セッション削除
  function revokeSession(sessionId) {
    return voltaFetch(_gatewayUrl + "/auth/sessions/" + sessionId, {
      method: "DELETE"
    });
  }

  // 全セッション削除
  function revokeAllSessions() {
    return voltaFetch(_gatewayUrl + "/auth/sessions/revoke-all", {
      method: "POST"
    });
  }

  // 公開 API
  global.Volta = {
    init: function(opts) {
      _gatewayUrl = (opts && opts.gatewayUrl) || "";
    },
    fetch: voltaFetch,
    switchTenant: switchTenant,
    logout: logout,
    getSession: getSession,
    revokeSession: revokeSession,
    revokeAllSessions: revokeAllSessions,
    onSessionExpired: function(cb) { _sessionExpiredCb = cb; }
  };

})(window);
```

---

## 🟠 セキュリティ修正（致命的の次に対応）

### Sec 1: CSRF 保護

POST/DELETE エンドポイント（HTML フォーム経由）に CSRF トークンを追加。

```
方針:
  1. セッション作成時に CSRF トークンを生成（SecureRandom 32 bytes, base64url）
  2. sessions テーブルに csrf_token カラム追加（または別テーブル）
  3. jte テンプレートの全 form に <input type="hidden" name="_csrf" value="${csrfToken}">
  4. Javalin before handler で POST/DELETE の _csrf パラメータを検証
  5. JSON API（Accept: application/json or X-Requested-With: XMLHttpRequest）は CSRF 検証スキップ
     （SameSite=Lax + Content-Type チェックで保護）
```

### Sec 2: 招待一覧 API に権限チェック追加

```
GET /api/v1/tenants/{tid}/invitations → ADMIN+ のみ
現状: enforceTenantMatch のみ。MEMBER でも閲覧可能。
修正: enforceRole("ADMIN", "OWNER") を追加。
```

### Sec 3: メンバー一覧 API の権限確認

```
GET /api/v1/tenants/{tid}/members → MEMBER+ で閲覧可（設計上OK）
ただし、メンバーの email 等が見える場合は検討が必要。
→ 現状維持でOK。ただし response からセンシティブ情報を制限すること。
```

### Sec 4: 最後の OWNER 降格防御

```
PATCH /api/v1/tenants/{tid}/members/{uid}
修正:
  if (currentRole == "OWNER" && newRole != "OWNER") {
    int ownerCount = countByTenantAndRole(tid, "OWNER");
    if (ownerCount <= 1) {
      return 400 "LAST_OWNER_CANNOT_CHANGE"
        "テナントには最低 1 人の OWNER が必要です";
    }
  }
```

### Sec 5: Cache-Control ヘッダ追加

```
/callback, /auth/logout, /auth/verify の全レスポンスに:
  Cache-Control: no-store, no-cache, must-revalidate, private
  Pragma: no-cache
```

### Sec 6: Retry-After ヘッダ追加

```
429 レスポンスに:
  Retry-After: {秒数}
  X-RateLimit-Limit: {上限}
  X-RateLimit-Remaining: 0
```

### Sec 7: 招待取消 API 追加

```
DELETE /api/v1/tenants/{tid}/invitations/{iid}
  → ADMIN+ のみ
  → invitations テーブルから DELETE or status='cancelled' に更新
```

---

## 🟠 機能修正

### Func 1: テナント一覧取得 API 追加

```
GET /api/v1/users/me/tenants
  → 現在ユーザーが所属する全テナント一覧
  → レスポンス: { "data": [{ "id", "name", "slug", "role", "isLast" }] }
  → /select-tenant 画面で使用
```

### Func 2: デフォルト遷移先の修正

```
/callback 成功後、return_to がない場合:
  現状: /settings/sessions（不自然）
  修正: volta-config.yaml の最初の App URL、またはなければ /select-tenant
```

### Func 3: セッション切替時の旧セッション無効化

```
POST /auth/switch-tenant 成功時:
  1. 新セッション発行
  2. 旧セッションを invalidated_at = now() で無効化
  → セッション累積を防止
```

### Func 4: メンバー削除 API 追加

```
DELETE /api/v1/tenants/{tid}/members/{uid}
  → ADMIN+ のみ
  → membership.is_active = false に更新
  → 対象ユーザーの当該テナントのセッションを全無効化
```

### Func 5: sessions テーブルに return_to がない場合の対応確認

設計では sessions テーブルに `return_to VARCHAR(2048)` を追加済み。
V1__init.sql を確認し、含まれていることを確認。（確認済み: 含まれている）

---

## 実装順序（推奨）

```
1. Bug 1: 招待フロー修正（/callback のロジック）
2. Bug 4: volta.js 実装
3. Sec 1: CSRF 保護
4. Bug 2: テンプレート本実装（login → tenant-select → invite-consent → sessions → error）
5. Bug 3: 管理画面ルート + テンプレート（members, invitations）
6. Sec 2-7: セキュリティ修正
7. Func 1-4: 機能修正
8. 全体テスト
```

---

## 参照ファイル

- `dge/specs/implementation-all-phases.md` — 全体設計（DB, JWT, API, 環境変数, 設計原則）
- `dge/specs/ux-specs-phase1.md` — UI 仕様（画面一覧, エラー, SDK）
- `dge/specs/ui-flow.md` — 画面遷移図（mermaid）+ 全 Gap 一覧
- `dge/sessions/2026-03-31-identity-gateway-protocol-auto.md` — Protocol 仕様（ForwardAuth, Internal API）
