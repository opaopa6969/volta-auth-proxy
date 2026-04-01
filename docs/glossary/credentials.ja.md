# 認証情報（クレデンシャル）

[English version](credentials.md)

---

## 一言で言うと？

認証情報（クレデンシャル）とは、自分が名乗っている人物であることをシステムに証明するために提示するもの -- パスワード、API キー、Google アカウントなどです。

---

## あなたの身分証と鍵

認証情報は、日常で本人確認に使うものと同じです：

| 現実世界 | デジタル認証情報 | 使う人 |
|---|---|---|
| 運転免許証 | ユーザー名 + パスワード | サイトにログインする人間 |
| ホテルの部屋の鍵 | [セッション](session.ja.md) [Cookie](cookie.ja.md) | ログイン状態を維持する[ブラウザ](browser.ja.md) |
| 社員証 | [API](api.ja.md) キーや [JWT](jwt.ja.md) | API を呼び出すアプリケーション |
| 指紋 | Google からの OAuth トークン | [OIDC](oidc.ja.md) 経由の volta |
| 推薦状 | Client ID + Client Secret | Google に対して自身を識別する volta |

重要な区別：

- **知っているもの** -- パスワード、PIN、秘密の質問
- **持っているもの** -- スマホ（2FA コード用）、セキュリティキー
- **自分自身であるもの** -- 指紋、顔認証

組み合わせる種類が多いほど、認証は強力になります。これを多要素認証（MFA）と呼びます。

---

## なぜ必要なの？

認証情報がなければ：

- 誰でも誰にでもなりすませる -- 証明不要
- [ログイン](login.ja.md)が無意味に（「管理者です」 -- 「はい、どうぞ！」）
- API エンドポイントが全世界に公開
- ユーザーの区別なし、[RBAC](authentication-vs-authorization.ja.md) なし、監査証跡なし
- マルチテナントシステムでデータを分離できない -- 誰でも任意のテナントを名乗れる

認証情報はあらゆるシステムにおける信頼の基盤です。認証情報が漏洩すれば、その上に構築されたすべてが崩壊します。

---

## volta-auth-proxy での認証情報

volta は複数の種類の認証情報を扱います：

| 認証情報 | 誰が持つか | 何を証明するか | 保存場所 |
|---|---|---|---|
| Google アカウント（メール + パスワード） | ユーザー | ユーザーの身元 | Google（volta ではない） |
| Google Client ID + Secret | volta | volta は正規の OAuth クライアント | [環境変数](environment-variable.ja.md) |
| `__volta_session` Cookie | [ブラウザ](browser.ja.md) | ユーザーに有効なセッションがある | Cookie（ブラウザ）+ sessions テーブル（PostgreSQL） |
| [JWT](jwt.ja.md)（RS256） | アプリ | ユーザーは認証済みでこのロールを持つ | [ヘッダー](header.ja.md)経由、公開鍵で検証 |
| RSA 秘密鍵 | volta | この JWT は volta が発行した | ファイルシステムまたは環境変数 |
| DB 接続文字列 | volta | volta は PostgreSQL にアクセスできる | 環境変数 |

**volta はユーザーのパスワードを一切保存しません。** [認証](authentication-vs-authorization.ja.md)を [OIDC](oidc.ja.md) で Google に委譲することで、パスワード保存の巨大な責任とリスクを回避しています。パスワード DB がなければ、パスワード DB の漏洩もありません。

**volta での認証情報セキュリティ：**

- Google Client Secret は[環境変数](environment-variable.ja.md)に保存、コードには含めない
- セッション Cookie は [HttpOnly](httponly.ja.md) + Secure + [SameSite](samesite.ja.md)
- JWT は5分で期限切れ -- 盗まれても短い窓
- RSA 秘密鍵は API 経由で公開されない
- DB 認証情報は [Docker ネットワーク](network-isolation.ja.md)内に閉じている

---

## 具体的な例

volta ログイン時の認証情報チェーン：

1. ユーザーが **Google 認証情報**（メール + パスワード + 任意の MFA）を Google に提供
2. Google が検証し、volta に**認可コード**を渡す
3. volta が **Client ID + Client Secret** を使ってコードをトークンに交換
4. volta が**セッション**を作成し、[ブラウザ](browser.ja.md)に**セッション Cookie** を設定
5. volta が **RSA 秘密鍵**で **JWT** に署名し、[ヘッダー](header.ja.md)経由でアプリに渡す
6. アプリが volta の **RSA 公開鍵**（`/.well-known/jwks.json` から取得）で JWT を検証

各認証情報が異なることを証明します：

- ステップ1: 「私はこの Google ユーザーです」
- ステップ3: 「私は volta、信頼された OAuth クライアントです」
- ステップ4: 「このブラウザにはアクティブなセッションがあります」
- ステップ5: 「この JWT は volta が発行し、改ざんされていません」

認証情報が漏洩した場合：

- **Google パスワード漏洩** -- 攻撃者がユーザーとしてログイン可能（Google の MFA で軽減）
- **Client Secret 漏洩** -- 攻撃者が Google に対して volta になりすまし可能（即座にローテーション）
- **セッション Cookie 漏洩** -- 攻撃者がユーザーのセッションを持つ（volta がサーバー側で取り消し可能）
- **RSA 秘密鍵漏洩** -- 攻撃者が JWT を偽造可能（即座に鍵をローテーション）

---

## さらに学ぶために

- [ログイン](login.ja.md) -- 認証情報を最初に提示する場所
- [セッション](session.ja.md) -- ログイン成功後に作成される認証情報
- [Cookie](cookie.ja.md) -- セッション認証情報がブラウザに保存される方法
- [JWT](jwt.ja.md) -- volta がアプリに渡す認証情報
- [環境変数](environment-variable.ja.md) -- サーバー側の認証情報の保存場所
- [OIDC](oidc.ja.md) -- Google との認証情報交換を扱うプロトコル
