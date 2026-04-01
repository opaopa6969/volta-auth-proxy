# ログイン

[English version](login.md)

---

## 一言で言うと？

ログインとは、自分が誰かをシステムに証明し、自分のデータにアクセスする許可を得る行為です。

---

## 受付で身分証を見せる

ログインはホテルに到着するようなものです：

1. **フロントに行く** -- [ブラウザ](browser.ja.md)でサイトを開く
2. **身分証を見せる** -- [認証情報](credentials.ja.md)（パスワード、Google アカウントなど）を提供する
3. **受付がシステムを確認** -- [サーバー](server.ja.md)が本人確認する
4. **部屋の鍵をもらう** -- サーバーが[セッション](session.ja.md) [Cookie](cookie.ja.md) を発行する
5. **鍵で部屋が開く** -- Cookie が以降のすべてのリクエストでアカウントへのアクセスを許可する

ステップ2がなければ、誰でもどの部屋にも入れてしまいます。ログインが存在する理由です。

---

## なぜ必要なの？

ログインがなければ：

- 誰でも他人のデータを見られる
- 誰がどの操作をしたか分からない（監査証跡が消える）
- [RBAC](authentication-vs-authorization.ja.md) ロール（OWNER, ADMIN, MEMBER, VIEWER）が無意味になる
- ユーザー招待や設定変更などの機密操作が誰にでもできてしまう
- マルチテナントの分離が崩壊 -- テナント A がテナント B のデータを見られる

ログインは、すべてのセキュリティモデルの正門です。

---

## volta-auth-proxy でのログイン

volta はパスワードを自分で扱いません。[認証](authentication-vs-authorization.ja.md)は Google に [OIDC](oidc.ja.md) で委譲します。理由は：

- **パスワード保存リスクなし** -- volta はパスワードを見ることも保存することもない
- **Google が MFA、不審なログイン検知、アカウント復旧を処理**
- **攻撃対象が1つ減る** -- volta のログインへのブルートフォース攻撃がない

volta のログインフロー：

```
  ブラウザ                   volta-auth-proxy              Google
  ──────                    ──────────────────            ──────
  1. /auth/login にアクセス ──>
                             2. state + nonce 生成
                             3. Google にリダイレクト ──>
                                                          4. ユーザーがサインイン
                                                          5. Google がリダイレクトバック
                             <────── 認可コード付き
                             6. コードをトークンと交換
                             7. id_token を検証（OIDC）
                             8. ユーザーレコードを作成/更新
                             9. セッション作成
                             10. __volta_session Cookie 設定
  <────── アプリにリダイレクト
  11. ログイン完了！
```

ログイン時の主なセキュリティ対策：

- **State パラメータ** -- ログインフローへの [CSRF](csrf.ja.md) 攻撃を防止
- **Nonce** -- トークンリプレイ攻撃を防止
- **[PKCE](pkce.ja.md)** -- 認可コード傍受を防止
- **[リダイレクト URI](redirect-uri.ja.md) 検証** -- [オープンリダイレクト](open-redirect.ja.md)攻撃を防止

---

## 具体的な例

volta で保護されたアプリにログインする流れ：

1. ユーザーが `https://app.acme.example.com/dashboard` にアクセス
2. [リバースプロキシ](reverse-proxy.ja.md)が [ForwardAuth](forwardauth.ja.md) で volta に確認 -- 有効な[セッション](session.ja.md)なし
3. volta が HTTP 302 [リダイレクト](redirect.ja.md)で `/auth/login?redirect_to=/dashboard` に応答
4. volta がランダムな `state` 値を生成し、一時的な [Cookie](cookie.ja.md) に保存
5. volta が[ブラウザ](browser.ja.md)を `https://accounts.google.com/o/oauth2/v2/auth?client_id=...&state=...&nonce=...` に[リダイレクト](redirect.ja.md)
6. ユーザーに Google の「サインイン」ページが表示され、メールとパスワードを入力
7. Google がユーザーを検証し、`https://auth.acme.example.com/auth/callback?code=abc123&state=...` に[リダイレクト](redirect.ja.md)
8. volta が `state` の一致を確認し、`code` を Google とトークンに交換
9. volta が `id_token` からユーザーのメールと名前を読み取る
10. volta がセッションを作成し、`__volta_session` Cookie を設定
11. volta がユーザーを `/dashboard` にリダイレクト -- 認証完了

---

## さらに学ぶために

- [ログアウト](logout.ja.md) -- ログインの逆：セッションを終了する
- [セッション](session.ja.md) -- ログイン時に作成されるもの
- [OIDC](oidc.ja.md) -- volta が Google ログインに使うプロトコル
- [認証情報](credentials.ja.md) -- 提供する本人確認の証拠
- [PKCE](pkce.ja.md) -- ログインフローの追加セキュリティ
- [リダイレクト](redirect.ja.md) -- volta と Google の間でブラウザが移動する仕組み
