# API（Application Programming Interface）

[English version](api.md)

---

## 一言で言うと？

APIとは、あるソフトウェアが別のソフトウェアと通信するためのルールとエンドポイントの集合です。注文できるものと注文方法が書かれたレストランのメニューのようなものです。

---

## レストランメニューのたとえ

レストランに行ったとき、キッチンに入って自分で料理を始めることはありません。代わりに：

1. **メニューを読む** -- 何があるか教えてくれる（パスタ、ステーキ、サラダ）
2. **注文する** -- 「パスタをお願いします」（リクエスト）
3. **キッチンが準備する** -- どうやって作るかは見えないし、気にしない
4. **料理が届く** -- テーブルに結果が届く（レスポンス）

APIも同じように動きます：

- **メニュー** = APIドキュメント（何を依頼できるかの一覧）
- **注文する** = エンドポイント（URL）にリクエストを送る
- **キッチン** = リクエストを処理するサーバー
- **料理** = 返ってくるレスポンスデータ

サーバーの内部コードに直接触れることはありません。キッチンに入らないのと同じです。メニュー（API）を使って通信します。

---

## RESTの基本（ほとんどのAPIの動き方）

現代のほとんどのAPIはREST（Representational State Transfer）というスタイルに従います。難しい名前は気にしないでください。意味はこうです：

1. **すべてがURL** -- やり取りできるものにはそれぞれ固有のアドレスがある
2. **HTTP動詞を使う** -- ニーズに応じて異なるアクション
3. **データはJSONで返ってくる** -- 構造化データのシンプルなテキスト形式

HTTP動詞（取れるアクション）：

| 動詞 | 意味 | レストランのたとえ |
|---|---|---|
| **GET** | 「情報をください」 | 「メニューを見せてください」 |
| **POST** | 「新しいものを作って」 | 「新しい注文をしたいです」 |
| **PUT** | 「全部置き換えて」 | 「注文を全部別のものに変えて」 |
| **PATCH** | 「一部を更新して」 | 「やっぱりパスタにチーズ多めで」 |
| **DELETE** | 「削除して」 | 「注文をキャンセルして」 |

---

## JSONとは？

JSON（JavaScript Object Notation）は、ほとんどのAPIがデータの送受信に使う形式です。構造化された情報を表現するシンプルで読みやすい方法です：

```json
{
  "name": "山田太郎",
  "email": "taro@acme.com",
  "role": "MEMBER",
  "tenant": {
    "id": "acme-uuid",
    "name": "ACME Corp"
  }
}
```

JSONを見たことがなくても読めるはずです。波かっことコロンで整理されたキーと値のペア（名前は「山田太郎」、メールは「taro@acme.com」など）です。

---

## voltaのInternal APIの仕組み

volta-auth-proxyにはInternal APIがあり、下流アプリがユーザー、テナント、メンバーシップの情報を取得するために呼び出せます。アプリから受付（volta）への内線電話のようなものと考えてください。

いくつかの例：

**現在のユーザーのプロフィールを取得：**
```
GET /api/v1/users/me

レスポンス：
{
  "id": "taro-uuid",
  "email": "taro@acme.com",
  "display_name": "山田太郎"
}
```

**テナントのメンバー一覧：**
```
GET /api/v1/tenants/acme-uuid/members

レスポンス：
{
  "members": [
    { "user_id": "taro-uuid", "role": "OWNER", "email": "taro@acme.com" },
    { "user_id": "hanako-uuid", "role": "MEMBER", "email": "hanako@acme.com" }
  ]
}
```

**テナントに誰かを招待：**
```
POST /api/v1/tenants/acme-uuid/invitations
ボディ：
{
  "email": "newperson@example.com",
  "role": "MEMBER"
}

レスポンス：
{
  "invitation_id": "inv-uuid",
  "status": "sent"
}
```

これらのAPI呼び出しには認証が必要です。アプリはリクエストヘッダーにサービストークン（アプリ用のパスワードのようなもの）を含める必要があります。これはvoltaに対して、あなたのアプリがこの情報を要求する権限があることを証明します。

---

## 簡単な例

wikiアプリがサイドバーにチームメンバーの一覧を表示する必要があるとします。データベースを直接クエリする代わりに（間違いや古い情報の可能性がある）、voltaのAPIに問い合わせます：

```
  Wikiアプリがメンバー一覧を必要としている：

  1. Wikiアプリ → GET /api/v1/tenants/acme-uuid/members
     ヘッダー：Authorization: Bearer <service-token>

  2. volta-auth-proxyがリクエストを処理：
     - サービストークンを確認（このアプリは認可されている？）
     - データベースでACME Corpのメンバーを検索
     - 一覧をJSONで返す

  3. Wikiアプリが受信：
     [
       { "name": "太郎", "role": "OWNER" },
       { "name": "花子", "role": "ADMIN" },
       { "name": "次郎", "role": "MEMBER" }
     ]

  4. Wikiアプリがサイドバーにメンバー一覧を表示
```

---

## さらに学ぶために

- [http-status-codes.md](http-status-codes.md) -- APIレスポンスの数字の意味（200、400、401など）。
- [sdk.md](sdk.md) -- voltaのAPIを簡単に呼び出せるライブラリ。
- [downstream-app.md](downstream-app.md) -- voltaのAPIを呼び出すアプリ。
