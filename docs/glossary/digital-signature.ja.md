# デジタル署名

[English / 英語](digital-signature.md)

---

## これは何？

デジタル署名は、あるデータが特定の当事者によって作成（または承認）され、その後改ざんされていないことを示す数学的な証明です。契約書の手書きサインのようなものですが、偽造がはるかに困難です。署名者は秘密鍵を使って署名を生成し、対応する公開鍵を持つ人なら誰でもそれを検証できます。

---

## なぜ重要？

インターネット上では、相手の目を見て書類を手渡すことができません。データは多くの中継者を通過します。デジタル署名は2つの問題を同時に解決します：

1. **真正性** -- 「これは本当に、名乗っている人から来たものだ」秘密鍵がなければ攻撃者は署名を偽造できない
2. **完全性** -- 「これは改変されていない」署名されたデータの1ビットでも変わると、署名が無効になる

JWT はデジタル署名に完全に依存しています。volta が JWT を発行するとき、RSA 秘密鍵でトークンに署名します。下流のサービスは署名を検証して、トークンが本物で改ざんされていないことを確認します。

---

## 簡単な例

```
1. volta が JWT ペイロードを作成：
   {"sub": "user-123", "email": "alice@example.com", "exp": 1711900000}

2. volta が秘密鍵で署名：
   署名 = RSA_SIGN(秘密鍵, ヘッダ + "." + ペイロード)

3. 完成した JWT：
   ヘッダ.ペイロード.署名

4. 下流のアプリが JWT を受け取り検証：
   RSA_VERIFY(公開鍵, ヘッダ + "." + ペイロード, 署名)
   -> true（有効）or false（改ざん/偽造）
```

攻撃者がペイロードを変更した場合（例：「user-123」を「admin-1」に変更）、署名が一致しなくなります。検証が失敗し、トークンは拒否されます。

---

## volta-auth-proxy での使い方

volta の `JwtService` は RS256（RSA + SHA-256）を使って発行するすべての JWT に署名します：

```java
SignedJWT jwt = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID())
        .type(JOSEObjectType.JWT)
        .build(),
    claims
);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
```

検証側では、volta は受信するすべての JWT を以下の通りチェックします：

- アルゴリズムは RS256 でなければならない（それ以外は拒否。「alg: none」攻撃を防止）
- 署名が volta の公開鍵に対して有効であること
- 発行者（issuer）が `"volta-auth"` であること
- 受信者（audience）に `"volta-apps"` が含まれること
- トークンが期限切れでないこと

この一連のチェックにより、検証を通過した JWT は信頼できる声明となります：「volta-auth-proxy がこの時点でこのユーザーの身元を確認し、トークンは発行以降誰にも変更されていない」

関連: [public-key-cryptography.md](public-key-cryptography.md), [hash-function.md](hash-function.md)
