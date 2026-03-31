# 鍵ローテーション

[English / 英語](key-rotation.md)

---

## これは何？

鍵ローテーションとは、暗号鍵を定期的に新しいものに置き換える運用です。古い鍵は引退し、以後のすべての操作は新しい鍵が担います。家の鍵を定期的に交換するようなものです -- 誰かが気づかないうちに古い鍵のコピーを作っていても、錠を交換すれば使えなくなります。

---

## なぜ重要？

暗号鍵は気づかないうちに漏洩する可能性があります。攻撃者がログファイル、バックアップ、メモリダンプから鍵を盗んでも、発覚しないかもしれません。鍵を定期的にローテーションすることで被害を限定します：

- **被害範囲**: 鍵が漏洩しても、影響を受けるのはその鍵の有効期間中に署名されたトークンだけ
- **コンプライアンス**: 多くのセキュリティ基準（PCI DSS、SOC 2）が定期的な鍵ローテーションを要求
- **暗号解析への防御**: 鍵の使用期間が長いほど、攻撃者に分析材料を多く与える

課題はダウンタイムなしにローテーションすることです。鍵を即座に入れ替えると、古い鍵で署名されたトークンが検証できなくなります。移行期間が必要です。

---

## 簡単な例

```
時刻 0:  鍵 A がアクティブ。すべての JWT は鍵 A で署名。
時刻 1:  ローテーション！ 鍵 B を作成。鍵 A は「引退」。
         - 新しい JWT は鍵 B で署名
         - 鍵 A で署名された既存の JWT はまだ有効（期限切れまで）
         - JWKS エンドポイントが鍵 A と鍵 B の両方を公開
時刻 2:  鍵 A のトークンがすべて期限切れ（volta では最大5分）。
         鍵 A を JWKS から安全に削除可能。
```

JWT ヘッダの `kid`（Key ID）フィールドが検証者にどの鍵を使うか伝えるので、移行期間中も新旧両方のトークンを検証できます。

---

## volta-auth-proxy での使い方

volta は `JwtService.rotateKey()` で鍵ローテーションを実装しています：

```java
public synchronized String rotateKey() {
    RSAKey current = this.rsaKey;
    // 新しい 2048 ビット RSA 鍵ペアを生成
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String kid = "key-" + Instant.now()...;

    // 新しい鍵の保存と古い鍵の引退を1回のトランザクションで
    store.rotateSigningKey(
        current.getKeyID(),   // 引退させる古い kid
        kid,                  // 新しい kid
        keyCipher.encrypt(...), // 新しい公開鍵（暗号化済み）
        keyCipher.encrypt(...)  // 新しい秘密鍵（暗号化済み）
    );
    this.rsaKey = next;
    return kid;
}
```

volta のローテーションの仕組み：

1. **新しい鍵の生成**: 新しい 2048 ビット RSA 鍵ペアを生成
2. **アトミックなデータベース更新**: 古い鍵の引退と新しい鍵の保存を、AES-256-GCM で暗号化した上で、1回のデータベース操作で実行
3. **kid による識別**: 各鍵にはユニークな `kid`（例：`key-2026-03-31T10-00`）がある。kid はすべての JWT ヘッダに埋め込まれ、検証者にどの鍵を使うか伝える
4. **短いトークン寿命**: JWT は5分で期限切れ（`JWT_TTL_SECONDS=300`）。ローテーション後、古い鍵のトークンはすぐに消滅
5. **JWKS エンドポイント**: `/.well-known/jwks.json` が現在のアクティブな鍵を公開。JWKS をキャッシュする下流サービスは定期的にリフレッシュすべき

ローテーションは管理者 API から実行でき、漏洩が疑われる場合にすぐ鍵を交換できます。

関連: [encryption-at-rest.md](encryption-at-rest.md), [public-key-cryptography.md](public-key-cryptography.md)
