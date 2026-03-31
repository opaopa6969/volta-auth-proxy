# ブルートフォース攻撃

[English / 英語](brute-force.md)

---

## これは何？

ブルートフォース攻撃は最もシンプルな攻撃形態です：うまくいくまですべての組み合わせを試します。パスワードなら「aaa」「aab」「aac」... とすべての組み合わせを試行。トークンならランダムな値を生成して送信。賢くはありませんが、執拗です。十分な時間と防御がなければ、ブルートフォースはいつか必ず成功します。

---

## なぜ重要？

ブルートフォース攻撃は自動攻撃ツールの基本です。インターネットに接続されたすべてのサーバーが、ボットからのログイン試行を絶え間なく受けています。レート制限がなければ、攻撃者は毎秒数千のパスワードを試せます。強いパスワードでも、試行回数に制限がなければいつか破られます。レート制限が主要な防御策です：1つの IP アドレス（またはアカウント）が1分間に何回試行できるかを制限することで、ブルートフォースを実用的でないほど遅くします。

---

## 簡単な例

レート制限なし：
```
攻撃者（1 IP）-> 毎分 10,000 パスワードを試行
サーバー       -> 各パスワードをチェック... いつか1つが成功
```

レート制限あり（200リクエスト/分）：
```
攻撃者（1 IP）-> リクエスト 1-200: 許可
              -> リクエスト 201+: HTTP 429 Too Many Requests
              -> 次の分ウィンドウまで待つ必要あり
```

毎分200回の試行では、6文字の小文字パスワード全組み合わせ（3億8百万通り）を試すのに約2.9年かかります。適切なパスワードなら、ブルートフォースは非現実的になります。

---

## volta-auth-proxy での使い方

volta は `RateLimiter` で IP ごとのレート制限を実装しています：

```java
public final class RateLimiter {
    private final int maxPerMinute;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        long bucket = Instant.now().getEpochSecond() / 60;
        Counter counter = counters.compute(key, (k, v) -> {
            if (v == null || v.bucketMinute != bucket) {
                return new Counter(bucket, 1);
            }
            v.count++;
            return v;
        });
        return counter.count <= maxPerMinute;
    }
}
```

重要な詳細：

- **上限**: IP アドレスごとに毎分200リクエスト（`new RateLimiter(200)` で設定可能）
- **バケット**: 1分間のタイムウィンドウを使用。新しい分が始まるとカウンタがリセット
- **スコープ**: IP アドレス単位で適用。1つの IP からの攻撃者は、異なるユーザー名を使っても上限を回避できない
- **レスポンス**: 上限を超えると、volta はリクエストが多すぎることを示すエラーを返す
- **並行安全**: `ConcurrentHashMap` の `compute()` でスレッドセーフなカウント

volta は認証を Google に委譲している（OIDC）ため、ブルートフォースすべき従来のパスワードログインはありません。しかしレート制限は、ログインフロー自体の悪用、API エンドポイント、自動スキャンツールによるリソース枯渇を防ぎます。

関連: [credential-stuffing.md](credential-stuffing.md), [replay-attack.md](replay-attack.md)
