# リプレイ攻撃

[English / 英語](replay-attack.md)

---

## これは何？

リプレイ攻撃は、攻撃者が有効なメッセージ（トークン、リクエスト、認証レスポンスなど）をキャプチャし、後でそれを再送して元の送信者になりすます攻撃です。攻撃者はメッセージを理解したり復号する必要はありません -- 録音して再生するだけです。声で開くドアロックに向かって、誰かが「ドアを開けて」と言うのを録音して再生するようなものです。

---

## なぜ重要？

システムが有効なトークンを受け取る際に、使用済みかどうかや時間的に有効かどうかを確認しなければ、1回の認証交換を傍受した攻撃者がそれを無限に再生できます。ユーザーのパスワードを盗んだりサーバーを侵害する必要はなく、キャプチャしたネットワークリクエスト1つで十分です。

リプレイ攻撃は認証トークンで特に危険です。再生されたトークンは元のトークンと同じアクセスを与えるからです。2つの主要な防御策は **nonce**（使い捨てのランダム値）と**有効期限**（短い期間で使えなくなるトークン）です。

---

## 簡単な例

リプレイ保護なし：
```
1. Alice が認証して id_token を受け取る（1時間有効）
2. 攻撃者がネットワーク上で id_token を傍受
3. 30分後、攻撃者が同じ id_token を提示
4. サーバーが受け入れる -- 攻撃者は「Alice」になる
```

nonce + 短い有効期限あり：
```
1. Alice が認証。サーバーが nonce="abc123" を保存
2. Google が nonce="abc123" を含む id_token を返す
3. サーバーが確認：nonce は保存値と一致？ はい。消費（使用済みにする）
4. 攻撃者が id_token をキャプチャして再生
5. サーバーが確認：nonce="abc123" は消費済み。拒否
```

---

## volta-auth-proxy での使い方

volta は複数のレベルでリプレイ攻撃を防御しています：

**OIDC フローの nonce**：

ログインフロー開始時、volta はランダムな nonce を生成してデータベースに保存：

```java
String nonce = SecurityUtils.randomUrlSafe(32);
store.saveOidcFlow(new OidcFlowRecord(state, nonce, verifier, ...));
```

Google が `id_token` を返すと、volta は nonce が一致するか検証：

```java
String nonce = (String) claims.getClaim("nonce");
if (nonce == null || !nonce.equals(expectedNonce)) {
    throw new IllegalArgumentException("Invalid nonce");
}
```

OIDC フローは使用時に即座に消費（データベースから削除）されるため、同じ `state`/`nonce` ペアは再生できません。

**短い JWT 有効期限**：

volta の JWT は5分で期限切れ（`JWT_TTL_SECONDS=300`）。JWT が傍受されても、再生できる時間枠は極めて狭いです。

**使い捨て state パラメータ**：

`state` パラメータは初回使用時にデータベースから消費されます（`store.consumeOidcFlow(state)`）。同じ state での2回目のコールバックは失敗します。

**フローの有効期限**：

OIDC フローは10分で期限切れ。state/nonce ペアが何らかの理由で消費されなくても、短い期間で無効になります。

関連: [brute-force.md](brute-force.md), [token-theft.md](token-theft.md)
