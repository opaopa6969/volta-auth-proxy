# Guard（ガード）

[English version](guard.md)

---

## これは何？

ガードとは、[状態マシン](state-machine.ja.md)の[遷移](transition.ja.md)が実行されるために真でなければならない真偽条件です。トリガーイベントが到着すると、システムがガード式を評価します。ガードが真なら遷移が進行し、偽なら遷移がブロックされ、次に一致する遷移を試すかエラーを返します。

クラブの入り口のバウンサーのようなものです。ドア（トリガー）は誰にでもありますが、バウンサー（ガード）が入る前にIDをチェックします。「ゲストリストに載っていて、かつ21歳以上か？」-- これがガードです。両方の条件が満たされなければドアは開きません。

volta-auth-proxyでは、ガードは`dsl/auth-machine.yaml`内にCELライクな真偽式として記述されます。`session.valid`、`tenant.active`、`membership.role`のような型付きコンテキスト変数を参照します。

---

## なぜ重要なのか？

ガードがなければ、あらゆるトリガーがあらゆる遷移を引き起こします。有効なOIDCフローなしに`/callback`にアクセスしてUNAUTHENTICATEDから直接AUTHENTICATEDに遷移できてしまいます。ガードは状態マシンのセキュリティチェックポイントです。

- **セキュリティ**：ガードが不正な状態遷移を防ぐ（例：ADMINロールなしに管理ページへアクセス）
- **正確性**：前提条件が満たされた場合にのみ遷移が起こることを保証
- **曖昧さの解消**：複数の遷移が同じトリガーを共有する場合、ガードがどれを実行するか決定
- **可読性**：ガード式は宣言的で、コードを追跡せずに必要な条件を読み取れる

---

## どう動くのか？

### ガード式の構文

voltaはCELライク（Common Expression Language）な構文を使用します：

```
  演算子:    &&  ||  !  ==  !=  >  <  >=  <=  in
  変数:      session.valid, tenant.active, membership.role 等
  リテラル:  true, false, 整数, "文字列", ['配列']
  評価:      短絡評価、左から右
```

### 例

```yaml
# 単純な真偽値
guard: "session.valid"

# 複合条件
guard: "session.valid && tenant.active"

# 否定
guard: "!request.accept_json"

# 'in'演算子でロールチェック
guard: "membership.role in ['ADMIN', 'OWNER']"

# 複雑な複数条件
guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"

# 招待チェック
guard: "invite.valid && !invite.expired && !invite.used && invite.email_match"
```

### コンテキスト変数

ガードは`auth-machine.yaml`の`context`セクションで定義された型付き変数を参照します：

```
  ┌─────────────────────────────────────────────┐
  │ context:                                     │
  │   session:                                   │
  │     valid: bool                              │
  │     expired: bool                            │
  │     tenant_id: uuid?                         │
  │   user:                                      │
  │     exists: bool                             │
  │     active: bool                             │
  │     tenant_count: int                        │
  │   membership:                                │
  │     exists: bool                             │
  │     role: enum[OWNER, ADMIN, MEMBER, VIEWER] │
  │   tenant:                                    │
  │     active: bool                             │
  │     suspended: bool                          │
  │   invite:                                    │
  │     valid: bool                              │
  │     expired: bool                            │
  │     email_match: bool                        │
  │   oidc_flow:                                 │
  │     state_valid: bool                        │
  │     nonce_valid: bool                        │
  │   request:                                   │
  │     accept_json: bool                        │
  └─────────────────────────────────────────────┘
```

### ガードの評価順序（priority）

複数の遷移が同じトリガーを共有する場合、ガードは`priority`順に評価されます：

```yaml
# AUTH_PENDINGのGET /callbackに対する遷移
callback_error:
  trigger: "GET /callback"
  guard: "oidc_flow.has_error_param"
  priority: 1                          # 最初にチェック

callback_state_invalid:
  trigger: "GET /callback"
  guard: "!oidc_flow.state_valid"
  priority: 2                          # 2番目にチェック

callback_nonce_invalid:
  trigger: "GET /callback"
  guard: "oidc_flow.state_valid && !oidc_flow.nonce_valid"
  priority: 3                          # 3番目にチェック

callback_success:
  trigger: "GET /callback"
  guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"
  priority: 5                          # 最後にチェック
```

最初にtrueと評価されたガードが勝ちます。エラーチェックが成功パスより低いpriority番号（先にチェック）を持つのはこのためです。

### テンプレート式 vs ガード式

DSLには重要な区別があります：

```
  ガード式：                テンプレート式：
  ┌──────────────────────┐ ┌──────────────────────────────────┐
  │ guard: "a || b"      │ │ target: "{x || y}"               │
  │ "||" = 論理OR        │ │ "||" = COALESCE（フォールバック） │
  │ 結果: true/false     │ │ 結果: xの値、nullならyの値       │
  └──────────────────────┘ └──────────────────────────────────┘
```

---

## volta-auth-proxyではどう使われているか？

### ForwardAuthのガード

システム中で最も重要なガードはForwardAuthエンドポイントを保護します：

```yaml
# AUTHENTICATED状態
forward_auth:
  trigger: "GET /auth/verify"
  guard: "session.valid && tenant.active"
  priority: 4
  actions:
    - { type: side_effect, action: touch_session }
    - { type: http, action: return_volta_headers }
  next: AUTHENTICATED
```

このガードにより、下流アプリに転送されるすべてのリクエストが有効なセッションとアクティブなテナントを持つことが保証されます。どちらかが失敗した場合、より低いpriorityの遷移がエラーケース（401、403）を処理します。

### 招待承認のガード

招待フローは複数のガードを使ってすべてのエッジケースを処理します：

```yaml
accept_already_member:
  guard: "membership.exists"           # すでにメンバー？ → エラー
  priority: 1

accept_expired:
  guard: "invite.expired"              # 期限切れ？ → 期限切れページ
  priority: 2

accept_email_mismatch:
  guard: "!invite.email_match"         # メールが違う？ → エラー
  priority: 3

accept:
  guard: "invite.valid && !invite.expired && !invite.used && invite.email_match"
  priority: 4                          # すべてパス → 承認
```

### コンテンツネゴシエーションのガード

voltaはレスポンス形式を決定するためにガードを使います：

```yaml
login_browser:
  trigger: "GET /login"
  guard: "!request.accept_json"        # ブラウザ → Googleにリダイレクト
  next: AUTH_PENDING

login_api:
  trigger: "GET /login"
  guard: "request.accept_json"         # API/SPA → JSONエラーを返す
  next: UNAUTHENTICATED
```

---

## よくある間違いと攻撃

### 間違い1：priorityなしでガードが重複

2つのガードが同時に真になり得るのにpriorityがなければ、結果は曖昧です。voltaは同じトリガーを持つすべての遷移に明示的な`priority`値を要求します。

### 間違い2：決して真にならないガード

`"session.valid && session.expired"`のようなガードは矛盾であり、決して真になりません。デッド遷移を作り、スペースを無駄にし、読者を混乱させます。

### 間違い3：否定ケースの忘れ

成功パスのガードはあるが失敗パスを忘れると、ガードに失敗したユーザーはレスポンスを受け取れません。voltaはすべてのガード結果（成功、期限切れ、無効、ミスマッチ等）に対して遷移を定義してこれに対処します。

### 攻撃：パラメータ操作によるガードバイパス

攻撃者はリクエストパラメータを操作して`oidc_flow.state_valid`をtrueにしようとするかもしれません。voltaのガードはサーバーサイドのコンテキスト（データベース検索、セッション状態）を参照し、生のリクエストパラメータは参照しません。コンテキストはガード評価前にJavaコードで計算されます。

---

## さらに学ぶために

- [transition.md](transition.md) -- ガードは遷移の一部。
- [state-machine.md](state-machine.md) -- ガードを評価するマシン。
- [dsl.md](dsl.md) -- ガードが定義される場所。
- [invariant.md](invariant.md) -- 遷移ごとのガードを補完するグローバルルール。
- [nonce.md](nonce.md) -- `oidc_flow.nonce_valid`でチェックされるnonce。
- [state.md](state.md) -- `oidc_flow.state_valid`でチェックされるOIDC state。
- [csrf.md](csrf.md) -- `guard_check`アクションで検証されるCSRFトークン。
