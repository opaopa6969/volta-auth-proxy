# JWT のデコード方法

[English version](jwt-decode-howto.md)

---

## これは何？

JWT のデコードとは、その中身（ヘッダーとペイロード）を読むことです。JWT の*検証*（署名のチェック）とは別の操作です。JWT は base64url エンコードされているだけなので、誰でもデコードできます。**JWT は暗号化されていません。** 署名されているので改ざんされていないことは証明されますが、中身は誰でも読めます。

JWT は封蝋付きのはがきのようなものです。封蝋（署名）は送信者が本物であることを証明しますが、はがきを手にした人は誰でもメッセージを読めます。

---

## なぜ重要？

JWT をデコードできることは、デバッグの必須スキルです。問題が起きたとき（「なぜこのユーザーは 403 になる？」）、最初にすべきことは JWT をデコードしてクレームを確認することです。トークンは期限切れか？テナント ID は正しいか？ロールは想定通りか？

セキュリティの観点でも重要です。JWT は読めるものなので、秘密情報を入れてはいけません。ログや URL で JWT を見かけたら、中身は全員に見えていると認識してください。

---

## 簡単な例

### 方法 1: jwt.io（Web）

[https://jwt.io](https://jwt.io) にアクセスし、JWT を貼り付けると、デコードされたヘッダーとペイロードが即座に表示されます。公開鍵を入力すれば署名の検証もできます。

**注意：** 本番トークンを第三者のウェブサイトに貼り付けないでください。jwt.io はブラウザ内でローカルに実行されますが、機密性の高いトークンには注意が必要です。

### 方法 2: コマンドライン（bash）

JWT はドットで区切られた3つの部分で構成されます。最初の2つをデコードします：

```bash
# JWT が与えられたとして
TOKEN="eyJhbGciOiJSUzI1NiIsImtpZCI6ImtleS0yMDI1IiwidHlwIjoiSldUIn0.eyJpc3MiOiJ2b2x0YS1hdXRoIiwic3ViIjoiMTIzIn0.signature_here"

# ヘッダーをデコード（パート1）
echo "$TOKEN" | cut -d. -f1 | base64 -d 2>/dev/null
# {"alg":"RS256","kid":"key-2025","typ":"JWT"}

# ペイロードをデコード（パート2）
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null
# {"iss":"volta-auth","sub":"123"}
```

注意：base64url は `+` と `/` の代わりに `-` と `_` を使い、パディング `=` を省略します。ほとんどの `base64 -d` は対応していますが、エラーが出る場合はパディングを追加してください：

```bash
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d
```

### 方法 3: jq で見やすく出力

```bash
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "volta_tid": "660e8400-e29b-41d4-a716-446655440000",
  "volta_roles": ["ADMIN"],
  "exp": 1711875900
}
```

### 方法 4: Node.js ワンライナー

```bash
node -e "console.log(JSON.parse(Buffer.from('$TOKEN'.split('.')[1],'base64url')))"
```

---

## volta-auth-proxy では

volta は TTL 5 分の JWT を発行します。認証の問題をデバッグする際、JWT をデコードするのが最も早い方法です：

1. **`exp` を確認：** トークンは期限切れか？Unix タイムスタンプを現在時刻と比較。
2. **`volta_tid` を確認：** ユーザーは正しいテナントにいるか？
3. **`volta_roles` を確認：** ユーザーは必要なロールを持っているか？
4. **`iss` と `aud` を確認：** `volta-auth` と `volta-apps` か？

volta の JWKS エンドポイントから公開鍵を取得して完全な検証もできます：

```bash
curl http://localhost:7070/.well-known/jwks.json | jq .
```

覚えておいてください：デコードはトークンが*主張する内容*を見るだけです。署名検証だけがその主張が*真実か*を証明します。

---

## 関連項目

- [jwt-payload.md](jwt-payload.md) -- デコードしたクレームの意味
- [jwt-signature.md](jwt-signature.md) -- JWT をデコードだけでなく検証する方法
