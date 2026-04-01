# リクエストボディ（Request Body）

[English version](request-body.md)

---

## これは何？

リクエストボディとは、HTTPリクエストの一部としてクライアントがサーバーに送るメインコンテンツ（「ペイロード」）です。封筒の中身であり、ヘッダー（封筒の外側に書かれた情報）とは対照的です。

郵便で荷物を送るようなものです。荷物の外側にはラベル（ヘッダー）があります：差出人、宛先、重さ、「取扱注意」の警告。そして中には物（ボディ）があります：誕生日プレゼント、書類の束、4.5キロのコーヒー豆。荷物を仕分ける郵便局員はラベルを読みます -- 箱は開けません。正しく配送するにはラベルで十分です。

---

## どのリクエストにボディがあるか？

すべてのHTTPリクエストにボディがあるわけではありません：

```
GET /api/users              ← ボディなし。「リストをください」だけ。
DELETE /api/users/123       ← ボディなし（通常）。「これを削除して」だけ。

POST /api/users             ← ボディあり：{ "name": "太郎", "email": "taro@..." }
PUT /api/users/123          ← ボディあり：{ "name": "更新された名前" }
PATCH /api/users/123        ← ボディあり：{ "email": "new@..." }
```

POSTとPUTリクエストはデータを運びます -- 作成する新しいユーザー、フォーム送信、ファイルアップロード、JSONペイロード。このデータは小さい（数バイトのJSON）こともあれば巨大（500MBの動画アップロード）なこともあります。

### ボディの見た目

```
JSONボディ（APIで最も一般的）：
{
  "name": "山田太郎",
  "email": "taro@example.com",
  "role": "ADMIN"
}

フォームボディ（HTMLフォーム送信）：
name=%E5%B1%B1%E7%94%B0%E5%A4%AA%E9%83%8E&email=taro%40example.com&role=ADMIN

ファイルアップロードボディ：
--boundary
Content-Disposition: form-data; name="avatar"
Content-Type: image/png

[... バイナリ画像データ、メガバイトになる可能性 ...]
--boundary--
```

---

## voltaがボディを見ない理由

これはForwardAuthパターンの最も重要な特性の1つです。TraefikがvoltaにForwardAuthサブリクエストを送るとき、ヘッダーのみを送信します -- リクエストボディは送信しません。

```
ユーザーが送るもの：
  POST /api/documents
  Cookie: __volta_session=abc123
  Content-Type: application/json

  { "title": "秘密のレポート", "content": "機密データ..." }

TraefikがvoltaにForwardAuthサブリクエストとして送るもの：
  GET /auth/verify
  Cookie: __volta_session=abc123
  X-Forwarded-Host: docs.example.com
  X-Forwarded-Uri: /api/documents
  X-Forwarded-Method: POST

  （ボディなし）

voltaが承認した後、Traefikがアプリに送るもの：
  POST /api/documents
  X-Volta-User-Id: user-uuid
  X-Volta-Tenant-Id: tenant-uuid
  Content-Type: application/json

  { "title": "秘密のレポート", "content": "機密データ..." }
```

voltaはクッキー（ユーザーを識別するため）、転送されたヘッダー（どのアプリとエンドポイントかを知るため）を見て、それ以外は何も見ません。実際のドキュメント内容 -- 機密データを含む「秘密のレポート」 -- はvoltaを通過することなく、Traefikから直接アプリに送られます。

### なぜこれが重要か

1. **プライバシー。** リクエストボディにはしばしば機密データが含まれます：個人情報、財務記録、医療データ、プライベートメッセージ。voltaはこのデータを見ないため、漏洩、ログ記録、開示を求められることがありません。

2. **パフォーマンス。** ファイルアップロードは100MBになることもあります。voltaがその100MBを受信、処理、転送しなければならないなら、遅くなり膨大なメモリを使います。代わりに、voltaは数百バイトのヘッダーを処理し、Traefikがボディを直接アプリにストリーミングします。

3. **セキュリティサーフェス。** システムが処理するすべてのデータは潜在的な攻撃ベクトルです：インジェクション攻撃、バッファオーバーフロー、不正な入力。リクエストボディに触れないことで、voltaは潜在的な脆弱性のカテゴリ全体を排除します。

4. **シンプルさ。** voltaはJSON解析、マルチパートフォーム解析、ファイルアップロード処理、コンテンツエンコーディングを理解する必要がありません。ヘッダーを読み、セッションをチェックし、ヘッダーを返す。それだけです。

---

## 比較：リバースプロキシ vs ForwardAuth

```
フルリバースプロキシ（認証プロキシがすべてを見る）：
  ブラウザ ──[完全なリクエスト]──► 認証プロキシ ──[完全なリクエスト]──► アプリ
  認証プロキシはボディを含むリクエスト全体を受信し転送する。
  ボトルネックでありプライバシーリスク。

ForwardAuth（voltaはヘッダーのみ見る）：
  ブラウザ ──[完全なリクエスト]──► Traefik ──[完全なリクエスト]──► アプリ
                                     │
                                [ヘッダーのみ]
                                     │
                                     ▼
                                volta-auth-proxy
                                （セッションをチェック、
                                 ヘッダーを返す、
                                 ボディには触れない）
```

---

## volta-auth-proxyでは

voltaはForwardAuthパターンを使用しており、リクエストボディを受信、処理、アクセスすることはありません -- HTTPヘッダーのみを見るため、高速でプライベートで攻撃サーフェスが最小です。

---

## さらに学ぶために

- [forwardauth.ja.md](forwardauth.ja.md) -- voltaが見るものと見ないものを含む完全なForwardAuthフロー。
- [header.ja.md](header.ja.md) -- voltaが読む「封筒の外側の情報」。
- [network-hop.ja.md](network-hop.ja.md) -- ホップ上で送るデータを少なくすることが重要な理由。
- [proxy-types.ja.md](proxy-types.ja.md) -- 異なるプロキシタイプがリクエストボディをどう扱うか。
