# JWT 署名

[English version](jwt-signature.md)

---

## これは何？

署名は JWT の3番目で最後の部分です（`ヘッダー.ペイロード.署名`）。トークンが改ざんされていないことの暗号学的な証明です。手紙の封蝋のようなもので、手紙を開けて内容を変えると封印が壊れます。

署名は、ヘッダーとペイロードをドットで結合し、その文字列を秘密鍵で署名して作成します。対応する公開鍵を持つ人なら誰でも署名の有効性を検証できます。

---

## なぜ重要？

署名がなければ JWT はセキュリティ上無意味です。誰でも `"roles": ["ADMIN"]` と書いた JWT を作って管理者アクセスを得られてしまいます。署名により、秘密鍵の所有者（volta-auth-proxy）だけが有効なトークンを作成できることが保証されます。ヘッダーやペイロードの1文字でも変更されると、署名は無効になります。

---

## 簡単な例

### 作り方（署名）

```
入力:    base64url(ヘッダー) + "." + base64url(ペイロード)
         = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ"

秘密鍵で署名 (RS256):
         RSA_SIGN(SHA256(入力), 秘密鍵)

出力:    "kB4rD9..." （署名、これも base64url エンコード）

最終的な JWT: ヘッダー.ペイロード.署名
              "eyJhbGci...eyJzdWIi...kB4rD9..."
```

### 検証方法

```
1. JWT をドットで分割 -> ヘッダー、ペイロード、署名
2. ヘッダーから "alg" を読む -> RS256
3. ヘッダーから "kid" を読む -> 公開鍵を検索
4. 再計算: SHA256(ヘッダー + "." + ペイロード)
5. 公開鍵で署名が一致するか検証
6. 一致 -> トークンは正当。不一致 -> 拒否。
```

### 改ざんされるとどうなる？

```
元のペイロード:     {"sub":"123","volta_roles":["MEMBER"]}
攻撃者が変更:      {"sub":"123","volta_roles":["OWNER"]}

署名は元のペイロードに対して計算された。
変更されたペイロードは異なる SHA256 ハッシュを生成する。
検証失敗 -> トークン拒否。
```

---

## volta-auth-proxy では

volta はすべての JWT 署名に RS256（RSA + SHA-256）を使用します：

**署名**（`JwtService.issueToken()` 内）：
```java
SignedJWT jwt = new SignedJWT(header, claims);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
```

**検証**（`JwtService.verify()` 内）：
```java
JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
if (!jwt.verify(verifier)) {
    throw new IllegalArgumentException("Invalid JWT signature");
}
```

volta は検証を試みる前にアルゴリズムが RS256 であることも確認します。これにより、攻撃者がサーバーを騙してより弱いアルゴリズムを使わせようとする「アルゴリズム混同攻撃」を防止します。

公開鍵は `/.well-known/jwks.json` で公開されており、下流サービスが volta に問い合わせることなく独立して volta 発行トークンを検証できます。

---

## 関連項目

- [jwt-header.md](jwt-header.md) -- アルゴリズムと鍵 ID が指定される場所
- [jwt-payload.md](jwt-payload.md) -- 署名が保護するデータ
