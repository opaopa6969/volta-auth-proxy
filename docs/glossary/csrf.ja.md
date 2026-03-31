# CSRF (Cross-Site Request Forgery)

[English version](csrf.md)

---

## これは何？

Cross-Site Request Forgery（CSRF、「シーサーフ」と読むこともある）は、悪意のあるウェブサイトがあなたのブラウザを騙して、あなたがすでにログインしている別のウェブサイト上でアクションを実行させる攻撃です。悪意のあるウェブサイトはレスポンスを読めません。ただリクエストを発射するだけです。しかしそのリクエストが送金やパスワード変更であれば、被害は発生します。

誰かがあなたの署名を文書に偽造するようなものです。あなたはその文書を書いていないし、同意もしていない。でも署名があなたのもの（あなたのブラウザがCookie付きでリクエストを送っている）に見えるので、銀行はそれを処理してしまいます。

---

## なぜ重要なのか？

CSRF対策がないと、あなたが訪問するどのウェブサイトでも以下のことが可能になります：

- 銀行口座から送金する
- サービス上のメールアドレスを変更する
- アプリケーションに管理者アカウントを作成する
- あなたの代わりに招待を承諾する
- チーム内のあなたのロールを変更する

怖いのは、ユーザーは何も悪いことをしていないということです。怪しいリンクをクリックしたわけでもありません。普通に見えるウェブサイト（または見えないiframeを含むサイト）を訪問しただけで、バックグラウンドで攻撃が静かに実行されます。

---

## どう動くのか？

### 実際の攻撃例：銀行送金

あなたが`https://bank.example.com`の銀行にログインしているとします。銀行はCookieでセッションを追跡しています（すべての銀行がそうしています）。

```
  ステップ1：銀行にログイン。ブラウザがセッションCookieを保存。

    ブラウザのCookie保管庫：
    ┌────────────────────────────────────────┐
    │  bank.example.com: session=abc123xyz   │
    └────────────────────────────────────────┘

  ステップ2：まだログイン中に、別のウェブサイトを訪問
          （例えば猫の写真サイト：evil-cats.example.com）

  ステップ3：そのウェブサイトにはこんな隠しHTMLがある：

    <form action="https://bank.example.com/transfer" method="POST"
          style="display:none">
      <input name="to" value="attacker-account-999">
      <input name="amount" value="10000">
    </form>
    <script>document.forms[0].submit();</script>

  ステップ4：ブラウザが自動的に：
    a) bank.example.com/transferへのPOSTリクエストを作成
    b) 銀行のセッションCookieを付与（リクエストの宛先が
       bank.example.comなので、ブラウザはそのCookieを送る）
    c) フォームデータを送信（to=攻撃者、amount=10000）

  ステップ5：銀行がリクエストを受信。正当に見える：
    - 有効なセッションCookie ✓
    - 有効なフォームデータ ✓
    - evil-cats.example.comから来たとは判別できない

  ステップ6：10,000ドルが攻撃者に送金される。
```

重要なポイント：**ブラウザはどのウェブサイトがリクエストを開始したかに関係なく、そのドメインへのすべてのリクエストにCookieを自動的に付与します。** これはCookieの設計通りの動作です。バグではなく機能です。CSRFはこの機能を悪用します。

### トークンベースのCSRF保護の仕組み

防御はシンプルです：あなたのウェブサイトだけが知る秘密のトークンをすべてのフォームに含めます。

```
  ステップ1：voltaがHTMLフォームを描画する際、隠しトークンを含める：

    <form action="/admin/members/change-role" method="POST">
      <input type="hidden" name="_csrf" value="Kj8mX2pQ...ランダム...">
      <select name="role">...</select>
      <button>ロール変更</button>
    </form>

    このトークンはユーザーのセッションに固有で、
    サーバー側のsessionsテーブルに保存される。

  ステップ2：フォーム送信時にサーバーがチェック：

    a) _csrfトークンが存在するか？ ─── いいえ ──► 403 Forbidden
    b) セッションのCSRFトークンと一致するか？ ─── いいえ ──► 403 Forbidden
    c) 両方はい？ ──► リクエストを処理

  なぜ攻撃者は勝てないか：

    攻撃者の悪意あるページ：
    ┌────────────────────────────────────────────┐
    │  <form action="https://volta.example.com/  │
    │        admin/members/change-role">          │
    │    <input name="role" value="OWNER">        │
    │    <input name="_csrf" value="???">          │
    │  </form>                                    │
    │                                             │
    │  攻撃者はCSRFトークンを知らない。             │
    │  voltaのページを読めない（同一オリジン         │
    │  ポリシーがクロスオリジンの読み取りを防ぐ）。   │
    │  フォーム送信は拒否される。                    │
    └────────────────────────────────────────────┘
```

### SameSite Cookieによる保護

最新のブラウザは`SameSite`というCookie属性をサポートしており、クロスオリジンリクエスト時にCookieが送られるかどうかを制御します：

| SameSite値 | 動作 |
|-----------|------|
| `Strict` | クロスオリジンリクエストではCookieが**絶対に**送られない。最も安全だが、正当なクロスサイトナビゲーション（メールからのリンククリックなど）が壊れる。 |
| `Lax`（最新ブラウザのデフォルト） | トップレベルナビゲーション（リンククリック）ではCookieが送られるが、POSTリクエスト、iframe、他サイトからのAJAXでは送られない。ほとんどのCSRF攻撃をブロック。 |
| `None` | Cookieが常に送られる。`Secure`フラグとの併用が必須。古い動作。 |

```
  SameSite=Laxが防ぐもの：

  evil-cats.example.com                 volta.example.com
  ┌──────────────────────┐              ┌──────────────────┐
  │  <form method="POST" │              │                  │
  │   action="volta..."> │              │  Cookieが送られ   │
  │  </form>             │──── POST ───►│  ない（ブロック！）│
  │  <script>submit()    │              │                  │
  └──────────────────────┘              └──────────────────┘

  ただし許可されるもの：

  リンク付きメール                       volta.example.com
  ┌──────────────────────┐              ┌──────────────────┐
  │  ダッシュボードを      │              │                  │
  │  見るにはここをクリック │── GETリンク ─►│  Cookieが送られる│
  │                      │              │  (トップレベルnav) │
  └──────────────────────┘              └──────────────────┘
```

---

## voltaでの具体的な実装

voltaは多層的なCSRF防御戦略を使用しています：

### レイヤー1：SameSite=LaxのセッションCookie

`__volta_session` Cookieは`SameSite=Lax`で設定されており、他サイトからのクロスオリジンPOSTリクエストをブロックします。

### レイヤー2：HTMLフォーム用のトークンベースCSRF

従来のHTMLフォーム送信（POST、PATCH、DELETE）に対して、voltaはセッションレコードに保存されたCSRFトークンを生成し、すべてのフォーム送信で要求します：

```
  Main.java - CSRFミドルウェア：

  1. チェック：POST/PATCH/DELETEリクエストか？
     ├── いいえ → CSRFチェックをスキップ
     └── はい   → 続行

  2. チェック：JSON/XHRリクエストか？
     ├── はい → スキップ（JSON + SameSiteで十分）
     └── いいえ → 続行（HTMLフォームである）

  3. チェック：セッションCookieがあるか？
     ├── いいえ → 403 "CSRFトークンが無効です"
     └── はい   → 続行

  4. チェック：_csrfパラメータがセッションのトークンと一致するか？
     ├── いいえ → 403 "CSRFトークンが無効です"
     └── はい   → リクエストを処理
```

### レイヤー3：JSON APIの免除

voltaのJSON APIエンドポイント（`fetch()`や`XMLHttpRequest`経由で呼ばれる）は従来のCSRFトークンが不要です。理由：

1. **SameSite=Lax** がCookie付きのクロスオリジンPOSTリクエストをブロック
2. **Content-Type: application/json** -- ブラウザはHTMLフォーム経由でJSONを送信できない（フォームは`application/x-www-form-urlencoded`か`multipart/form-data`のみ）。攻撃者のフォームは`Content-Type: application/json`を設定できない。
3. **CORS** -- ブラウザがXHR/fetchに同一オリジンポリシーを適用。資格情報付きのクロスオリジン`fetch()`にはサーバーからの明示的なCORSヘッダーが必要。

この組み合わせにより：`Content-Type: application/json`と有効なセッションCookieを持つリクエストが来たら、同一オリジン（またはCORS承認済みオリジン）から発信されたものでなければなりません。

### レイヤー4：OIDCのstateパラメータ

Googleログインフロー中、`state`パラメータがCSRFトークンとして機能します。これがないと、攻撃者は：

1. 自分のGoogleアカウントでログインフローを開始
2. コールバックURL（認可コード付き）をキャプチャ
3. 被害者をそのコールバックURLに訪問させる
4. 被害者が攻撃者としてログインしてしまう（ログインCSRF）

voltaは暗号学的に安全なstateを生成し、データベースに保存し、Googleがリダイレクトバックしたときに検証します。詳細は[oidc.md](oidc.md)を参照。

---

## よくある間違いと攻撃

### 間違い1：一部のエンドポイントだけを保護する

CSRF保護はすべての状態変更エンドポイントをカバーしなければなりません。`/transfer`を保護して`/change-email`を忘れたら、攻撃者はメールを変更し、パスワードをリセットし、アカウントを乗っ取ります。

### 間違い2：GETを状態変更操作に使う

```
  悪い例：GET /admin/members/delete?id=user-123
          （<img>タグやリンククリックでトリガーできる）

  良い例：DELETE /api/v1/tenants/{tid}/members/{uid}
          （適切なメソッドのフォームまたはfetchが必要）
```

### 間違い3：CSRFトークンをCookieだけに入れる

CSRFトークンをCookieだけに入れると、攻撃は依然として成功します。ブラウザはCookieを自動的に送るからです。トークンはフォームボディまたはカスタムヘッダーに含める必要があり、攻撃者は別のオリジンからこれらを設定できません。

### 間違い4：CSRFトークンを再生成しない

CSRFトークンが変わらなければ、攻撃者が1つ見つけた場合（キャッシュされたページなどから）再利用できます。voltaはCSRFトークンをセッションに紐づけ、セッションはログイン時に再生成されます（セッション固定防止）。

### 攻撃：ログインCSRF

これは見落とされがちな亜種です。攻撃者はあなたの既存のセッションを攻撃するのではなく、攻撃者が制御するセッションにあなたを強制します：

```
  1. 攻撃者が自分のアカウントでGoogle OIDCログインを開始
  2. 攻撃者がコールバックURLを取得：/callback?code=攻撃者のコード&state=...
  3. 攻撃者が被害者をこのURLに誘導
  4. state検証がないと：被害者が攻撃者としてログインしてしまう
  5. 被害者が自分のアカウントだと思って機密ファイルをアップロード
  6. 攻撃者がログインしてファイルを読む
```

voltaのstateパラメータはこれを完全に防ぎます。

---

## さらに学ぶために

- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) -- 包括的な防御ガイド。
- [SameSite Cookies Explained](https://web.dev/samesite-cookies-explained/) -- GoogleによるSameSiteガイド。
- [oidc.md](oidc.md) -- stateパラメータがログインCSRFを防ぐ方法。
- [cookie.md](cookie.md) -- SameSiteを含むCookieの属性。
- [session.md](session.md) -- voltaがセッションとCSRFトークンをどう管理するか。
