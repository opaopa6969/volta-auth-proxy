# 署名鍵

[English version](signing-key.md)

---

## これは何？

署名鍵とは、データに[デジタル署名](digital-signature.md)を作成するために使われる秘密の[暗号鍵](key-cryptographic.md)です。voltaの場合は[JWT](jwt.md)に対して使います。voltaがJWTに署名するとき、署名鍵を使ってそのトークンがvoltaによって発行され改ざんされていないことの数学的証明を生成します。

個人の封蝋スタンプを思い浮かべてください。昔、貴族は手紙の封蝋に固有のスタンプを押しました。その模様を知っている人なら手紙がその貴族からのものだと確認できますが、スタンプを持っているのはその貴族だけです。署名鍵がスタンプです。[公開鍵](public-key-cryptography.md)は封蝋がどう見えるべきかの知識です。

署名鍵は絶対に秘密でなければなりません。他の誰かが入手すれば、正当なものと区別できない署名を偽造できます。そのためvoltaは署名鍵を[AES-256-GCM](encryption-at-rest.md)で保存時に暗号化し、いかなるAPIでも公開しません。

---

## なぜ重要なのか？

署名鍵はvoltaの認証システム全体の信頼の根幹です：

- **JWTの整合性**: すべてのJWTの署名は署名鍵で作成される。鍵が漏洩すれば、攻撃者は任意の[クレーム](claim.md)を持つJWTを作れる -- どのテナントの管理者にもなれる
- **否認防止**: 署名はvoltaがトークンを発行したことを証明する。安全な署名鍵がなければ、この保証は消える
- **カスケード障害**: 侵害された署名鍵はvoltaのJWTを信頼するすべての下流サービスを危険にさらす。被害範囲はシステム全体

そのためvoltaは複数の保護層を実装しています：[保存時の暗号化](encryption-at-rest.md)、弱い鍵を防ぐ[自動生成](auto-key-generation.md)、露出時間を制限する[ローテーション](key-rotation.md)。

---

## どう動くのか？

### 署名 vs 検証

```
  署名（秘密鍵 -- 秘密）:
  ┌──────────────────────────────────────────────┐
  │                                              │
  │  JWTヘッダー + ペイロード                     │
  │         │                                    │
  │         ▼                                    │
  │  RS256アルゴリズム + 秘密鍵                   │
  │         │                                    │
  │         ▼                                    │
  │  署名（JWTに付加される）                      │
  │                                              │
  │  voltaだけがこれを行える。                    │
  └──────────────────────────────────────────────┘

  検証（公開鍵 -- 共有）:
  ┌──────────────────────────────────────────────┐
  │                                              │
  │  JWTヘッダー + ペイロード + 署名              │
  │         │                                    │
  │         ▼                                    │
  │  RS256アルゴリズム + 公開鍵                   │
  │         │                                    │
  │         ▼                                    │
  │  有効? はい / いいえ                          │
  │                                              │
  │  公開鍵を持つ誰でもこれを行える。             │
  └──────────────────────────────────────────────┘
```

### 鍵ペアの関係

```
  ┌──────────────────────────────────┐
  │  RSA-2048 鍵ペア                 │
  │                                  │
  │  秘密鍵（署名鍵）:               │
  │  ┌────────────────────────────┐  │
  │  │ 2048ビットRSA秘密鍵       │  │
  │  │ JWTの署名に使用            │  │
  │  │ DBに暗号化して保存          │  │
  │  │ サーバーから出ない          │  │
  │  └────────────────────────────┘  │
  │           │                      │
  │           │ 数学的に              │
  │           │ 導出                  │
  │           ▼                      │
  │  公開鍵（検証鍵）:               │
  │  ┌────────────────────────────┐  │
  │  │ 対応する公開鍵             │  │
  │  │ JWTの検証に使用            │  │
  │  │ JWKSエンドポイントで公開   │  │
  │  │ 誰とでも共有して安全       │  │
  │  └────────────────────────────┘  │
  └──────────────────────────────────┘
```

### 鍵の識別（kid）

各署名鍵には一意な鍵ID（`kid`）があります。これはすべてのJWTヘッダーに埋め込まれ、検証者にどの鍵が使われたかを伝えます：

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2026-04-01T12-00"
}
```

[鍵ローテーション](key-rotation.md)中は複数の鍵が共存する場合があります。`kid`により正しい鍵が検証に使われることが保証されます。

---

## volta-auth-proxy ではどう使われている？

### 保存

署名鍵はAES-256-GCMで暗号化され`signing_keys`テーブルに保存されます：

```
  signing_keys テーブル:
  ┌────────────────────────────────────────────────┐
  │ kid         │ "key-2026-04-01T12-00"           │
  │ public_pem  │ "v1:IV_base64:encrypted_base64"  │
  │ private_pem │ "v1:IV_base64:encrypted_base64"  │
  │ status      │ "active"                         │
  │ created_at  │ 2026-04-01T12:00:00Z             │
  └────────────────────────────────────────────────┘
```

### JWTへの署名

`JwtService.issueToken()`がnimbus-jose-jwt経由で署名鍵を使用：

```java
SignedJWT jwt = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID())       // ヘッダーのkid
        .type(JOSEObjectType.JWT)
        .build(),
    claims
);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));  // 秘密鍵で署名
```

### 検証

`JwtService.verify()`が対応する公開鍵を使用：

```java
JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
if (!jwt.verify(verifier)) {
    throw new IllegalArgumentException("Invalid JWT signature");
}
```

### JWKS公開

公開鍵（署名鍵ではない）が`/.well-known/jwks.json`で公開されます：

```java
public String jwksJson() {
    RSAKey publicKey = new RSAKey.Builder(rsaKey.toRSAPublicKey())
            .keyID(rsaKey.getKeyID())
            .algorithm(JWSAlgorithm.RS256)
            .build();
    // 公開鍵のみ -- 秘密鍵は含まれない
    return new JWKSet(publicKey).toJSONObject();
}
```

### ローテーション

`rotateKey()`が呼ばれると、現在の署名鍵は引退し新しい鍵が作成されます。[鍵ローテーション](key-rotation.md)と[グレースフル・トランジション](graceful-transition.md)を参照。

---

## よくある間違いと攻撃

### 間違い1: API経由で署名鍵を公開

秘密鍵をJWKSやAPIレスポンスに含めてはいけません。共有するのは公開鍵だけです。voltaの`jwksJson()`は明示的に公開鍵のみの`RSAKey`を構築します。

### 間違い2: 環境間で同じ署名鍵を使用

開発、ステージング、本番はそれぞれ独自の署名鍵を持つべきです。voltaの[自動鍵生成](auto-key-generation.md)はこれを自然に保証します -- 各環境が初回起動時に独自の鍵を生成します。

### 間違い3: 署名鍵を平文で保存

データベースが侵害された場合、平文の署名鍵があると攻撃者がJWTを偽造できます。voltaは`KeyCipher`経由でAES-256-GCMですべての署名鍵を暗号化しています。

### 攻撃: 鍵の侵害

攻撃者が署名鍵を入手すると、任意のクレーム（任意のユーザー、テナント、ロール）でJWTを偽造できます。緩和策：[保存時の暗号化](encryption-at-rest.md)、短いトークン寿命（5分）、疑いがあれば即座に[鍵ローテーション](key-rotation.md)。

### 攻撃: アルゴリズム混同

攻撃者がJWTヘッダーを`alg: HS256`に変更し、公開鍵をHMACシークレットとして使用する。voltaは`JwtService.verify()`でRS256をハードコードしてこれを防いでいます：

```java
if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
    throw new IllegalArgumentException("Unsupported JWT algorithm");
}
```

---

## さらに学ぶ

- [key-cryptographic.md](key-cryptographic.md) -- 暗号鍵全般
- [digital-signature.md](digital-signature.md) -- 署名の数学的な仕組み
- [rs256.md](rs256.md) -- 署名鍵と共に使われる具体的なアルゴリズム
- [encryption-at-rest.md](encryption-at-rest.md) -- 署名鍵がストレージで保護される方法
- [auto-key-generation.md](auto-key-generation.md) -- 署名鍵が自動作成される仕組み
- [key-rotation.md](key-rotation.md) -- 署名鍵の時間経過に伴う交換
