# 暗号的乱数（Crypto-Random）

[English version](crypto-random.md)

---

## これは何？

暗号的乱数（Cryptographically Secure Random）とは、大きな計算資源を持つ攻撃者にとっても予測不能なソースから生成された乱数のことです。通常の乱数（実際には擬似乱数で、シードが分かれば予測可能）とは異なり、暗号的乱数はセッションID、[暗号化](encryption.md)鍵、[ナンス](nonce.md)、招待コードなどセキュリティ上重要な操作に適しています。

サイコロの目と予測可能なパターンの違いを想像してください。偏りのあるサイコロを100回振るのを誰かが見ていれば、次の目を予測し始めるかもしれません。しかし完全に公正なサイコロが密封された箱の中にあり、観察も影響もできない状態 -- これが暗号的乱数です。以前の値をどれだけ見ても、次の値は予測できません。

Javaでは、`Math.random()`と`new Random()`は暗号的乱数ではありません。シードが分かれば予測可能な列を生成するアルゴリズムを使います。`SecureRandom`は暗号的乱数です -- ハードウェアのノイズ、OS のイベント、その他の予測不能なソースからエントロピーを取得します。

---

## なぜ重要なのか？

乱数値が予測可能だと、攻撃者は：

- **セッションIDを推測できる**: セッションID生成器が予測可能なら、攻撃者は他のユーザーのセッションIDを計算してセッションをハイジャックできる
- **CSRFトークンを予測できる**: 予測可能なCSRFトークンは攻撃者が生成でき、CSRF保護が無効になる
- **ナンスを偽造できる**: [ナンス](nonce.md)が予測可能なら、リプレイ保護が失敗する
- **暗号化鍵を復元できる**: 鍵生成のランダムネスソースが弱ければ、鍵空間が実質的に小さくなり、ブルートフォースが実行可能になる

voltaのすべてのランダム値 -- セッションID、招待コード、PKCE検証子、CSRFトークン、鍵ID、AES初期化ベクトル -- のセキュリティは暗号的乱数生成に依存しています。

---

## どう動くのか？

### エントロピーソース

```
  ハードウェア / OSエントロピー:
  ┌──────────────────────────────────────────────────┐
  │  予測不能性のソース:                              │
  │  ├── CPUタイミングジッター                       │
  │  ├── ディスクI/Oタイミング                       │
  │  ├── ネットワークパケット到着時刻                 │
  │  ├── マウス/キーボードイベント                    │
  │  ├── ハードウェアRNG（Intel RDRAND）             │
  │  └── 熱雑音                                     │
  │                                                  │
  │  OSがエントロピーを収集 → /dev/urandom (Linux)   │
  │  JVMがOSから読み取り → java.security.SecureRandom│
  └──────────────────────────────────────────────────┘
```

### Random vs SecureRandom

```
  java.util.Random（安全ではない）:
  ┌──────────────────────────────────────────────┐
  │  seed = System.nanoTime()（またはユーザー提供）│
  │  next = (seed * 0x5DEECE66DL + 0xBL)        │
  │         & 0xFFFFFFFFFFFFL                    │
  │                                              │
  │  問題: いくつかの出力から、全列を              │
  │  再構成できる。                               │
  │  セキュリティには絶対に使わない！              │
  └──────────────────────────────────────────────┘

  java.security.SecureRandom（安全）:
  ┌──────────────────────────────────────────────┐
  │  OSのハードウェアソースからエントロピー        │
  │  CSPRNGアルゴリズム（例：SHA1PRNG、DRBG）    │
  │  定期的にOSエントロピーから再シード           │
  │                                              │
  │  何十億の出力を観察しても、                   │
  │  次の出力は予測不能。                        │
  │  すべてのセキュリティ操作に必須。             │
  └──────────────────────────────────────────────┘
```

### 出力品質の比較

```
  予測可能（java.util.Random）:
  ┌─────────────────────────────────┐
  │  出力1: 42                      │
  │  出力2: 17                      │  これら3つの出力を見た
  │  出力3: 89                      │  攻撃者は出力4を
  │  出力4: ???                     │  予測できる。
  └─────────────────────────────────┘

  予測不能（SecureRandom）:
  ┌─────────────────────────────────┐
  │  出力1: a7f2c8e1               │
  │  出力2: 3b9d4f2a               │  何百万の出力を見ても
  │  出力3: 8c1e5d7b               │  出力4は
  │  出力4: ???                     │  予測不能。
  └─────────────────────────────────┘
```

---

## volta-auth-proxy ではどう使われている？

voltaはランダム性が必要なすべての場所で`SecureRandom`を使用します：

### 1. ランダムURL安全文字列

`SecurityUtils.randomUrlSafe()`が暗号ランダムバイトを生成しBase64-URLエンコードします：

```java
private static final SecureRandom RANDOM = new SecureRandom();

public static String randomUrlSafe(int byteLength) {
    byte[] bytes = new byte[byteLength];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}
```

用途：PKCE検証子、[state](state.md)パラメータ、CSRFトークン。

### 2. 招待コード

```java
public static String inviteCode() {
    return randomUrlSafe(24);  // 24バイト = 192ビットのエントロピー
}
```

`SecureRandom`の24バイトは192ビットのエントロピーを生成します。攻撃者が招待コードを推測するには2^192の可能性を試す必要があります -- 観測可能な宇宙の原子数より多い数です。

### 3. セッションID（UUID）

```java
public static UUID newUuid() {
    return UUID.randomUUID();  // 内部でSecureRandomを使用
}
```

Javaの`UUID.randomUUID()`は`SecureRandom`から122ビットのランダム性を持つバージョン4 UUIDを生成します。

### 4. AES初期化ベクトル

`KeyCipher`はAES-GCM暗号化のために12バイトのランダムIVを生成します：

```java
private static final SecureRandom RANDOM = new SecureRandom();

public String encrypt(String plain) {
    byte[] iv = new byte[12];
    RANDOM.nextBytes(iv);  // 暗号ランダムIV
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
    // ...
}
```

ランダムIVにより、同じ平文を2回暗号化しても異なる暗号文が生成されます。

### 5. RSA鍵生成

```java
KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
generator.initialize(2048);  // デフォルトでSecureRandomを使用
KeyPair keyPair = generator.generateKeyPair();
```

Javaの`KeyPairGenerator`はすべての鍵素材に対してデフォルトで`SecureRandom`を使用します。

### 暗号ランダム使用のまとめ

```
  ┌───────────────────────────────────────────────────┐
  │  コンポーネント      ランダムソース    ビット数     │
  │  ─────────────────────────────────────────────── │
  │  セッションID (UUID) SecureRandom    122ビット    │
  │  招待コード          SecureRandom    192ビット    │
  │  PKCE検証子          SecureRandom    256ビット    │
  │  stateパラメータ     SecureRandom    256ビット    │
  │  CSRFトークン        SecureRandom    256ビット    │
  │  AES-GCM IV          SecureRandom    96ビット     │
  │  RSA鍵ペア           SecureRandom    2048ビット   │
  │  鍵ID (kid)          タイムスタンプ  N/A（秘密   │
  │                      （ランダムでない）ではない）  │
  └───────────────────────────────────────────────────┘
```

---

## よくある間違いと攻撃

### 間違い1: セキュリティ値にMath.random()を使用

```java
// 間違い - 予測可能
String sessionId = String.valueOf(Math.random());

// 正しい - 暗号ランダム
String sessionId = UUID.randomUUID().toString();
```

`Math.random()`は線形合同法を使います。いくつかの出力から全列が復元可能です。

### 間違い2: タイムスタンプシードでnew Random()を使用

```java
// 間違い - 攻撃者がおおよその時刻を知っていれば予測可能
Random rng = new Random(System.currentTimeMillis());

// 正しい
SecureRandom rng = new SecureRandom();
```

ミリ秒精度のタイムスタンプには約30ビットのエントロピーしかありません。攻撃者は指定した時間窓のすべてのシードを試せます。

### 間違い3: IV/ナンスの再利用

同じAES鍵で同じIVを使うとGCMのセキュリティが完全に壊れます。voltaはすべての暗号化操作で新しいランダムIVを生成します。

### 間違い4: 不十分なエントロピー長

4バイトのランダムセッションIDには2^32（約40億）の可能性しかありません。攻撃者は数秒ですべてを試せます。セキュリティトークンには最低16バイト（128ビット）を使いましょう。voltaは招待コードに24バイト（192ビット）を使います。

### 攻撃: 乱数生成器の状態回復

攻撃者が弱いRNG（`java.util.Random`など）から十分な出力を観察できれば、内部状態をリバースエンジニアリングしてすべての将来の出力を予測できます。そのため`SecureRandom`はハードウェアエントロピーから定期的に再シードします。

---

## さらに学ぶ

- [nonce.md](nonce.md) -- リプレイ防止のための使い捨てランダム値
- [key-cryptographic.md](key-cryptographic.md) -- 暗号ランダムで生成される鍵
- [encryption-at-rest.md](encryption-at-rest.md) -- 暗号ランダムで生成されるIV
- [session-fixation.md](session-fixation.md) -- 予測不能なセッションIDが重要な理由
- [hash-function.md](hash-function.md) -- 暗号ランダムと共に使われるハッシュ関数
