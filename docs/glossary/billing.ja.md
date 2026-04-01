# 課金（ビリング）

[English version](billing.md)

---

## これは何？

課金（ビリング）は、サービスの利用に対して顧客に料金を請求するプロセスです。SaaSの文脈では、サブスクリプションプラン（free、pro、enterprise）、支払い処理、請求書生成、支払い失敗の処理が含まれます。volta-auth-proxyは直接支払いを処理しません。Stripeと[Webhook](webhook.md)経由で統合し、各[テナント](tenant.md)の課金状態を把握します。

ジムと決済会社の関係のようなものです。ジム（あなたのアプリ）はトレーニング器具とトレーナーを提供します。決済会社（Stripe）は会費、カード処理、督促を処理します。ジムのフロントデスクシステム（volta）は入場時に会員ステータスを確認します。支払いが失敗した場合、フロントデスクはカードの更新を求めてから入場を許可します。ジム自体はクレジットカードを処理しません。

voltaの課金における役割は受動的です：Stripeイベントを[取り込み](ingestion.md)、課金状態の変更に基づいてアクション（プランの有効化、テナントの[停止](suspension.md)、[Webhook](webhook.md)の発火）を実行します。

---

## なぜ重要なのか？

認証レイヤーに課金統合がないと、危険なギャップが生じます：

```
  課金認識のない認証：
  ┌──────────────────────────────────────────────┐
  │  Stripe：「Acmeのサブスクリプションがキャンセル」│
  │                                               │
  │  あなたのアプリ：何も知らない。                  │
  │  Acmeの50人全員がまだフルアクセス。              │
  │  無料でサービスを提供している。                  │
  │                                               │
  │  数ヶ月後：「なぜ収益が下がっている？」          │
  └──────────────────────────────────────────────┘

  課金認識のある認証（volta）：
  ┌──────────────────────────────────────────────┐
  │  Stripe：「Acmeのサブスクリプションがキャンセル」│
  │                                               │
  │  volta：Acmeテナントを停止。                    │
  │  50人全員に「更新してください」と表示。           │
  │  voltaがアプリにWebhookを発火。                 │
  │  アプリは猶予期間を提供可能。                   │
  └──────────────────────────────────────────────┘
```

課金認識のある認証は、アクセスが支払い状態と一致することを保証します。

---

## どう動くのか？

### Stripe Webhook統合

課金状態が変更されるとStripeがvoltaにイベントを送信します：

```
  Stripe                    volta-auth-proxy            あなたのアプリ
  ======                    ================            ============

  顧客がサインアップして
  「Pro」プランを選択
  ─────────────────────────►
  subscription.created       1. Stripeカスタマーを
                               voltaテナントにマッピング
                            2. テナントplan = "pro"に設定
                            3. Outboxに書き込み：
                               billing.plan_changed
                            ──────────────────────────►
                                                       webhook：
                                                       billing.plan_changed
                                                       「AcmeがProになった」

  3ヶ月後...
  支払い失敗
  ─────────────────────────►
  invoice.payment_failed     1. テナントにフラグ：
                               payment_failed = true
                            2. 猶予期間設定（3日）
                            3. テナント管理者に通知
                            ──────────────────────────►
                                                       webhook：
                                                       billing.payment_failed
                                                       「Acme支払い失敗」

  猶予期間終了、
  支払い未受領
  ─────────────────────────►
  subscription.deleted       1. テナント停止
                            2. 全セッション取り消し
                            3. Outboxに書き込み：
                               tenant.suspended
                            ──────────────────────────►
                                                       webhook：
                                                       tenant.suspended
                                                       「Acme停止」
```

### voltaの課金状態

```
  ┌──────────┐     ┌──────────────┐     ┌──────────────┐
  │  ACTIVE  │────►│ GRACE_PERIOD │────►│  SUSPENDED   │
  │          │     │              │     │              │
  │ プラン   │     │ 支払い失敗、 │     │ 支払い未受領、│
  │ アクティブ│     │ 3日間の     │     │ アクセス      │
  │ 問題なし │     │ 猶予期間    │     │ ブロック      │
  └──────────┘     └──────────────┘     └──────────────┘
       ▲                  │                    │
       │                  │                    │
       └──────────────────┴────────────────────┘
               支払い受領
              (subscription.updated)
```

### プランベースの機能制御

voltaはプランベースのアクセス制御を適用できます：

```
  テナント：Acme Corp
  プラン："pro"

  プラン制限：
  ┌────────────────────────────────────────────┐
  │  プラン  │ メンバー │ M2Mクライアント │ Webhook│
  │──────────┼─────────┼──────────────┼────────│
  │  free    │    5    │      1       │    2   │
  │  pro     │   50    │     10       │   20   │
  │  enterprise│ 無制限 │    無制限    │  無制限 │
  └────────────────────────────────────────────┘
```

voltaは[プロビジョニング](provisioning.md)、[M2M](m2m.md)クライアント作成、[Webhook](webhook.md)登録時にこれらの制限をチェックし、制限に達すると402 Payment Requiredを返します。

---

## volta-auth-proxyではどう使われている？

### Stripe Webhookインジェスション

voltaは`/webhooks/stripe`でStripe Webhookを受信します：

```yaml
# volta-config.yaml
billing:
  provider: stripe
  webhook_endpoint: /webhooks/stripe
  webhook_secret: ${STRIPE_WEBHOOK_SECRET}
  grace_period_days: 3
  plans:
    free:
      max_members: 5
      max_m2m_clients: 1
      max_webhooks: 2
    pro:
      max_members: 50
      max_m2m_clients: 10
      max_webhooks: 20
    enterprise:
      max_members: -1  # 無制限
      max_m2m_clients: -1
      max_webhooks: -1
```

### Stripeカスタマーとテナントのマッピング

voltaはメタデータフィールドでStripeカスタマーをテナントにマッピングします：

```
  Stripe Customerオブジェクト：
  {
    "id": "cus_acme123",
    "metadata": {
      "volta_tenant_id": "acme-uuid"
    }
  }

  voltaがStripeイベントを受信すると：
    1. イベントからカスタマーIDを抽出
    2. カスタマーメタデータからvolta_tenant_idを検索
    3. そのテナントに課金アクションを適用
```

### voltaが処理するイベント

| Stripeイベント | voltaのアクション |
|-------------|-----------------|
| `customer.subscription.created` | プラン有効化、制限設定 |
| `customer.subscription.updated` | プランティア更新、[Webhook](webhook.md)発火 |
| `customer.subscription.deleted` | 猶予期間開始または[停止](suspension.md) |
| `invoice.payment_failed` | 猶予期間開始、管理者に通知 |
| `invoice.paid` | 支払い失敗をクリア、アクセス復元 |
| `customer.deleted` | テナント停止、通知 |

### 認証フローでの課金状態

voltaは認証中に課金状態をチェックします：

```java
// ForwardAuthHandler内
Tenant tenant = tenantService.get(principal.tenantId());

if (tenant.isSuspended() &&
    "billing".equals(tenant.suspendedReason())) {
    ctx.status(402).json(Map.of(
        "error", "payment_required",
        "message", "支払い方法を更新してください",
        "billing_portal_url", stripeBillingPortalUrl(tenant)
    ));
    return;
}
```

### アウトバウンド課金Webhook

課金状態が変更されると、voltaは[Outbox](outbox-pattern.md)経由でアプリにWebhookを発火します：

```json
{
  "event": "billing.plan_changed",
  "timestamp": "2026-04-01T12:00:00Z",
  "tenant_id": "acme-uuid",
  "data": {
    "old_plan": "free",
    "new_plan": "pro",
    "effective_at": "2026-04-01T12:00:00Z"
  }
}
```

---

## よくある間違いと攻撃

### 間違い1：猶予期間を実装しない

最初の支払い失敗で即座に停止するのは攻撃的です。クレジットカードは期限切れになり、銀行が正当な請求にフラグを立て、自動更新が無害な理由で失敗します。通知付きの3日間の猶予期間が標準です。

### 間違い2：クライアントが報告するプラン状態を信頼する

フロントエンドがローカルでプランをチェックする場合（「私はProか？」）、攻撃者がチェックを変更できます。プランの適用はvoltaの認証レイヤーでサーバー側で行わなければなりません。

### 間違い3：Webhook署名検証なし

StripeのWebhook署名を検証しないと、攻撃者が偽の課金イベントを送ってテナントをダウングレードまたは停止できます。常にStripe Webhookシークレットで検証してください。

### 間違い4：サブスクリプションキャンセル時にデータを削除する

サブスクリプションがキャンセルされたら、テナントを停止してください。データを削除しないでください。顧客が再購読するかもしれませんし、データのエクスポートが必要かもしれません。

### 攻撃：偽の課金Webhook

攻撃者がvoltaの`/webhooks/stripe`エンドポイントを発見し、偽の`subscription.deleted`イベントを送って競合他社のテナントを停止させる。防御：Stripe署名検証がすべての偽造イベントをブロック。

### 攻撃：プラン制限のバイパス

攻撃者が高速な同時リクエストでプランが許可する以上のM2Mクライアントを作成しようとする。防御：voltaが行ロックを使ったデータベーストランザクション内でプラン制限をチェックし、レースコンディションを防止。

---

## さらに学ぶために

- [ingestion.md](ingestion.md) -- voltaがStripe Webhookをどう受信・処理するか。
- [suspension.md](suspension.md) -- 課金失敗でトリガーされるテナント停止。
- [webhook.md](webhook.md) -- アプリに転送される課金イベント。
- [outbox-pattern.md](outbox-pattern.md) -- 課金Webhookの確実な配信。
- [tenant.md](tenant.md) -- 課金サブスクリプションに関連付けられたエンティティ。
- [Stripe Webhook Docs](https://stripe.com/docs/webhooks) -- Stripeの公式Webhookドキュメント。
