# 暗号署名

[English version](cryptographic-signature.md)

---

## これは何？

暗号署名（Cryptographic Signature）とは、特定のデータが特定の[秘密鍵](key-cryptographic.md)の保持者によって作成（または承認）され、署名以降データが改ざんされていないことを示す数学的な証明です。手書きの署名のデジタル版ですが、数学的に検証可能で鍵なしに偽造不可能なため、はるかに安全です。

法律文書の公証スタンプを想像してください。公証人が文書を確認し、スタンプを押し、署名します。スタンプを見た人は次のことが分かります：(1) 特定の公証人がこの文書を承認した、(2) スタンプ後に文書を改ざんすれば検出可能。暗号署名はこの2つの保証を、蝋とインクの代わりに数学で提供します。

暗号署名はvoltaの信頼モデル全体を支えています。すべてのJWTがvoltaが発行し[クレーム](claim.md)が改ざんされていないことを証明する署名を持ちます。

---

## なぜ重要なのか？

暗号署名がなければ、データが信頼できるソースから来たことを確認する方法がありません。誰でも`volta_roles: ["admin"]`のJWTを作って管理者を名乗れます。下流サービスは正当なトークンと偽造を区別できません。

署名は3つの重要な特性を提供します：

- **認証**: 署名はデータを誰が作ったか（秘密鍵の保持者）を証明する
- **完全性**: 署名は署名以降データが変更されていないことを証明する
- **否認防止**: 署名者は署名したことを否定できない（秘密鍵は本人だけが持つ）

署名が機能しなければ、信頼の連鎖全体が崩壊します。voltaはトークンが本物であることを証明できません。下流アプリはJWTを信頼できません。認証システムは見せかけになります。

---

## どう動くのか？

### 署名プロセス

```
  入力:  データ（JWTヘッダー + ペイロード）
  鍵:    秘密鍵（RSA-2048）

  ┌──────────────────────────────────────────────┐
  │  ステップ1: データをハッシュ化               │
  │  hash = SHA-256(data)                        │
  │  固定サイズの「指紋」を生成                   │
  │                                              │
  │  ステップ2: ハッシュを秘密鍵で暗号化         │
  │  signature = RSA_ENCRYPT(hash, private_key)  │
  │                                              │
  │  ステップ3: 署名をデータに付加               │
  │  result = data + "." + signature             │
  └──────────────────────────────────────────────┘

  このデータに対してこの署名を生成できるのは
  秘密鍵の保持者だけ。
```

### 検証プロセス

```
  入力:  データ + 署名
  鍵:    公開鍵（秘密鍵に対応するもの）

  ┌──────────────────────────────────────────────┐
  │  ステップ1: 受信データをハッシュ化            │
  │  hash1 = SHA-256(data)                       │
  │                                              │
  │  ステップ2: 署名を公開鍵で復号               │
  │  hash2 = RSA_DECRYPT(signature, public_key)  │
  │                                              │
  │  ステップ3: ハッシュを比較                    │
  │  hash1 == hash2?                             │
  │  はい → 署名は有効                           │
  │  いいえ → データが改ざんまたは偽造           │
  └──────────────────────────────────────────────┘

  公開鍵を持つ誰でも検証できるが、
  秘密鍵なしに有効な署名は作れない。
```

### 署名 vs 暗号化

```
  ┌──────────────────────────────────────────────────┐
  │  署名:                                           │
  │  目的: 作者と完全性を証明                         │
  │  データ: 全員に見える（隠さない）                 │
  │  鍵: 秘密鍵が署名、公開鍵が検証                  │
  │  例: JWT署名（RS256）                            │
  │                                                  │
  │  暗号化:                                         │
  │  目的: 権限のない読者からデータを隠す              │
  │  データ: 隠される（鍵保持者だけが読める）          │
  │  鍵: 公開鍵が暗号化、秘密鍵が復号                │
  │  例: AES-256-GCMで暗号化された鍵                 │
  └──────────────────────────────────────────────────┘

  voltaはJWTに署名を使用（データは見えるが
  本物であることを保証）。
  voltaは保存時の鍵に暗号化を使用（データベース
  読者からデータを隠す）。
```

### 暗号署名の種類

| 種類 | アルゴリズム | 鍵の種類 | voltaでの使用 |
|------|-------------|---------|---------------|
| RSA署名 | [RS256](rs256.md) | 非対称（公開/秘密） | JWT署名 |
| [HMAC](hmac.md) | HMAC-SHA256 | 対称（共有シークレット） | Cookie署名 |
| ECDSA | ES256 | 非対称（楕円曲線） | voltaでは不使用 |

---

## volta-auth-proxy ではどう使われている？

### JWT署名（RS256）

voltaが発行するすべてのJWTはRS256署名を持ちます：

```java
// JwtService.java
SignedJWT jwt = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID())
        .type(JOSEObjectType.JWT)
        .build(),
    claims
);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
```

署名はヘッダーとペイロードの両方をカバーするため、どのクレームを変更しても署名が無効になります。

### JWT署名検証

```java
// JwtService.verify()
JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
if (!jwt.verify(verifier)) {
    throw new IllegalArgumentException("Invalid JWT signature");
}
```

### Cookie署名（HMAC-SHA256）

voltaの[署名Cookie](signed-cookie.md)はHMAC-SHA256を使用：

```java
// SecurityUtils.java
public static String hmacSha256Hex(String secret, String payload) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    byte[] sig = mac.doFinal(payload.getBytes(UTF_8));
    // 16進数文字列に変換
}
```

### 定数時間検証

voltaのすべての署名比較はタイミング攻撃を防ぐために定数時間比較を使用：

```java
public static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8));
}
```

---

## よくある間違いと攻撃

### 間違い1: 署名をまったく検証しない

一部の開発者はJWTをデコード（Base64）して署名を確認せずにクレームを直接使います。署名のない手紙を読んで内容を信じるようなものです。

### 間違い2: alg: noneを使用

JWT仕様は`alg: none`（署名なし）を許可しています。一部のライブラリはデフォルトでこれを受け入れます。voltaはRS256以外のすべてのアルゴリズムを明示的に拒否します。

### 間違い3: 署名と暗号化を混同

署名は真正性を証明しますがデータを隠しません。JWTペイロードはBase64エンコード（誰でも読める）です。データを隠す必要があるなら[暗号化](encryption.md)を使いましょう。

### 攻撃: アルゴリズム混同（RS256からHS256へ）

攻撃者がJWTヘッダーを`alg: HS256`に変更し、公開鍵（JWKSから公開されている）をHMACシークレットとして使用します。脆弱なライブラリがこの偽造トークンを受け入れます。voltaはRS256をハードコードしてこれを防いでいます。

### 攻撃: 署名剥がし

攻撃者がJWTから署名を削除し、`alg`を`none`に変更します。脆弱なライブラリが未署名トークンを受け入れます。voltaは検証を試みる前にアルゴリズムをチェックします。

### 攻撃: 鍵の混同

攻撃者が別の鍵でトークンに署名します。鍵が期待されるソースから来ていることを検証しなければ、署名チェックは通過しますがトークンは偽造です。voltaは検証に自身の鍵のみを使い、JWTヘッダーからの鍵は使いません。

---

## さらに学ぶ

- [digital-signature.md](digital-signature.md) -- デジタル署名のより広い概念
- [rs256.md](rs256.md) -- voltaがJWTに使う具体的な署名アルゴリズム
- [hmac.md](hmac.md) -- Cookieに使われる対称署名アルゴリズム
- [verification.md](verification.md) -- 署名を確認するプロセス
- [signing-key.md](signing-key.md) -- 署名の作成に使われる秘密鍵
- [public-key-cryptography.md](public-key-cryptography.md) -- 非対称署名の背後にある数学
