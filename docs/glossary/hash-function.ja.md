# ハッシュ関数（SHA-256）

[English / 英語](hash-function.md)

---

## これは何？

ハッシュ関数は、どんな入力 -- パスワード、ファイル、1文字、本1冊 -- でも受け取って、「ハッシュ」または「ダイジェスト」と呼ばれる固定サイズの出力を生成します。最も広く使われる SHA-256 は、常に256ビット（16進数64文字）の出力を生成します。重要な特性は：**一方向性**（出力から元の入力を逆算できない）、**衝突耐性**（同じハッシュを生成する2つの異なる入力を見つけることが事実上不可能）です。

---

## なぜ重要？

ハッシュ関数は暗号技術の万能ツールです。あらゆるところに登場します：

- **パスワード保存**: パスワードではなくハッシュを保存。データベースが漏洩しても、攻撃者が得るのはハッシュでありパスワードではない
- **データ完全性**: 転送前後でファイルをハッシュ化。ハッシュが一致すればファイルは破損していない
- **デジタル署名**: 署名者はまずデータをハッシュ化し、（大きな）データの代わりに（小さな）ハッシュに署名する
- **PKCE**: コードチャレンジはコードベリファイアの SHA-256 ハッシュ。ベリファイアを露出せずに検証できる
- **HMAC**: ハッシュ関数と秘密鍵を組み合わせてメッセージ認証コードを作る

ハッシュは指紋のようなものです。すべての人に固有の指紋があります。指紋から人を特定できますが、指紋から人を復元することはできません。

---

## 簡単な例

```
入力:  "hello"
SHA-256: 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824

入力:  "Hello"（1文字だけ大文字に）
SHA-256: 185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969
```

注目：入力のわずかな変化で、まったく異なるハッシュが生成されます。これを「雪崩効果」と呼びます。

また：入力の長さに関係なく、出力は常に16進数64文字です。

---

## volta-auth-proxy での使い方

volta は SHA-256 を複数の場所で使っています：

**PKCE コードチャレンジ**（`SecurityUtils.pkceChallenge()`）：

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");
byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
```

OIDC フローでランダムな `code_verifier` を生成し、SHA-256 でハッシュして `code_challenge` を作り、チャレンジだけを Google に送ります。後で volta が元のベリファイアを送り、フローを開始したのが自分であることを証明します。

**鍵暗号化鍵の導出**（`KeyCipher`）：

```java
MessageDigest sha = MessageDigest.getInstance("SHA-256");
byte[] key = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
this.keySpec = new SecretKeySpec(key, "AES");
```

環境変数 `JWT_KEY_ENCRYPTION_SECRET` を SHA-256 でハッシュし、256ビットの AES 鍵を導出します。秘密の長さに関係なく、暗号化鍵が常に正しいサイズになることを保証します。

**汎用ハッシュ**（`SecurityUtils.sha256Hex()`）：トークンなどの値で一方向の指紋が必要な場合に使用。

**HMAC-SHA256**（`SecurityUtils.hmacSha256Hex()`）：Webhook の署名検証に使用。SHA-256 と秘密鍵を組み合わせます。

関連: [public-key-cryptography.md](public-key-cryptography.md), [encryption-at-rest.md](encryption-at-rest.md)
