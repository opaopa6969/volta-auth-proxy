# Webhook（ウェブフック）

[English version](webhook.md)

---

## これは何？

Webhookはサーバー間の通知メカニズムです。あるシステムで重要なイベントが発生すると、別のシステムが登録したURLにHTTP POSTリクエストを送信します。2番目のシステムが「何か変わった？」と常に問い合わせる代わりに、最初のシステムが「何か起きたよ。詳細はこちら」と伝えるのです。

玄関のチャイムのようなものです。チャイムがなければ、誰か来ていないか数分おきにドアを開けて確認しなければなりません（ポーリング）。チャイムがあれば、訪問者がボタンを押すだけで即座に通知されます。Webhookはインターネットのチャイムです。あるシステムがニュースがあるとき、別のシステムのURLを鳴らします。

Webhookはイベント駆動統合のバックボーンです。Stripeのような決済プロセッサは課金の通知に使います。アイデンティティプロバイダはユーザー変更の通知に使います。volta-auth-proxyは認証イベントをアプリケーションに通知するために使います。

---

## なぜ重要なのか？

Webhookがないと、システム同士が常にポーリングし合う必要があります：

```
  ポーリング（無駄）：
  ┌────────┐   「新しいイベントある？」  ┌────────────────┐
  │  あなた │ ───────────────────────► │ volta-auth-    │
  │  のアプリ│ ◄─────────────────────── │ proxy          │
  │        │   「ないよ。」             │                │
  │        │                          │                │
  │        │   「新しいイベントある？」  │                │
  │        │ ───────────────────────► │                │
  │        │ ◄─────────────────────── │                │
  │        │   「ないよ。」             │                │
  │        │                          │                │
  │        │   「新しいイベントある？」  │                │
  │        │ ───────────────────────► │                │
  │        │ ◄─────────────────────── │                │
  │        │   「あるよ！ユーザー登録」  │                │
  └────────┘                          └────────────────┘

  Webhook（効率的）：
  ┌────────┐                          ┌────────────────┐
  │  あなた │                          │ volta-auth-    │
  │  のアプリ│                          │ proxy          │
  │        │   （静寂...）              │                │
  │        │                          │（ユーザー登録）  │
  │        │   POST /your/webhook     │                │
  │        │ ◄─────────────────────── │                │
  │        │   「ユーザー登録したよ！」  │                │
  └────────┘                          └────────────────┘
```

主な利点：

- **リアルタイム**：ポーリング間隔ではなく、イベント発生時に即座に知ることができる
- **効率的**：変更がないときの無駄なリクエストがない
- **疎結合**：voltaはアプリの内部ロジックを知る必要がない。イベントを送るだけ
- **スケーラブル**：1日1イベントでも10,000イベントでも同じように動作

---

## どう動くのか？

### 基本的なフロー

```
  1. 登録
     アプリがvoltaに伝える：「https://myapp.com/webhooks/volta にイベントを送って」
     voltaがこのURLと共有HMACシークレットを保存。

  2. イベント発生
     ユーザー登録、ロール変更、テナント停止など。

  3. 通知
     voltaが登録されたURLにHTTP POSTを送信：

     POST https://myapp.com/webhooks/volta
     Content-Type: application/json
     X-Volta-Signature: sha256=a1b2c3d4e5f6...
     X-Volta-Event: user.created
     X-Volta-Delivery: 550e8400-e29b-41d4-a716-446655440000

     {
       "event": "user.created",
       "timestamp": "2026-04-01T12:00:00Z",
       "tenant_id": "acme-uuid",
       "data": {
         "user_id": "new-user-uuid",
         "email": "alice@example.com",
         "roles": ["MEMBER"]
       }
     }

  4. 確認応答
     アプリがHTTP 200を返して受信を確認。
     アプリが4xx/5xxを返すかタイムアウトした場合、voltaがリトライ。
```

### HMAC署名検証

誰でもあなたのWebhook URLにPOSTしてvoltaのふりができます。HMAC署名がこれを防ぎます：

```
  volta側：
    secret  = "whsec_abc123..."  （共有シークレット）
    payload = '{"event":"user.created",...}'
    signature = HMAC-SHA256(secret, payload)
    ヘッダー：X-Volta-Signature: sha256=<signature>

  アプリ側：
    1. 生のリクエストボディを読む（JSONパースはまだしない）
    2. 共有シークレットのコピーでHMAC-SHA256を計算
    3. 計算した署名とヘッダーの値を比較
    4. 一致 → リクエストは本物
    5. 不一致 → 403で拒否
```

```java
// Javaでの検証例
String header = request.header("X-Volta-Signature");
String expected = "sha256=" + hmacSha256(secret, rawBody);
if (!MessageDigest.isEqual(expected.getBytes(), header.getBytes())) {
    return response.status(403);
}
```

### voltaの主なWebhookイベント

| イベント | トリガー |
|---------|---------|
| `user.created` | 新規ユーザーが[テナント](tenant.md)に参加 |
| `user.deleted` | テナントからユーザー削除 |
| `user.role_changed` | ユーザーの[ロール](role.md)更新 |
| `tenant.suspended` | テナント[停止](suspension.md) |
| `tenant.reactivated` | テナント再有効化 |
| `invitation.accepted` | [招待コード](invitation-code.md)使用 |
| `billing.plan_changed` | Stripe[課金](billing.md)プラン更新 |
| `m2m.client_created` | 新規[M2M](m2m.md)クライアント登録 |

---

## volta-auth-proxyではどう使われている？

### アウトバウンドWebhook（voltaがアプリに通知）

voltaは[Outboxパターン](outbox-pattern.md)を使ってWebhookを確実に送信します。イベント発生時、voltaはHTTPリクエストを即座には送りません。代わりに：

1. イベントがアクション自体と同じデータベーストランザクション内で`webhook_outbox`テーブルに書き込まれる
2. バックグラウンドのOutboxワーカーが保留中のイベントを取得して配信
3. 配信が失敗した場合、ワーカーが指数バックオフで[リトライ](retry.md)

これにより、アクションがデータベースにコミットされれば、ネットワークが一時的にダウンしていてもWebhookは最終的に配信されることが保証されます。

### インバウンドWebhook（voltaがイベントを受信）

voltaはWebhookの受信側としても機能します。Stripe課金Webhookを専用エンドポイントで[取り込み](ingestion.md)、プラン変更、支払い失敗、サブスクリプション更新を処理します。これらのインバウンドWebhookは処理前にStripeの署名スキームで検証されます。

### Webhook登録

テナント管理者がvolta APIでWebhookエンドポイントを登録します：

```
POST /api/v1/tenants/{tid}/webhooks
{
  "url": "https://myapp.com/webhooks/volta",
  "events": ["user.created", "user.deleted", "tenant.suspended"],
  "active": true
}

レスポンス：
{
  "webhook_id": "wh-uuid",
  "secret": "whsec_abc123...",
  "url": "https://myapp.com/webhooks/volta",
  "events": ["user.created", "user.deleted", "tenant.suspended"]
}
```

`secret`は作成時に一度だけ表示されます。管理者はHMAC署名検証のためにアプリケーションに保存します。

---

## よくある間違いと攻撃

### 間違い1：HMAC署名を検証しない

署名検証を省略すると、Webhook URLを発見した誰でも偽のイベントを送れます。常に`X-Volta-Signature`ヘッダーを検証してください。

### 間違い2：署名検証前にJSONをパースする

HMACは生のバイト列に対して計算されます。先にJSONをパースして再シリアライズすると、空白やキーの順序が変わり、署名が一致しなくなります。常に生のリクエストボディに対して検証してください。

### 間違い3：Webhookが正確に1回届くと仮定する

ネットワークは信頼できません。アプリの応答が遅かったが実際にはイベントを処理していた場合、voltaが配信をリトライする可能性があります。Webhookハンドラを冪等に設計してください。`X-Volta-Delivery` IDを使って重複を検出します。

### 間違い4：Webhookハンドラ内で重い処理をする

ハンドラは素早く（5秒以内に）200を返すべきです。重い処理が必要な場合、イベントをキューに入れて非同期で処理してください。遅い応答はリトライを引き起こし、重複処理の原因になります。

### 攻撃：リプレイ攻撃

攻撃者が正当なWebhookを傍受し、後で再送する。防御：ペイロードにタイムスタンプを含め、閾値（例：5分）より古いイベントを拒否する。

### 攻撃：URLプロービング

攻撃者が内部サービスを指すWebhook URLを登録する（SSRF）。防御：voltaがWebhook URLを許可ドメインの[ホワイトリスト](whitelist.md)に対して検証し、プライベートIP範囲をブロックする。

---

## さらに学ぶために

- [outbox-pattern.md](outbox-pattern.md) -- voltaが確実なWebhook配信をどう保証するか。
- [retry.md](retry.md) -- 配信失敗時のリトライ戦略。
- [ingestion.md](ingestion.md) -- voltaがインバウンドWebhook（Stripeなど）をどう受信するか。
- [m2m.md](m2m.md) -- よくWebhookを使うマシン間通信。
- [billing.md](billing.md) -- voltaが取り込むStripe課金Webhook。
