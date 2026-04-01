# インジェスション（データ取り込み）

[English version](ingestion.md)

---

## これは何？

インジェスション（ingestion）は、外部ソースからの受信データを受け取り、検証し、処理するプロセスです。volta-auth-proxyの文脈では、主にインバウンド[Webhook](webhook.md)の受信を指します。例えば、Stripeがvoltaに課金イベントを送信する場合です。受信システムはデータが本物であることを検証し、パースし、アクションを実行する必要があります。

大きなオフィスビルのメールルームのようなものです。多くの送り主から手紙や荷物が届きます。メールルームはすべてを従業員のデスクにそのまま置いたりしません。差出人住所を確認し、不審物をスキャンし、部門ごとに仕分けして、適切な担当者に届けます。ソフトウェアのインジェスションも同じ仕組みです：外部からデータが届き、検証、仕分け、正しいハンドラにルーティングされます。

インジェスションはエミッション（送信）の反対です。voltaが[Outboxパターン](outbox-pattern.md)経由であなたのアプリに[Webhook](webhook.md)を送信するのがエミッション。voltaがStripeからWebhookを受信するのがインジェスションです。

---

## なぜ重要なのか？

外部システムからのインバウンドデータを盲目的に信頼することはできません：

```
  外部ソース                  volta-auth-proxy
  =========                  ================

  Stripeが送信：              voltaがすべきこと：
  「テナントXの                1. これは本当にStripeから？
   プランアップグレード」          （署名検証）
                             2. データは正しい形式？
                                （スキーマ検証）
                             3. 以前見たことがある？
                                （重複排除）
                             4. 何をすべき？
                                （ハンドラにルーティング）
                             5. 処理は成功した？
                                （確認応答またはリトライ）
```

適切なインジェスションがないと：

- **偽イベント**：攻撃者が偽の課金イベントを送ってテナントをダウングレード/アップグレードできる
- **重複処理**：リトライされたWebhookで顧客に二重課金される可能性
- **データ破損**：不正なペイロードがアプリケーションをクラッシュさせる可能性
- **イベント欠落**：確認応答されないWebhookはプロバイダから送信されなくなる

---

## どう動くのか？

### インジェスションパイプライン

```
  ┌─────────────────────────────────────────────────────┐
  │              インジェスションパイプライン                │
  │                                                       │
  │  1. 受信          2. 検証          3. パース           │
  │  ┌────────────┐   ┌────────────┐   ┌────────────┐    │
  │  │ HTTP POST  │──►│ HMAC署名   │──►│ JSONボディ  │    │
  │  │ を受け付け  │   │ チェック    │   │ デシリアライズ│    │
  │  │ 生バイト   │   │ タイムスタンプ│   │ 検証       │    │
  │  └────────────┘   └────────────┘   └────────────┘    │
  │                                         │             │
  │  4. 重複排除       5. ルーティング  6. 確認応答         │
  │  ┌────────────┐   ┌────────────┐   ┌────────────┐    │
  │  │ イベントID  │──►│ 正しい     │──►│ HTTP 200   │    │
  │  │ を処理済み  │   │ ハンドラに  │   │ を送信者に  │    │
  │  │ セットで確認│   │ ディスパッチ│   │ 返す       │    │
  │  └────────────┘   └────────────┘   └────────────┘    │
  └─────────────────────────────────────────────────────┘
```

### ステップ1：受信

生のHTTPリクエストを受け付け、処理前にボディをバイトとして保存：

```java
// 署名検証のために生ボディを保存
byte[] rawBody = ctx.bodyAsBytes();
String signatureHeader = ctx.header("Stripe-Signature");
```

### ステップ2：真正性の検証

各プロバイダには独自の署名スキームがあります。Stripeはタイムスタンプ付きHMAC-SHA256を使います：

```
  Stripe-Signature: t=1711900000,v1=abc123def456...

  検証：
    1. タイムスタンプ（t）と署名（v1）を抽出
    2. 署名対象ペイロードを構築：timestamp + "." + rawBody
    3. Webhookシークレットで HMAC-SHA256を計算
    4. 計算した署名をv1と比較
    5. タイムスタンプが許容範囲内か確認（5分）
```

### ステップ3：パースと検証

```json
{
  "id": "evt_1234567890",
  "type": "customer.subscription.updated",
  "data": {
    "object": {
      "customer": "cus_acme",
      "status": "active",
      "items": {
        "data": [{"price": {"id": "price_pro_monthly"}}]
      }
    }
  }
}
```

処理前に必須フィールドの存在と期待される型を検証します。

### ステップ4：重複排除

Stripe（および他のプロバイダ）はイベントをリトライする場合があります。イベント`id`で重複を検出：

```
  イベント "evt_1234567890" が到着
    → チェック：このIDを以前処理した？
    → YES：200を返す（確認応答）が処理はスキップ
    → NO：イベントを処理し、IDを保存
```

### ステップ5：ハンドラにルーティング

```java
switch (event.getType()) {
    case "customer.subscription.updated":
        handlePlanChange(event);
        break;
    case "customer.subscription.deleted":
        handleCancellation(event);
        break;
    case "invoice.payment_failed":
        handlePaymentFailure(event);
        break;
    default:
        log.info("未処理のイベントを無視: {}", event.getType());
}
```

---

## volta-auth-proxyではどう使われている？

### Stripe課金Webhookのインジェスション

voltaはStripe Webhookを専用エンドポイントで取り込み、[課金](billing.md)状態を管理します：

```
  Stripe                    volta-auth-proxy
  ======                    ================

  subscription.updated ────► POST /webhooks/stripe
                              │
                              ├─ Stripe署名を検証
                              ├─ イベントをパース
                              ├─ イベントIDで重複排除
                              ├─ イベントタイプでルーティング：
                              │
                              │  subscription.updated
                              │  → テナントプランを更新
                              │  → billing.plan_changed webhookを発火
                              │
                              │  subscription.deleted
                              │  → テナントを停止
                              │  → tenant.suspended webhookを発火
                              │
                              │  invoice.payment_failed
                              │  → テナントに猶予期間フラグ
                              │  → テナント管理者に通知
                              │
                              └─ Stripeに200を返す
```

### voltaが処理するStripeイベント

| Stripeイベント | voltaのアクション |
|-------------|-----------------|
| `customer.subscription.created` | [テナント](tenant.md)プランを有効化 |
| `customer.subscription.updated` | プランティアを更新、[Webhook](webhook.md)発火 |
| `customer.subscription.deleted` | テナントを[停止](suspension.md)、Webhook発火 |
| `invoice.payment_failed` | 猶予期間開始、管理者に通知 |
| `invoice.paid` | 支払い失敗フラグをクリア |
| `customer.deleted` | テナント停止、通知 |

### インジェスションエンドポイントの設定

```yaml
# volta-config.yaml
billing:
  provider: stripe
  webhook_endpoint: /webhooks/stripe
  webhook_secret: ${STRIPE_WEBHOOK_SECRET}
  event_tolerance_seconds: 300
  handled_events:
    - customer.subscription.created
    - customer.subscription.updated
    - customer.subscription.deleted
    - invoice.payment_failed
    - invoice.paid
```

### インジェスション + Outbox統合

voltaがStripeイベントを取り込んでテナントのプランを更新すると、[Outbox](outbox-pattern.md)にも書き込んでアプリに通知します：

```
  Stripe → volta（インジェスション） → DB更新 + Outbox書き込み
                                              ↓
                                   Outboxワーカー → あなたのアプリ（Webhook）

  例：
    Stripeが言う：「AcmeがProにアップグレード」
    volta：
      1. acmeテナントを更新：plan = "pro"
      2. Outboxに書き込み：billing.plan_changed
    Outboxワーカー：
      3. your-app.com/webhooks/volta にPOST
         {"event": "billing.plan_changed",
          "tenant_id": "acme-uuid",
          "new_plan": "pro"}
```

---

## よくある間違いと攻撃

### 間違い1：Webhook署名を検証しない

インバウンドWebhookの署名検証を省略すると、攻撃者が偽のイベントを送信できます。処理前に常に署名を検証してください。

### 間違い2：確認応答前に処理する

200を返す前に重い処理をする実装があります。処理が遅いと、送信者がタイムアウトしてリトライし、重複処理が発生します。素早く200を返し、非同期で処理してください。

### 間違い3：冪等性がない

重複排除がないと、リトライされたイベントが重複した副作用を引き起こします（例：「プランアップグレード」メールを2回送信）。処理前に常にイベントIDを確認してください。

### 間違い4：イベント処理のハードコーディング

イベントルーターで`default: throw`にすると、新しい未処理のイベントタイプがインジェスションエンドポイントをクラッシュさせます。不明なイベントはログに記録して無視すべきです。

### 攻撃：Webhook偽造

攻撃者がインジェスションエンドポイントを発見し、偽のStripeイベントを送ってテナントプランを操作する。防御：常にStripe署名を検証、可能であればエンドポイントをStripeのIPレンジに制限。

### 攻撃：リプレイ攻撃

攻撃者が正当なWebhookをキャプチャして再送する。防御：イベントのタイムスタンプを確認（Stripeは署名に含む）。5分以上古いイベントを拒否。

---

## さらに学ぶために

- [webhook.md](webhook.md) -- アウトバウンドWebhook（送信側）。
- [billing.md](billing.md) -- Stripe課金統合の詳細。
- [outbox-pattern.md](outbox-pattern.md) -- 取り込まれたイベントがアウトバウンドWebhookをどうトリガーするか。
- [retry.md](retry.md) -- プロバイダが失敗したWebhook配信をどうリトライするか。
- [payload.md](payload.md) -- 取り込まれたイベントのデータコンテンツ。
