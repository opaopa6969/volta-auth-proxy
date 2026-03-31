# SameSite（Cookie 属性）

## これは何？

SameSite とは、リクエストが別のウェブサイトから来た場合にブラウザが Cookie を送信するかどうかを制御する Cookie の属性です。Cookie がサイト境界を越えて送信されるタイミングを制限することで、クロスサイト攻撃の防止に役立ちます。

3 つの値があります:

**`SameSite=Strict`** -- クロスサイトリクエストでは Cookie を**絶対に送らない**。`evil.com` にいて `your-bank.com` へのリンクをクリックしても、ブラウザは銀行の Cookie を含めません。アクティブなセッションがあっても、ログアウト状態に見えます。

**`SameSite=Lax`** -- トップレベルのナビゲーション（リンクのクリック、URL の直接入力）では Cookie を送るが、バックグラウンドのリクエスト（画像、iframe、AJAX コール）では送らない。多くのアプリケーションにとってちょうどいいバランスです。

**`SameSite=None`** -- 全てのクロスサイトリクエストで Cookie を送る。SameSite の保護を実質的に無効化します。`Secure` フラグ（HTTPS のみ）が必須。

```
  シナリオ: ユーザーが evil.com にいて、your-app.com にセッションがある

  evil.com にリンクがある: <a href="https://your-app.com/dashboard">

  ユーザーがリンクをクリック:
    Strict: Cookie を送らない。ログインページが表示される。
    Lax:    Cookie を送る。ダッシュボードが表示される。(トップレベルナビゲーション)
    None:   Cookie を送る。ダッシュボードが表示される。

  evil.com に画像がある: <img src="https://your-app.com/api/delete-account">

  ブラウザが画像を読み込む:
    Strict: Cookie を送らない。API がリクエストを拒否。
    Lax:    Cookie を送らない。API がリクエストを拒否。
    None:   Cookie を送る。アカウントが削除される！
```

## なぜ重要？

SameSite は CSRF（クロスサイトリクエストフォージェリ）攻撃に対する防御策です。CSRF 攻撃では、悪意のあるウェブサイトがユーザーのブラウザを騙して、ユーザーがログイン中の正規サイトにリクエストを送らせます。ブラウザは自動的にユーザーの Cookie を含めてしまいます。

典型的な CSRF 攻撃:

```
  evil.com のページに含まれる:
  <form action="https://your-bank.com/transfer" method="POST">
    <input name="to" value="attacker-account">
    <input name="amount" value="10000">
  </form>
  <script>document.forms[0].submit()</script>

  SameSite なし:
  ブラウザが your-bank.com にフォーム POST を送信
  ユーザーのセッション Cookie 付きで
  銀行はユーザーが送金を開始したと思う
  お金がなくなる

  SameSite=Lax または Strict の場合:
  ブラウザはクロスサイト POST で Cookie を送らない
  銀行は未認証のリクエストとして扱う
  送金は拒否される
```

SameSite が登場する前は、開発者は CSRF トークン（ランダムな値を持つ隠しフォームフィールド）を主な防御策として実装する必要がありました。SameSite はブラウザレベルの保護を自動的に提供し、CSRF トークンの実装にバグがあっても強力な防御層を追加します。

## どう動くの？

ブラウザはリクエストを開始したページの登録可能ドメインと、リクエスト先の登録可能ドメインを比較して、「同一サイト」か「クロスサイト」かを判定します。

- `app.example.com` と `api.example.com` は**同一サイト**（両方とも `example.com` の下）
- `example.com` と `evil.com` は**クロスサイト**（異なる登録可能ドメイン）

送信する各リクエストについて、ブラウザは以下を確認します:

```
  同一サイト? クロスサイト?
       |
       |--- 同一サイト: Cookie を送る（全ての SameSite 値）
       |
       |--- クロスサイト:
               |
               |--- SameSite=None: Cookie を送る
               |
               |--- SameSite=Lax:
               |       |
               |       |--- トップレベルナビゲーション（リンククリック、GET フォーム）?
               |       |       はい: Cookie を送る
               |       |       いいえ: Cookie を送らない
               |
               |--- SameSite=Strict: Cookie を送らない
```

### なぜ Strict ではなく Lax？

Strict の方が安全に聞こえますが、なぜ常に使わないのか？ Strict は一般的なユーザー体験を壊すからです:

- ユーザーがメールからあなたのアプリへのリンクをクリックした場合、Strict ではログアウト状態に見えます（Cookie が送られない）。サイト上で何かをクリックして初めて、2 回目のリクエストでログイン状態になります。
- Slack、Teams、その他の外部ツールからリンクをクリックした場合も同じ問題です。

Lax はトップレベルのナビゲーション（ユーザーが明示的にあなたのサイトに行くことを選んだ）では Cookie を許可しつつ、バックグラウンドリクエスト（ユーザーが開始していない）はブロックすることで、この問題を解決します。

注意が必要な点: OIDC のリダイレクトフロー。Google がユーザーをコールバック URL にリダイレクトで戻すとき、これは `accounts.google.com` からあなたのアプリへのトップレベルナビゲーションです。`SameSite=Lax` ではセッション Cookie がこのリダイレクトで送信されます。`SameSite=Strict` では送信されず、ログインフローが壊れます。

## volta-auth-proxy ではどう使っている？

volta-auth-proxy はセッション Cookie に `SameSite=Lax` を使います:

```
Set-Cookie: __volta_session=<UUID>; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax
```

**なぜ Lax？** volta はログインに Google との OIDC を使います。ログインフローはこうなります:

```
  1. ユーザーが volta のログインページ (your-domain.com) にいる
  2. Google (accounts.google.com) にリダイレクト
  3. ユーザーが Google で認証
  4. Google が volta にリダイレクトで戻す (/callback、your-domain.com)
  5. volta がセッションを作成、Cookie を設定
  6. volta が対象アプリにリダイレクト

  ステップ 4 はクロスサイトのトップレベルナビゲーション
  (accounts.google.com から your-domain.com)。

  SameSite=Lax の場合: ステップ 5 の Cookie は正常に動作
  （SET は your-domain.com 上で行われるため）。

  その後、外部サイトからアプリへのリンクを
  ユーザーがクリックする場合:
  SameSite=Lax: Cookie が送られる（トップレベルナビゲーション）-- 良い
  SameSite=Strict: Cookie が送られない -- ログアウト状態に見える
```

**CSRF 保護。** volta はフォーム送信に対して従来の CSRF トークン検証も実装しています。全てのサーバーサイドセッションにデータベースに保存された CSRF トークンがあります。HTML フォームはこのトークンを隠しフィールド `_csrf` として含み、volta は全ての POST、DELETE、PATCH リクエストでこれを検証します。この二重保護（SameSite + CSRF トークン）が多層防御を提供します。

**フラッシュ Cookie。** volta のフラッシュメッセージ Cookie（`__volta_flash`）も `SameSite=Lax` を使います:
```
Set-Cookie: __volta_flash=...; Path=/; Max-Age=20; SameSite=Lax
```

## よくある間違い

**1. 影響を理解せずに `SameSite=None` を使う。**
`None` は全てのクロスサイトリクエストで Cookie を送信するため、Cookie レベルの CSRF 保護を実質的に無効にします。本当にクロスサイトでの Cookie 送信が必要な場合（サードパーティの埋め込みウィジェットなど）のみ使い、常に `Secure` と一緒に使いましょう。

**2. OIDC フローで `SameSite=Strict` を使う。**
アプリが OIDC ログイン（Google/Okta 等へのリダイレクトと戻り）を使う場合、`Strict` はフローを壊す可能性があります。アプリへの戻りのリダイレクトがクロスサイトのナビゲーションだからです。`Lax` が正しい選択です。

**3. CSRF 保護を SameSite だけに頼る。**
SameSite は強力な防御ですが、エッジケースがあります:
- 古いブラウザは対応していない可能性がある
- `Lax` はクロスサイトからの GET リクエストを許可するため、GET エンドポイントで状態変更する操作を行うと脆弱
- 共有ドメインでのサブドメイン攻撃は SameSite を回避できる

volta のように、常に SameSite と従来の CSRF トークンを組み合わせて多層防御しましょう。

**4. SameSite を全く設定しない。**
現代のブラウザは SameSite 属性が未設定の場合 `Lax` をデフォルトにします。しかし、ブラウザのデフォルトに頼るのは脆弱です。常に明示的に設定しましょう。

**5. `SameSite=None` に `Secure` が必要なことを忘れる。**
ブラウザは `Secure` フラグのない `SameSite=None` Cookie を拒否します。Cookie は単に無視されます。`None` が必要な場合、HTTPS も使う必要があります。

**6. 「同一サイト」と「同一オリジン」を混同する。**
同一オリジンの方が厳格です: `app.example.com:443` と `api.example.com:443` は異なるオリジンですが同一サイトです。SameSite はより緩い「同一サイト」の定義（登録可能ドメインに基づく）を使い、CORS はより厳格な「同一オリジン」の定義を使います。
