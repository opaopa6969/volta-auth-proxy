# Transition（遷移）

[English version](transition.md)

---

## これは何？

遷移とは、[状態マシン](state-machine.ja.md)において1つの状態から別の状態への移動のことです。イベント（通常はHTTPリクエスト）によってトリガーされ、[ガード](guard.ja.md)条件で制御され、途中でアクションを実行することがあります。遷移はvoltaの認証システムにおける振る舞いの基本単位です：「これが起きたとき、もしこの条件が成り立つなら、これらのことを行い、あの状態に移る。」

国境を越えるようなものです。国境通過（遷移）は検問所（トリガー）で行われます。国境警備員（ガード条件）がパスポートをチェックします。承認されれば税関（アクション）を通り、新しい国（次の状態）に到着します。こっそり越えることはできません。すべての通過は記録され管理されています。

volta-auth-proxyでは、すべての遷移が`dsl/auth-machine.yaml`で明示的に定義されています。8状態にわたり約30以上の遷移があり、さらに複数の状態から適用されるグローバル遷移があります。

---

## なぜ重要なのか？

遷移は認証システムで実際に物事が起こる場所です。ログイン、ログアウト、コールバック処理、テナント切り替え、セッション無効化 -- これらはすべて遷移です。遷移が欠けていたり設定が間違っていると：

- **ユーザーが詰む**：現在の状態から必要な場所へのパスがない
- **セキュリティホールが現れる**：ガードのない遷移が未認可のユーザーを通す
- **エッジケースが壊れる**：メールのミスマッチをチェックしない招待承認
- **デバッグが不可能**：名前付き遷移がなければ、エラーは単に「何かがうまくいかなかった」

---

## どう動くのか？

### 遷移の構造

```
  遷移 = トリガー + ガード + アクション + 次の状態
  ┌──────────────────────────────────────────────────────┐
  │ login_browser:                                        │
  │   trigger: "GET /login"        ← 何のイベント？       │
  │   guard: "!request.accept_json" ← 何の条件？         │
  │   actions:                      ← 何の副作用？       │
  │     - create_oidc_flow                               │
  │     - Googleにリダイレクト                             │
  │   next: AUTH_PENDING            ← どこに行く？        │
  └──────────────────────────────────────────────────────┘
```

### トリガーの種類

```
  HTTPトリガー：
  ┌──────────────────────────────────────┐
  │ "GET /login"                         │
  │ "POST /auth/logout"                  │
  │ "GET /callback"                      │
  │ "GET /invite/{code}"                 │
  │ "POST /auth/switch-tenant"           │
  │ "DELETE /auth/sessions/{id}"         │
  └──────────────────────────────────────┘

  自動トリガー：
  ┌──────────────────────────────────────┐
  │ trigger: automatic                   │
  │ （すべてのリクエストで評価）           │
  │ 例: session_timeout                  │
  └──────────────────────────────────────┘
```

### アクションの種類

各遷移は複数のアクションを持つことができ、順番に実行されます：

| 種類 | 目的 | 例 |
|------|------|-----|
| `side_effect` | DB書き込み、状態の変更 | `create_session`、`invalidate_session` |
| `http` | HTTPレスポンス（終端） | `redirect`、`json_ok`、`json_error`、`render_html` |
| `audit` | 監査ログエントリ | `event: LOGIN_SUCCESS` |
| `guard_check` | 失敗しうる検証 | `csrf_token_valid` |

### アクションのマージ戦略

遷移がトップレベルのアクションと条件分岐（`next_if`）の両方を持つ場合、マージ戦略は`append`です：

```yaml
callback_success:
  trigger: "GET /callback"
  guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"
  actions:                              # これらが最初に実行：
    - { type: side_effect, action: upsert_user }
    - { type: side_effect, action: delete_oidc_flow }
    - { type: side_effect, action: create_session }
    - { type: audit, event: LOGIN_SUCCESS }
  next_if:                              # 次に分岐固有のアクション：
    - guard: "invite.present && invite.valid"
      next: INVITE_CONSENT
      actions:
        - { type: http, action: redirect, target: "/invite/{invite.code}/accept" }

    - guard: "user.tenant_count == 1"
      next: AUTHENTICATED
      actions:
        - { type: side_effect, action: auto_select_tenant }
        - { type: http, action: redirect, target: "{request.return_to || config.default_app_url}" }
```

実行順序：`upsert_user` -> `delete_oidc_flow` -> `create_session` -> `audit` -> （分岐アクション）。トップレベルのアクションが失敗すると分岐アクションは実行されません。

### 自己遷移

遷移は同じ状態に戻ることもできます：

```yaml
# AUTHENTICATED → AUTHENTICATED（同じ状態に留まる）
forward_auth:
  trigger: "GET /auth/verify"
  guard: "session.valid && tenant.active"
  next: AUTHENTICATED
```

---

## volta-auth-proxyではどう使われているか？

### 遷移としての完全なログインフロー

```
  UNAUTHENTICATED                AUTH_PENDING
  ┌──────────────┐              ┌──────────────┐
  │              ─┼─ login ────►│              ─┼─ callback_success ──►
  │              ─┼─ login_api  │              ─┼─ callback_error ────►
  │   (自己遷移)  │             │              ─┼─ callback_state_inv ►
  └──────────────┘              │              ─┼─ callback_nonce_inv ►
                                │   (タイムアウト)│
                                └──────────────┘

  callback_success後、next_ifで分岐：
  ├── INVITE_CONSENT  （招待がある場合）
  ├── AUTHENTICATED   （テナント1つの場合）
  ├── TENANT_SELECT   （複数テナントの場合）
  └── NO_TENANT       （テナント0、招待なしの場合）
```

### グローバル遷移

これらの遷移は複数の状態にわたって適用されます：

```yaml
global_transitions:
  logout_browser:
    trigger: "POST /auth/logout"
    guard: "!request.accept_json"
    from_except: [UNAUTHENTICATED, AUTH_PENDING]
    actions:
      - { type: side_effect, action: invalidate_session }
      - { type: side_effect, action: clear_cookie }
      - { type: audit, event: LOGOUT }
      - { type: http, action: redirect, target: "/login" }
    next: UNAUTHENTICATED
```

`from_except`フィールドは「これらの状態を除くすべての状態からこの遷移が適用される」を意味します。ログアウトはAUTHENTICATED、TENANT_SELECT、NO_TENANT、INVITE_CONSENT、TENANT_SUSPENDEDから機能します。

### 遷移の一覧

| 起点 | 遷移 | 終点 | トリガー |
|------|------|------|---------|
| UNAUTHENTICATED | login_browser | AUTH_PENDING | GET /login |
| UNAUTHENTICATED | login_api | UNAUTHENTICATED | GET /login |
| AUTH_PENDING | callback_success | （分岐） | GET /callback |
| AUTH_PENDING | callback_error | UNAUTHENTICATED | GET /callback |
| INVITE_CONSENT | accept | AUTHENTICATED | POST /invite/{code}/accept |
| TENANT_SELECT | select | AUTHENTICATED | POST /auth/switch-tenant |
| AUTHENTICATED | forward_auth | AUTHENTICATED | GET /auth/verify |
| AUTHENTICATED | switch_tenant | AUTHENTICATED | POST /auth/switch-tenant |
| AUTHENTICATED | revoke_session | AUTHENTICATED | DELETE /auth/sessions/{id} |
| （グローバル） | logout | UNAUTHENTICATED | POST /auth/logout |
| （グローバル） | session_timeout | UNAUTHENTICATED | 自動 |

---

## よくある間違いと攻撃

### 間違い1：エラー遷移の欠落

`callback_success`は定義したが`callback_error`を忘れると、OIDCプロバイダーがエラーを返したユーザーがレスポンスを受け取れません。voltaはすべてのトリガーに対してあり得るすべての結果の遷移を定義します。

### 間違い2：終端HTTPレスポンス後のアクション

`http`アクションは終端で、レスポンスを送信します。その後に記載されたアクションは実行されません。voltaの慣習ではアクションリストの最後に`http`アクションを配置します。

### 間違い3：出口のない循環遷移

状態Aが状態Bに遷移し、状態Bが状態Aにしか遷移しなければ、ユーザーは閉じ込められます。`no_deadlock`[不変条件](invariant.ja.md)がこれを捕捉します：すべての状態にはAUTHENTICATEDかUNAUTHENTICATEDへのパスが必要です。

### 攻撃：遷移のリプレイ

攻撃者が有効な`GET /callback?code=...&state=...`のURLをキャプチャしてリプレイします。voltaは最初の使用後にOIDCフローレコードを削除（`delete_oidc_flow`アクション）して防ぎます。2回目のリプレイでは一致するフローが見つからず失敗します。

---

## さらに学ぶために

- [state-machine.md](state-machine.md) -- 遷移が動作するマシン。
- [guard.md](guard.md) -- 遷移が実行されるかを制御する条件。
- [dsl.md](dsl.md) -- 遷移が定義される場所。
- [invariant.md](invariant.md) -- すべての遷移が満たすべきルール。
- [forwardauth.md](forwardauth.md) -- ForwardAuth遷移の詳細。
- [invitation-flow.md](invitation-flow.md) -- 招待関連の遷移。
