# 署名Cookie

[English version](signed-cookie.md)

---

## これは何？

署名Cookie（Signed Cookie）とは、改ざんを検出するための[HMAC](hmac.md)署名が付加された[Cookie](cookie.md)です。サーバーが秘密鍵を使ってCookieの値に対する署名を生成し、Cookieに付加します。以後のリクエストのたびに、サーバーが署名を再計算して比較します -- 値が変更されていれば署名が一致せず、サーバーはCookieを拒否します。

開封防止シール付きの封筒を思い浮かべてください。手紙を入れて封をし、封の上に特殊なシールを貼ります。誰かが封筒を開けて再び封をしても、シールが破れたり見た目が変わったりします。受け取った人は封筒が開けられたと分かります。署名Cookieの[HMAC](hmac.md)がその開封防止シールです。

重要なのは、署名は暗号化ではないということです。署名Cookieの値は検査すれば誰でも読めます（封筒が存在していることは見えるのと同じ）。署名は値が変更されていないことを証明するだけで、隠すわけではありません。voltaの`__volta_session` Cookieの場合、値はUUID（セッションID）だけで機密データではないため、これで問題ありません。

---

## なぜ重要なのか？

署名がなければ、Cookieにアクセスできる攻撃者（XSS、ネットワーク傍受、ブラウザのdevtools経由）がその値を変更できてしまいます。例えば：

- セッションIDを別のユーザーのセッションIDに変更する（セッションハイジャック）
- 有効なものと衝突するかもしれない偽のセッションIDを作る
- Cookieが構造化データを持つ場合に悪意のあるデータを注入する

署名はこれらの攻撃を検出可能にします。攻撃者がCookieの値を読めても、サーバーの秘密鍵なしでは有効な署名を偽造できません。変更されたCookieは即座に拒否されます。

---

## どう動くのか？

### 署名プロセス

```
  サーバーがCookieを作成:
  ┌──────────────────────────────────────────────────┐
  │                                                  │
  │  値: "550e8400-e29b-41d4-a716-446655440000"      │
  │  シークレット: "server-secret-key"                │
  │                                                  │
  │  署名 = HMAC-SHA256(secret, value)               │
  │       = "a3f2c8e1..."                            │
  │                                                  │
  │  Cookie = 値 + "." + 署名                        │
  │  "550e8400-e29b-41d4...a3f2c8e1..."              │
  └──────────────────────────────────────────────────┘

  Set-Cookie: __volta_session=550e8400...a3f2c8e1;
              HttpOnly; Secure; SameSite=Lax
```

### 検証プロセス

```
  ブラウザがCookieを送り返す:
  ┌──────────────────────────────────────────────────┐
  │  Cookie: __volta_session=550e8400...a3f2c8e1     │
  │                                                  │
  │  サーバーが分割: value = "550e8400..."            │
  │                  sig   = "a3f2c8e1..."           │
  │                                                  │
  │  再計算: HMAC-SHA256(secret, value)              │
  │        = "a3f2c8e1..."                           │
  │                                                  │
  │  比較: 受信した署名 == 計算した署名?              │
  │        "a3f2c8e1" == "a3f2c8e1" → 一致 ✓        │
  │                                                  │
  │  Cookieは本物。処理を続行。                       │
  └──────────────────────────────────────────────────┘
```

### 改ざん検出

```
  攻撃者がCookieの値を変更:
  ┌──────────────────────────────────────────────────┐
  │  元: "550e8400...a3f2c8e1"                       │
  │  変更後: "ATTACKER-SESSION-ID...a3f2c8e1"         │
  │                                                  │
  │  サーバーが再計算:                                │
  │  HMAC-SHA256(secret, "ATTACKER-SESSION-ID")      │
  │  = "7b9d4f2a..."                                 │
  │                                                  │
  │  比較: "a3f2c8e1" == "7b9d4f2a"? → 不一致       │
  │                                                  │
  │  Cookie拒否。攻撃を検出。→ 401                    │
  └──────────────────────────────────────────────────┘
```

### なぜ普通のハッシュではなくHMACなのか？

```
  普通のハッシュ（SHA-256）:
  ┌──────────────────────────────────────────────┐
  │  sig = SHA256(value)                         │
  │                                              │
  │  問題: 攻撃者はアルゴリズムを知っている。     │
  │  SHA256("evil-value")を計算して               │
  │  有効な値+ハッシュのペアを作れる。             │
  │  シークレット不要！                           │
  └──────────────────────────────────────────────┘

  HMAC（鍵付きハッシュ）:
  ┌──────────────────────────────────────────────┐
  │  sig = HMAC-SHA256(SECRET, value)            │
  │                                              │
  │  攻撃者は有効な署名を計算するために           │
  │  SECRETが必要。                               │
  │  シークレットなしでは偽造不可能。             │
  └──────────────────────────────────────────────┘
```

---

## volta-auth-proxy ではどう使われている？

voltaの`__volta_session` Cookieは署名Cookieです。署名は`SecurityUtils.hmacSha256Hex()`が処理します：

```java
public static String hmacSha256Hex(String secret, String payload) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    // 16進数文字列に変換
    StringBuilder sb = new StringBuilder(sig.length * 2);
    for (byte b : sig) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

### Cookie構造

```
  __volta_session Cookie:
  ┌──────────────────────────────────────────────────┐
  │  値: セッションUUID                              │
  │  署名方式: HMAC-SHA256                           │
  │  シークレット: サーバー設定から導出                │
  │                                                  │
  │  属性:                                           │
  │  ├── HttpOnly  （JavaScript アクセス不可）        │
  │  ├── Secure    （HTTPS のみ）                    │
  │  ├── SameSite=Lax （CSRF防御）                   │
  │  ├── Path=/    （全ルート）                      │
  │  └── Max-Age=28800 （8時間スライディングウィンドウ）│
  └──────────────────────────────────────────────────┘
```

### 定数時間比較

voltaは署名比較にタイミング攻撃を防ぐ`SecurityUtils.constantTimeEquals()`を使用します：

```java
public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
}
```

タイミング攻撃は比較にかかる時間を測定します。`String.equals()`が最初の不一致文字で早期リターンすると、攻撃者は一文字ずつ署名を推測できます。`constantTimeEquals`は不一致の位置に関わらず常に同じ時間がかかります。

---

## よくある間違いと攻撃

### 間違い1: HMACの代わりに普通のハッシュを使用

鍵なしのSHA-256では誰でもハッシュを計算できます。常に鍵付きハッシュ（[HMAC](hmac.md)）を使いましょう。

### 間違い2: 弱い署名シークレット

署名シークレットが推測可能（"secret"、"password"）なら、攻撃者は有効な署名を偽造できます。強力でランダムなシークレットを使いましょう。

### 間違い3: 定数時間比較を使わない

署名比較に`==`や`.equals()`を使うとタイミング情報が漏洩します。常に定数時間比較を使いましょう。

### 間違い4: 署名してもHttpOnlyを設定しない

JavaScriptから読める署名Cookieは、XSSで盗まれる可能性があります。署名は変更を防ぎますが窃取は防ぎません。常に署名とHttpOnlyを組み合わせましょう。

### 攻撃: Cookieリプレイ

攻撃者が署名Cookie全体（値+署名）を盗んでリプレイします。署名はこれを防ぎません -- 変更だけを防ぎます。防御策：HttpOnly（JS窃取を防止）、Secure（ネットワーク傍受を防止）、短い有効期限、[セッション固定化](session-fixation.md)防止。

---

## さらに学ぶ

- [cookie.md](cookie.md) -- Cookie全般
- [hmac.md](hmac.md) -- Cookie署名に使われるアルゴリズム
- [session.md](session.md) -- 署名Cookieが表すもの
- [session-fixation.md](session-fixation.md) -- 署名Cookieだけでは防げない攻撃
- [csrf.md](csrf.md) -- 署名CookieのSameSiteがCSRFを防ぐ仕組み
