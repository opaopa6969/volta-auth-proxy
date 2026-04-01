# 検証

[English version](verification.md)

---

## これは何？

検証（Verification）とは、何かが本物であり改ざんされていないことを確認するプロセスです。voltaのコンテキストでは、主にJWTの[デジタル署名](digital-signature.md)が有効かどうかを確認すること -- つまりトークンがvoltaによって発行され、その[クレーム](claim.md)が改ざんされていないことを証明することを意味します。

紙幣の確認を思い浮かべてください。お札を光にかざして透かしを確認し、指で浮き出し印刷をなぞり、セキュリティストリップを確認します。お金を作っているのではなく、お金が本物であることを検証しています。誰でも紙幣を検証できますが、造幣局だけが作れます。同様に、[公開鍵](public-key-cryptography.md)を持つ誰でもJWTを検証できますが、[署名鍵](signing-key.md)の保持者だけがJWTを作れます。

検証はデコードとは異なります。JWT（Base64）をデコードすると中身が見えます -- 誰でもこれはできます。検証は中身が信頼できることを証明します。デコードされたが未検証のJWTは、信頼できないただのJSONです。

---

## なぜ重要なのか？

検証がなければ、JWTを受け取るすべてのシステムが盲目的に信頼しなければなりません。攻撃者は：

- `"volta_roles": ["admin"]`の偽JWTを作って管理者アクセスを得られる
- `exp`クレームを変更して期限切れのないトークンを作れる
- `sub`クレームを変えて別のユーザーになりすませる
- `volta_tid`を切り替えて別のテナントのデータにアクセスできる

検証はvoltaのすべてのセキュリティ保証を現実のものにするゲートキーパーです。[暗号署名](cryptographic-signature.md)は誰かが実際にチェックしてこそ意味があります。

---

## どう動くのか？

### JWT検証ステップ

```
  受信JWT: header.payload.signature
         │
         ▼
  ステップ1: JWTをパース
         │  header、payload、signatureに分割
         │
         ▼
  ステップ2: アルゴリズムを確認
         │  header.algはRS256でなければならない
         │  "none"、"HS256"、その他すべて拒否
         │
         ▼
  ステップ3: 署名を検証
         │  再計算: RS256(header + "." + payload, public_key)
         │  提供された署名と比較
         │  一致 → トークンはvoltaが署名
         │  不一致 → トークンは偽造または改ざん → 拒否
         │
         ▼
  ステップ4: クレームを検証
         │  ├── iss == 設定された発行者?
         │  ├── aud に設定された対象者が含まれる?
         │  ├── exp > 現在時刻?
         │  └── すべての必須クレームが存在する?
         │
         ▼
  ステップ5: トークン検証済み。クレームを抽出して処理を続行。
```

### 署名検証の詳細

```
  JWT:  eyJhbGciOi...  .  eyJzdWIiOi...  .  SflKxwRJSM...
        ─────────────     ─────────────     ─────────────
        header (B64)      payload (B64)     signature (B64)

  検証:
  ┌──────────────────────────────────────────────────┐
  │                                                  │
  │  input = base64(header) + "." + base64(payload)  │
  │                                                  │
  │  expected_sig = RS256_SIGN(input, private_key)   │
  │  (秘密鍵はないので、代わりに...)                  │
  │                                                  │
  │  RS256_VERIFY(input, signature, public_key)      │
  │  → true: 署名は対応する秘密鍵で作成された        │
  │  → false: 署名が無効                             │
  │                                                  │
  │  これが非対称暗号の魔法:                          │
  │  署名できなくても検証できる。                     │
  └──────────────────────────────────────────────────┘
```

### 検証されるもの vs チェックされるもの

```
  検証（暗号的証明）:
  ┌──────────────────────────────────────────┐
  │  「このトークンはvoltaの秘密鍵保持者が    │
  │   署名し、署名以降1バイトも変更されて     │
  │   いない。」                              │
  └──────────────────────────────────────────┘

  チェック（ビジネスロジック）:
  ┌──────────────────────────────────────────┐
  │  「このトークンは期限切れでない。」        │
  │  「このトークンは自分の対象者向けだ。」   │
  │  「このトークンはvoltaが発行した。」       │
  │  「このユーザーは必要なロールを持つ。」    │
  └──────────────────────────────────────────┘

  両方が必要。クレームチェックなしの検証は
  期限切れや宛先違いのトークンを受け入れる。
  検証なしのクレームチェックは偽造を受け入れる。
```

---

## volta-auth-proxy ではどう使われている？

### JwtService.verify()

```java
public Map<String, Object> verify(String token) {
    SignedJWT jwt = SignedJWT.parse(token);

    // ステップ1: アルゴリズム確認
    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
        throw new IllegalArgumentException("Unsupported JWT algorithm");
    }

    // ステップ2: 署名検証
    JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
    if (!jwt.verify(verifier)) {
        throw new IllegalArgumentException("Invalid JWT signature");
    }

    // ステップ3: クレーム検証
    JWTClaimsSet claims = jwt.getJWTClaimsSet();
    if (!config.jwtIssuer().equals(claims.getIssuer())) {
        throw new IllegalArgumentException("Invalid issuer");
    }
    if (!claims.getAudience().contains(config.jwtAudience())) {
        throw new IllegalArgumentException("Invalid audience");
    }
    if (claims.getExpirationTime() == null
        || claims.getExpirationTime().before(new Date())) {
        throw new IllegalArgumentException("Token expired");
    }
    return claims.getClaims();
}
```

### Cookie署名検証

voltaは[署名Cookie](signed-cookie.md)も[HMAC](hmac.md)を使って検証します：

```java
String expected = SecurityUtils.hmacSha256Hex(secret, cookieValue);
if (!SecurityUtils.constantTimeEquals(expected, providedSignature)) {
    // Cookieが改ざんされている
    throw new IllegalArgumentException("Invalid cookie signature");
}
```

### 定数時間比較

voltaはタイミング攻撃を防ぐため、すべての署名比較に`MessageDigest.isEqual()`を使用します：

```java
public static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
}
```

---

## よくある間違いと攻撃

### 間違い1: 署名は検証するがクレームは検証しない

有効な署名はトークンがvoltaによって発行されたことだけを意味します。トークンがまだ有効か（`exp`）、自分のサービス向けか（`aud`）、期待される発行者からか（`iss`）は意味しません。常にすべてのクレームをチェックしましょう。

### 間違い2: alg: noneを受け入れる

一部のJWTライブラリは`alg: none`（署名なし）を受け入れます。誰でも「有効な」トークンを作れてしまいます。voltaはRS256をハードコードし、それ以外をすべて拒否します。

### 間違い3: 検証に間違った鍵を使用

サービスが間違った公開鍵（例：異なる環境のもの）を使うと、すべてのトークンが無効に見えます。JWKSエンドポイントが発行者と一致していることを確認しましょう。

### 攻撃: アルゴリズム混同（RS256 → HS256）

攻撃者がJWTヘッダーを`alg: HS256`に変更し、公開鍵（公開されている）で署名します。脆弱なライブラリが公開鍵をHMACシークレットとして扱い、偽造トークンを受け入れます。voltaは検証前に`alg == RS256`を確認してこれを防いでいます。

### 攻撃: JKU/X5U経由の鍵注入

一部のJWTヘッダーには署名鍵を指すURL（`jku`や`x5u`）が含まれます。攻撃者がこれを自分の鍵サーバーに向けることができます。voltaはこれらのヘッダーを無視し、自身の設定された鍵のみを使用します。

---

## さらに学ぶ

- [digital-signature.md](digital-signature.md) -- 検証の数学的基盤
- [cryptographic-signature.md](cryptographic-signature.md) -- 数学的証明としての署名
- [rs256.md](rs256.md) -- voltaが検証に使う具体的なアルゴリズム
- [claim.md](claim.md) -- JWT内で検証されるデータ
- [signing-key.md](signing-key.md) -- 署名と検証に使われる鍵ペア
- [hmac.md](hmac.md) -- 署名Cookieに使われる検証
