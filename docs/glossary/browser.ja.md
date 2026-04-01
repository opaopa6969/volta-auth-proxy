# ブラウザ

[English version](browser.md)

---

## 一言で言うと？

ブラウザとは、アドレスを入力したりリンクをクリックしてウェブサイトを閲覧するための、パソコンやスマートフォンのアプリ（Chrome、Safari、Firefox など）です。

---

## 世界への窓

外国のお店に行きたいけど、言葉が通じないとします。通訳を雇うと、こんなことをしてくれます：

- **あなたのリクエストを届ける**（「メニューを見せてください」）
- **お店の返事を持ち帰って** きれいに翻訳して見せてくれる
- **あなたの好みを覚えている**（名前やポイントカード）ので、毎回自己紹介しなくて済む
- **怪しい店を警告してくれる**（「この店は許可証がないよ、大丈夫？」）

その通訳がブラウザです。サーバーとは HTTP/HTTPS で会話し、結果をあなたが見られるページに変換してくれます。

| あなたがすること | ブラウザが裏でしていること |
|---|---|
| `example.com` と入力 | サーバーに HTTP GET リクエストを送信 |
| 「ログイン」をクリック | [認証情報](credentials.ja.md) を HTTPS で安全に送信 |
| ページを見る | [HTML](html.ja.md)、CSS、JavaScript を描画 |
| 鍵アイコンを見る | [SSL/TLS](ssl-tls.ja.md) 証明書を検証済み |
| ログイン状態が続く | [Cookie](cookie.ja.md) を保存し、毎回送信 |

---

## なぜ必要なの？

ブラウザがなければ、こうなります：

- ターミナルで生の [HTTP](http.ja.md) リクエストを手打ち
- HTML ソースコードを目で解読
- [Cookie](cookie.ja.md)、[リダイレクト](redirect.ja.md)、[SSL/TLS](ssl-tls.ja.md) ハンドシェイクを自分で処理

ブラウザがインターネットを人間にとって使えるものにしています。また、[同一オリジンポリシー](cross-origin.ja.md)や [CORS](cors.ja.md) といった重要なセキュリティルールを適用し、悪意あるサイトからデータを守ります。

---

## volta-auth-proxy でのブラウザ

volta-auth-proxy はブラウザと常にやり取りしています：

1. **ログインフロー** -- [ログイン](login.ja.md)時、ブラウザは Google の [OIDC](oidc.ja.md) ページに[リダイレクト](redirect.ja.md)され、認可コードとともに volta に戻ります。
2. **セッション Cookie** -- volta は `__volta_session` [Cookie](cookie.ja.md) を `HttpOnly`、`Secure`、`SameSite=Lax` で設定します。ブラウザがこれを保存し、毎リクエスト自動で送信します。
3. **ForwardAuth** -- アプリが[リバースプロキシ](reverse-proxy.ja.md)の背後にある場合、ブラウザは volta と直接通信しません。リバースプロキシが volta に Cookie を確認し、volta が [X-Volta-* ヘッダー](header.ja.md)を注入します。
4. **[CORS](cors.ja.md) の適用** -- ブラウザは volta の CORS ヘッダーが許可しない限り、[クロスオリジン](cross-origin.ja.md)リクエストをブロックします。悪意あるサイトによる volta [API](api.ja.md) の不正呼び出しを防ぎます。
5. **[レスポンシブ](responsive.ja.md)ログインページ** -- volta のログイン・招待ページはスマートフォンとデスクトップの両方に対応しています。

---

## 具体的な例

ブラウザで `https://app.acme.example.com` を開いたときの流れ：

1. ブラウザが[サーバー](server.ja.md)に GET リクエストを送る
2. [リバースプロキシ](reverse-proxy.ja.md)がリクエストを横取りし、volta に「このユーザーは認証済み？」と問い合わせる
3. volta が [Cookie](cookie.ja.md) を確認 -- Cookie なし
4. volta が[リダイレクト](redirect.ja.md)（HTTP 302）でログインページに飛ばす
5. ブラウザがリダイレクトに従い、ログインページを表示
6. 「Sign in with Google」をクリック
7. ブラウザが Google [OIDC](oidc.ja.md) にリダイレクト
8. Google で認証する
9. Google が認可コード付きで volta にリダイレクト
10. volta が[セッション](session.ja.md)を作成し、Cookie を設定し、元の URL にリダイレクト
11. ブラウザが再びリクエストを送る -- 今度は Cookie 付き
12. volta がセッションを検証し、[JWT](jwt.ja.md) ヘッダーを注入し、アプリが表示される

---

## さらに学ぶために

- [HTTP](http.ja.md) -- ブラウザがサーバーと話す言語
- [Cookie](cookie.ja.md) -- ページ間であなたを覚える仕組み
- [リダイレクト](redirect.ja.md) -- ブラウザが自動的に URL を変更する仕組み
- [SSL/TLS](ssl-tls.ja.md) -- 鍵アイコンが重要な理由
- [クロスオリジン](cross-origin.ja.md) -- 異なるドメイン間でブラウザが適用するセキュリティルール
- [レスポンシブ](responsive.ja.md) -- 異なるブラウザサイズにページが適応する仕組み
