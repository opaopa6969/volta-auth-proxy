# 自動鍵生成

[English version](auto-key-generation.md)

---

## これは何？

自動鍵生成とは、管理者が手動で暗号鍵を生成・設定する代わりに、システムが初回起動時に自動的に暗号鍵を作成する仕組みです。システムが「まだ鍵がない」ことを検出し、その場で生成して安全に保存します。

コンビネーションなしの新しい金庫を買ったと想像してください。メーカーに電話してコードをもらう必要はなく、初めて電源を入れたときに金庫が独自のコンビネーションを自動生成し、レシートに印刷して自らロックします。セットアップウィザードなし、手動設定なし -- 箱から出してすぐ使えます。

これはvoltaの「ゼロ設定」思想の重要な部分です。デプロイ直後に、運用者が暗号を理解したり鍵生成コマンドを実行したりする必要なく、システムがすぐ使える状態になるべきです。

---

## なぜ重要なのか？

手動の鍵管理はエラーが起きやすく、デプロイをブロックします：

- **忘れられたステップ**: 運用者が鍵生成を忘れ、実行時に不可解なエラーでシステムが落ちる
- **弱い鍵**: 運用者が不十分なランダム性や間違ったパラメータ（例：2048ビットではなく1024ビットRSA）で鍵を生成する
- **コピペの使い回し**: 運用者がチュートリアルや他の環境から鍵をコピーし、システム間で秘密を共有してしまう
- **設定の負担**: 新しい環境（開発、ステージング、本番）ごとに手動の鍵セットアップが必要

自動鍵生成はこれらすべてを排除します。システムは起動した瞬間から、正しく、ユニークで、強い鍵を常に持ちます。運用者がセキュリティ専門家でない可能性がある[セルフホスト](self-hosting.md)ソフトウェアにとって特に重要です。

---

## どう動くのか？

### 初回起動フロー

```
  volta-auth-proxy 起動
         │
         ▼
  signing_keys テーブルを検索
         │
         ├── 鍵あり? ──→ はい ──→ 鍵を読み込み・復号
         │                       (KeyCipher + AES-256-GCM使用)
         │                       JWTの署名準備完了。
         │
         └── 鍵あり? ──→ いいえ ──→ 新しいRSA-2048鍵ペアを生成
                                      │
                                      ▼
                                     AES-256-GCMで暗号化
                                      │
                                      ▼
                                     signing_keysテーブルに保存
                                      │
                                      ▼
                                     JWTの署名準備完了。
```

### 生成されるもの

```
  ┌──────────────────────────────────────────────────┐
  │  初回起動時に自動生成:                             │
  │                                                  │
  │  アルゴリズム:  RSA                               │
  │  鍵サイズ:     2048ビット                         │
  │  ソース:       java.security.KeyPairGenerator     │
  │  ランダム性:   java.security.SecureRandom         │
  │  鍵ID:        "key-2026-04-01T12-00"             │
  │              （タイムスタンプベース、ユニーク）      │
  │                                                  │
  │  保存先:      signing_keysテーブル                 │
  │  暗号化:      AES-256-GCM（KeyCipher経由）        │
  │  形式:        "v1:" + base64(IV) + ":" +          │
  │              base64(暗号化された鍵)                │
  └──────────────────────────────────────────────────┘
```

### 冪等性

自動鍵生成は冪等でなければなりません -- 複数回実行しても重複した鍵を作ってはいけません：

```
  起動1回目:  DBに鍵なし  →  生成 + 保存    →  鍵Aを使用
  起動2回目:  鍵AがDBにあり →  鍵Aを読み込み  →  鍵Aを使用
  起動3回目:  鍵AがDBにあり →  鍵Aを読み込み  →  鍵Aを使用
  ...
  ローテーション: 鍵A引退  →  鍵Bを生成      →  鍵Bを使用
  起動N回目:  鍵BがDBにあり →  鍵Bを読み込み  →  鍵Bを使用
```

---

## volta-auth-proxy ではどう使われている？

自動鍵生成ロジックは`JwtService`にあります：

```java
public JwtService(AppConfig config, SqlStore store) {
    this.config = config;
    this.store = store;
    this.keyCipher = new KeyCipher(config.jwtKeyEncryptionSecret());
    this.rsaKey = loadOrCreateKey();  // ← 自動鍵生成
}

private RSAKey loadOrCreateKey() {
    return store.loadActiveSigningKey()   // 既存の鍵を読み込み試行
            .map(this::restoreKey)         // 見つかったら復号
            .orElseGet(this::createKey);   // 見つからなければ生成
}
```

### 鍵作成の詳細

```java
private RSAKey createKey() {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);  // 強い鍵サイズ
    KeyPair keyPair = generator.generateKeyPair();
    String kid = "key-" + Instant.now()
        .truncatedTo(ChronoUnit.MINUTES)
        .toString().replace(":", "-");

    // 暗号化して保存 -- DBに平文は入れない
    store.saveSigningKey(kid,
        keyCipher.encrypt(base64(publicKey)),
        keyCipher.encrypt(base64(privateKey)));
    return rsaKey;
}
```

### 運用者が提供する必要があるもの

1つだけ：`JWT_KEY_ENCRYPTION_SECRET` -- 自動生成されたRSA鍵を保存時に暗号化するAES-256鍵を導出するためのパスフレーズ。それ以外はすべて自動です：

```
  運用者が提供:                          voltaが自動生成:
  ┌─────────────────────────────┐      ┌──────────────────────────────┐
  │ JWT_KEY_ENCRYPTION_SECRET   │      │ RSA-2048鍵ペア               │
  │ （環境変数）                 │      │ 鍵ID (kid)                   │
  │                             │      │ AES-256導出鍵                │
  │ これだけ。                   │      │ 暗号化された鍵の保存          │
  └─────────────────────────────┘      │ JWKSエンドポイント           │
                                       │ JWT署名機能                  │
                                       └──────────────────────────────┘
```

### 2回目以降の起動

2回目以降の起動では、`loadOrCreateKey()`がデータベース内の既存の鍵を見つけ、`KeyCipher`で復号し、通常の動作を再開します。新しい鍵は生成されません。システムは再起動をまたいでステートレスです -- すべての状態はデータベースにあります。

---

## よくある間違いと攻撃

### 間違い1: 保存時の暗号化なしに鍵を生成

データベースに平文で保存された自動生成鍵は、データベースが侵害されると露出します。常に保存時に暗号化しましょう。voltaは[KeyCipher](encryption-at-rest.md)経由でAES-256-GCMを使用しています。

### 間違い2: 弱い暗号化シークレットの使用

`JWT_KEY_ENCRYPTION_SECRET`が「password123」なら、AES暗号化は実質的に無意味です。強力でランダムなパスフレーズを使いましょう。

### 間違い3: 初回起動時の競合状態

複数のインスタンスが同時に起動すると、すべてが「鍵なし」を検出してそれぞれが鍵を生成する可能性があります。voltaは`loadOrCreateKey()`の`synchronized`とデータベース制約でこれを防いでいます。

### 間違い4: 鍵ローテーションをサポートしない

初回起動時の自動鍵生成は素晴らしいですが、後で交換する方法も必要です。voltaは[グレースフル・トランジション](graceful-transition.md)を維持しながら管理者API経由で[鍵ローテーション](key-rotation.md)をサポートしています。

### 間違い5: 生成された鍵をログに出力

自動生成された鍵は決してログ出力に現れてはいけません。voltaの`KeyCipher.encrypt()`により、暗号化された（読めない）値のみが保存・送信されます。

---

## さらに学ぶ

- [signing-key.md](signing-key.md) -- 自動生成される鍵
- [encryption-at-rest.md](encryption-at-rest.md) -- 自動生成された鍵がデータベースで保護される方法
- [self-signed.md](self-signed.md) -- 自動生成された鍵にCAが不要な理由
- [key-rotation.md](key-rotation.md) -- 自動生成された鍵を後で交換する方法
- [key-cryptographic.md](key-cryptographic.md) -- 暗号鍵全般
