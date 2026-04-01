# HMAC

[English version](hmac.md)

---

## これは何？

HMAC（Hash-based Message Authentication Code、ハッシュベースメッセージ認証コード）とは、[ハッシュ関数](hash-function.md)と秘密鍵を組み合わせて[暗号署名](cryptographic-signature.md)を作成する特定の方法です。メッセージが本物であること（シークレットを知っている人から来たこと）と、改ざんされていないことの両方を証明します。

クラブの秘密の握手を想像してください。あなたと用心棒の両方が握手を知っています。近づいたら握手を行います。用心棒がそれを認識すれば入場できます。握手を知らない人がでたらめなジェスチャーをしても拒否されます。ポイントは、両者が同じ秘密（握手のパターン）を共有していることです。これがHMACを「対称」にしています -- 片方だけが秘密鍵を持つ[RS256](rs256.md)とは異なります。

HMACは非対称署名より単純で高速なため、署名者と検証者が同じシステム（またはシークレットを共有）の場合に最適です。voltaはHMAC-SHA256を[Cookie署名](signed-cookie.md)とWebhook検証に使用しています。

---

## なぜ重要なのか？

HMACは特定の問題を解決します：「両者がシークレットを共有しているとき、このデータが改ざんされていないことをどう確認するか？」

HMACがなければ：
- 攻撃者が[Cookie](cookie.md)の値を変更してもサーバーが検出できない
- Webhookペイロードが偽造され、不正な操作がトリガーされる可能性
- 設定データが転送中に改ざんされる可能性

単なる[ハッシュ](hash-function.md)（SHA-256単体）はこれを解決しません。攻撃者はどんな値のハッシュでも計算できるからです -- シークレットがありません。HMACはハッシュ計算に秘密鍵を追加し、鍵を知らずに偽造を不可能にします。

---

## どう動くのか？

### HMAC構成

```
  HMAC-SHA256(key, message):

  ┌──────────────────────────────────────────────────┐
  │  ステップ1: 鍵を準備                             │
  │  鍵 > ブロックサイズの場合: key = SHA256(key)    │
  │  鍵をブロックサイズにパディング（SHA-256は64バイト）│
  │                                                  │
  │  ステップ2: 内側ハッシュ                          │
  │  inner_pad = key XOR 0x36363636...               │
  │  inner_hash = SHA256(inner_pad + message)        │
  │                                                  │
  │  ステップ3: 外側ハッシュ                          │
  │  outer_pad = key XOR 0x5C5C5C5C...               │
  │  hmac = SHA256(outer_pad + inner_hash)           │
  │                                                  │
  │  結果: 32バイト（256ビット）の認証コード          │
  └──────────────────────────────────────────────────┘
```

### なぜ単にhash(key + message)ではダメなのか？

```
  素朴なアプローチ: SHA256(key + message)
  ┌──────────────────────────────────────────────┐
  │  問題: 長さ拡張攻撃                          │
  │  SHA-256はMerkle-Damgardベース。              │
  │  SHA256(key + message)を知っていれば、        │
  │  攻撃者は鍵を知らずに                        │
  │  SHA256(key + message + extra)を計算できる！  │
  │                                              │
  │  HMACは二重ハッシュ（内側+外側）構成で        │
  │  これを防ぐ。                                │
  └──────────────────────────────────────────────┘
```

### HMAC vs RSA署名

```
  HMAC（対称）:
  ┌──────────────────────────────────────────────┐
  │  同じ鍵で署名と検証                          │
  │  両者がシークレットを共有する必要あり         │
  │  高速: RSAより約100倍速い                    │
  │  用途: Cookie署名、Webhook                   │
  │                                              │
  │  volta: SecurityUtils.hmacSha256Hex()        │
  └──────────────────────────────────────────────┘

  RSA署名（非対称）:
  ┌──────────────────────────────────────────────┐
  │  秘密鍵で署名、公開鍵で検証                  │
  │  署名者だけが秘密鍵を必要とする              │
  │  低速だが、公開鍵は自由に共有可能            │
  │  用途: JWT署名（RS256）                      │
  │                                              │
  │  volta: JwtService.issueToken()              │
  └──────────────────────────────────────────────┘
```

### HMAC検証

```
  サーバーがCookieを作成:
  value = "session-uuid"
  sig = HMAC-SHA256(secret, value) = "a3f2c8..."
  cookie = value + "." + sig

  後でサーバーがCookieを受信:
  ┌──────────────────────────────────────────┐
  │  Cookieを分割 → value、provided_sig     │
  │  再計算: expected_sig =                  │
  │    HMAC-SHA256(secret, value)            │
  │                                          │
  │  constantTimeEquals(expected, provided)? │
  │  はい → Cookieは本物                     │
  │  いいえ → Cookieは改ざん → 拒否          │
  └──────────────────────────────────────────┘
```

---

## volta-auth-proxy ではどう使われている？

### Cookie署名

`SecurityUtils.hmacSha256Hex()`は`__volta_session` Cookieの署名に使われます：

```java
public static String hmacSha256Hex(String secret, String payload) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(sig.length * 2);
    for (byte b : sig) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

### 定数時間比較

HMAC検証はタイミングサイドチャネル攻撃を防ぐために定数時間比較を使用：

```java
public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
}
```

`String.equals()`を使うと、攻撃者がレスポンス時間を測定してHMACを1バイトずつ推測できてしまいます。

### Webhook検証

voltaが下流サービスにWebhookを送るとき、ペイロードをHMAC-SHA256で署名できます。受信サービスは共有Webhookシークレットで署名を検証します：

```
  voltaが送信:
  POST /webhook
  X-Volta-Signature: sha256=a3f2c8e1...
  Body: {"event": "user.created", "data": {...}}

  受信者が検証:
  expected = HMAC-SHA256(webhook_secret, body)
  constantTimeEquals(expected, header_sig)? → 受理 / 拒否
```

---

## よくある間違いと攻撃

### 間違い1: HMACの代わりに普通のハッシュを使用

`SHA256(value)`は誰でも計算できます。`HMAC-SHA256(secret, value)`はシークレットが必要です。認証が必要な場合は常にHMACを使いましょう。

### 間違い2: 弱いHMAC鍵

HMAC鍵が"secret"や"password"ではブルートフォース可能です。少なくとも32バイトの強力でランダムな鍵を使いましょう。

### 間違い3: 比較にString.equals()を使用

`String.equals()`は最初の不一致バイトで早期リターンし、タイミング情報を漏洩します。HMAC検証には常に定数時間比較を使いましょう。

### 間違い4: メッセージの鮮度チェックなしのHMAC

HMACは真正性を証明しますが鮮度は証明しません。攻撃者は古いHMAC署名付きメッセージをリプレイできます。リプレイ防止のためにHMACをタイムスタンプや[ナンス](nonce.md)と組み合わせましょう。

### 攻撃: タイミングサイドチャネル

攻撃者が異なるHMAC値でリクエストを送り、レスポンス時間を測定します。比較がタイミングを漏洩すれば、HMACを1バイトずつ再構成できます。voltaの`constantTimeEquals()`はこれを防ぎます。

### 攻撃: HMAC鍵としてのRS256公開鍵（アルゴリズム混同）

システムがJWT検証にHMAC（HS256）とRSA（RS256）の両方を受け入れる場合、攻撃者は公開RSA鍵（公開されている）をHMACシークレットとして使えます。voltaはJWTにRS256のみを受け入れ、HMACはCookieにのみ使用してこれを防いでいます。

---

## さらに学ぶ

- [hash-function.md](hash-function.md) -- 基礎となるハッシュ関数（SHA-256）
- [signed-cookie.md](signed-cookie.md) -- HMACがCookieの完全性にどう使われるか
- [cryptographic-signature.md](cryptographic-signature.md) -- 署名のより広い概念
- [rs256.md](rs256.md) -- JWT署名のための非対称な代替手段
- [nonce.md](nonce.md) -- HMAC署名付きメッセージのリプレイ攻撃防止
