# リダイレクト

[English version](redirect.md)

---

## 一言で言うと？

リダイレクトとは、[サーバー](server.ja.md)が[ブラウザ](browser.ja.md)に「探しているものはここにないよ -- こっちの URL に行って」と伝え、ブラウザが自動的に従う仕組みです。

---

## 郵便受けの転送届

新しい家に引っ越したけど、友達はまだ古い住所に手紙を送ってきます。そこで古い郵便受けにメモを貼ります：「456番地に引っ越しました」。郵便配達員がメモを見て、手紙を新しい住所に届けてくれます -- 友達は何もしなくていい。

| 郵便 | ウェブ |
|---|---|
| 郵便受けのメモ | HTTP 302 レスポンスの `Location` ヘッダー |
| 配達員がメモを読む | [ブラウザ](browser.ja.md)が `Location` ヘッダーを読む |
| 手紙が新しい住所に届く | ブラウザが新しい URL にリクエストを送る |
| 友達は引っ越しを知らない | ユーザーは最終ページを見て、リダイレクトに気づかないことも |

リダイレクトでよく使う [HTTP ステータスコード](http-status-codes.ja.md)：

| コード | 意味 | 使うタイミング |
|---|---|---|
| 301 | 恒久的に移動 | 旧 URL は永久になくなった |
| 302 | 発見（一時的） | 今はこちらへ、ただし旧 URL は復活するかも |
| 303 | 他を参照 | POST の後、GET でこの URL を見て |
| 307 | 一時リダイレクト | 302 と同じだが HTTP メソッドを維持 |

---

## なぜ必要なの？

リダイレクトがなければ：

- [ログイン](login.ja.md)フローが動かない -- ユーザーを Google に送って戻す方法がない
- URL 変更ですべてのブックマークやリンクが壊れる
- フォーム送信後にページを更新すると再送信される（POST-Redirect-GET パターンがない）
- [OAuth2](oauth2.ja.md)/[OIDC](oidc.ja.md) が不可能 -- プロトコル全体がリダイレクトに依存

リダイレクトは、複数ステップのウェブフローを繋ぐ接着剤です。

---

## volta-auth-proxy でのリダイレクト

volta は[認証](authentication-vs-authorization.ja.md)フローでリダイレクトを多用します：

```
  ステップ1: ユーザーが保護ページにアクセス
  ブラウザ ──GET /dashboard──> リバースプロキシ ──ForwardAuth──> volta
                                                                │
  ステップ2: volta が「未認証」と判定                              │
  volta の応答: 302 Location: /auth/login?redirect_to=/dashboard
                                                                │
  ステップ3: volta が Google にリダイレクト                         │
  ブラウザ ──GET /auth/login──> volta                             │
  volta の応答: 302 Location: https://accounts.google.com/...
                                                                │
  ステップ4: Google がリダイレクトバック                             │
  ブラウザ ──GET /auth/callback?code=abc──> volta                 │
                                                                │
  ステップ5: volta が元のページにリダイレクト                        │
  volta の応答: 302 Location: /dashboard                          │
  ブラウザ ──GET /dashboard──>（認証済み！）
```

**セキュリティ：リダイレクト URI 検証**

リダイレクトは危険にもなり得ます。攻撃者が volta を `https://evil.com` にリダイレクトさせると、認可コードが盗まれる可能性があります。volta は以下で防止します：

- **許可リスト** -- 事前設定された[リダイレクト URI](redirect-uri.ja.md) のみ受け付ける
- **完全一致** -- バイパスされ得る部分一致やパターン一致はしない
- **[オープンリダイレクト](open-redirect.ja.md)防止** -- `redirect_to` パラメータを許可[ドメイン](domain.ja.md)に対して検証

---

## 具体的な例

volta ログイン時の完全なリダイレクトチェーン：

1. ユーザーが[ブラウザ](browser.ja.md)に `https://app.acme.example.com/settings` と入力
2. [リバースプロキシ](reverse-proxy.ja.md)が volta に問い合わせ：「このユーザーは認証済み？」 -- いいえ
3. **リダイレクト1:** volta が `302 Location: https://auth.acme.example.com/auth/login?redirect_to=https://app.acme.example.com/settings` で応答
4. ブラウザが volta のログインページにリダイレクト
5. ユーザーが「Sign in with Google」をクリック
6. **リダイレクト2:** volta が `302 Location: https://accounts.google.com/o/oauth2/v2/auth?client_id=...&redirect_uri=https://auth.acme.example.com/auth/callback&state=...` で応答
7. ブラウザが Google にリダイレクト
8. ユーザーが Google で認証
9. **リダイレクト3:** Google が `302 Location: https://auth.acme.example.com/auth/callback?code=abc123&state=xyz` で応答
10. ブラウザが volta にリダイレクトバック
11. volta がコードを検証し、[セッション](session.ja.md)を作成し、[Cookie](cookie.ja.md) を設定
12. **リダイレクト4:** volta が `302 Location: https://app.acme.example.com/settings` で応答
13. ブラウザが元のページにリダイレクト -- 認証済み

4回のリダイレクトが1秒未満で完了。ユーザーは設定ページに着く前に一瞬のフラッシュを見るだけです。

---

## さらに学ぶために

- [リダイレクト URI](redirect-uri.ja.md) -- OAuth プロバイダがユーザーを戻す先の URL
- [オープンリダイレクト](open-redirect.ja.md) -- 未検証リダイレクトを悪用する攻撃
- [HTTP ステータスコード](http-status-codes.ja.md) -- リダイレクトを引き起こす 3xx コード
- [ログイン](login.ja.md) -- 最もリダイレクトを使うフロー
- [OIDC](oidc.ja.md) -- リダイレクトの上に構築されたプロトコル
- [ブラウザ](browser.ja.md) -- リダイレクトに従うソフトウェア
