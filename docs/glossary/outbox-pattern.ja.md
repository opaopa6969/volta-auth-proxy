# Outboxパターン

[English version](outbox-pattern.md)

---

## これは何？

Outboxパターンは、データベース操作の後にメッセージ（[Webhook](webhook.md)など）を確実に送信するための信頼性テクニックです。データベースへの書き込みとメッセージ送信を2つの別々のステップで行う（それぞれが独立して失敗する可能性がある）代わりに、ビジネスデータと送信メッセージの両方を1つのトランザクションでデータベースに書き込みます。別のバックグラウンドワーカーが「outbox」テーブルから保留中のメッセージを読み取って配信します。

銀行の中にある郵便局のようなものです。お金を預けて誰かに領収書を送る必要があるとき、銀行は領収書を渡して自分で郵送させたりしません（忘れるかもしれないから）。代わりに、銀行は預金の記録と領収書を内部のOutboxに投函します。配達員がOutboxを定期的にチェックし、保留中の領収書をすべて配達します。今日配達員が病気でも、領収書はOutboxに安全に保管され、明日配達されます。

Outboxパターンは「デュアルライト問題」を解決します。データベースと外部システム（メッセージキューやWebhookエンドポイント）の同期を保つ課題です。

---

## なぜ重要なのか？

Outboxパターンがないと、2つのリスクのあるアプローチに直面します：

```
  アプローチ1：先に送信、後で保存（データ損失リスク）
  ┌────────────────────────────────────────────┐
  │  1. Webhook送信：user.created         ✓    │
  │  2. ユーザーをDBに保存               ✗    │
  │     （DB失敗！ユーザー未保存、              │
  │      だがWebhookは送信済み！）              │
  │     → 下流はユーザーが存在すると思う        │
  │     → しかしユーザーは存在しない            │
  └────────────────────────────────────────────┘

  アプローチ2：先に保存、後で送信（メッセージ損失リスク）
  ┌────────────────────────────────────────────┐
  │  1. ユーザーをDBに保存               ✓    │
  │  2. Webhook送信：user.created         ✗    │
  │     （ネットワーク失敗！ユーザー保存済み、   │
  │      だがWebhookは未送信！）               │
  │     → ユーザーはvoltaに存在する            │
  │     → 下流は知らないまま                   │
  └────────────────────────────────────────────┘

  Outboxパターン：両方をアトミックに保存、後で配信
  ┌────────────────────────────────────────────┐
  │  1. BEGIN TRANSACTION                      │
  │     a. ユーザーをDBに保存            ✓    │
  │     b. WebhookイベントをOutboxに保存  ✓    │
  │  2. COMMIT                            ✓    │
  │     （両方成功または両方失敗）               │
  │                                            │
  │  3. Outboxワーカー（非同期）：              │
  │     a. Outboxから保留イベントを読み取り     │
  │     b. Webhook送信                         │
  │     c. イベントを配信済みにマーク           │
  │     d. 送信失敗 → 後でリトライ             │
  └────────────────────────────────────────────┘
```

Outboxパターンは**ビジネス操作が成功すれば、メッセージは最終的に配信される**ことを保証します（at-least-once配信）。

---

## どう動くのか？

### Outboxテーブル

```sql
CREATE TABLE webhook_outbox (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    event_type  VARCHAR(100) NOT NULL,   -- 例："user.created"
    payload     JSONB NOT NULL,          -- Webhookボディ
    target_url  VARCHAR(2048) NOT NULL,  -- POSTする宛先
    status      VARCHAR(20) DEFAULT 'PENDING',
    attempts    INT DEFAULT 0,
    next_retry  TIMESTAMP,
    created_at  TIMESTAMP DEFAULT NOW(),
    delivered_at TIMESTAMP
);
```

### 書き込みパス

```
  アプリケーションスレッド：
  ┌──────────────────────────────────────────────┐
  │  BEGIN TRANSACTION                            │
  │                                               │
  │  INSERT INTO users (id, email, tenant_id)     │
  │  VALUES ('user-uuid', 'a@b.com', 'acme');    │
  │                                               │
  │  INSERT INTO webhook_outbox                   │
  │    (id, tenant_id, event_type, payload,       │
  │     target_url, status)                       │
  │  VALUES                                       │
  │    ('evt-uuid', 'acme', 'user.created',       │
  │     '{"user_id":"user-uuid","email":"a@b"}',  │
  │     'https://app.com/webhook', 'PENDING');    │
  │                                               │
  │  COMMIT                                       │
  └──────────────────────────────────────────────┘
```

### Outboxワーカー

```
  バックグラウンドワーカー（N秒ごとに実行）：
  ┌──────────────────────────────────────────────┐
  │                                               │
  │  1. SELECT * FROM webhook_outbox              │
  │     WHERE status = 'PENDING'                  │
  │     AND next_retry <= NOW()                   │
  │     ORDER BY created_at ASC                   │
  │     LIMIT 50                                  │
  │     FOR UPDATE SKIP LOCKED;                   │
  │                                               │
  │  2. 各イベントに対して：                       │
  │     a. target_urlにpayloadをPOST              │
  │        （HMAC署名付き）                        │
  │                                               │
  │     b. HTTP 2xxの場合：                        │
  │        UPDATE status='DELIVERED',             │
  │               delivered_at=NOW()              │
  │                                               │
  │     c. HTTP 4xx/5xxまたはタイムアウトの場合：   │
  │        UPDATE attempts=attempts+1,            │
  │               next_retry=NOW()+backoff(n)     │
  │                                               │
  │     d. attempts > max_retriesの場合：          │
  │        UPDATE status='FAILED'                 │
  │        （運用チームにアラート）                  │
  │                                               │
  └──────────────────────────────────────────────┘
```

### 指数バックオフ

```
  試行1：10秒後にリトライ
  試行2：30秒後にリトライ
  試行3：90秒後にリトライ
  試行4：270秒後にリトライ（約4.5分）
  試行5：810秒後にリトライ（約13.5分）
  ...
  最大：1時間後にリトライ
```

### FOR UPDATE SKIP LOCKED

`FOR UPDATE SKIP LOCKED`句は並行ワーカーにとって重要です。複数のOutboxワーカーインスタンスが同時に実行される場合、各ワーカーがブロックせずに異なる保留イベントを取得します：

```
  ワーカーA：イベント1, 2, 3を取得（ロック）
  ワーカーB：1, 2, 3をスキップ → イベント4, 5, 6を取得
  ワーカーC：1-6をスキップ → イベント7, 8, 9を取得
```

---

## volta-auth-proxyではどう使われている？

### voltaのWebhook Outboxワーカー

voltaはすべてのアウトバウンド[Webhook](webhook.md)にOutboxパターンを実装しています。ワーカーはvoltaプロセス内のスケジュールされたスレッドです（外部メッセージブローカー不要）：

```
  volta-auth-proxyプロセス：
  ┌────────────────────────────────────────────┐
  │                                            │
  │  ┌──────────────────┐                      │
  │  │  HTTPハンドラ     │                      │
  │  │  (Javalin)       │  ← ユーザーリクエスト │
  │  │                  │                      │
  │  │  user.create()   │                      │
  │  │    → INSERT user │                      │
  │  │    → INSERT outbox│                     │
  │  └──────────────────┘                      │
  │                                            │
  │  ┌──────────────────┐                      │
  │  │  Outboxワーカー   │  ← バックグラウンド  │
  │  │  (ScheduledExec) │     スレッド         │
  │  │                  │                      │
  │  │  poll()          │                      │
  │  │    → SELECT outbox│                     │
  │  │    → POST webhook │                     │
  │  │    → UPDATE status│                     │
  │  └──────────────────┘                      │
  │                                            │
  └────────────────────────────────────────────┘
```

### Outbox書き込みをトリガーする操作

| 操作 | Outboxイベント | Webhookペイロード |
|------|--------------|-----------------|
| ユーザーがテナントに参加 | `user.created` | user_id, email, roles |
| ユーザー削除 | `user.deleted` | user_id, tenant_id |
| ロール変更 | `user.role_changed` | user_id, old_roles, new_roles |
| [招待](invitation-code.md)承認 | `invitation.accepted` | invitation_id, user_id |
| テナント[停止](suspension.md) | `tenant.suspended` | tenant_id, reason |
| [課金](billing.md)プラン変更 | `billing.plan_changed` | tenant_id, old_plan, new_plan |
| [M2M](m2m.md)クライアント作成 | `m2m.client_created` | client_id, scopes |

### voltaがメッセージキューではなくOutboxを選んだ理由

| 機能 | メッセージキュー（Kafka, RabbitMQ） | Outboxパターン |
|------|----------------------------------|----------------|
| 追加インフラ | あり（ブローカーが必要） | なし（DBだけ） |
| DB書き込みとアトミック | 2PCまたはsagaが必要 | あり（同じトランザクション） |
| 順序保証 | パーティションベース | created_at順序 |
| 運用の複雑さ | 高 | 低 |
| voltaのスケールに適切か | 過剰 | 適切 |

voltaはシンプルさと自己完結性を優先します。OutboxパターンはPostgreSQLだけで済みます。Kafkaもなし、RabbitMQもなし、余計な可動部品なし。

---

## よくある間違いと攻撃

### 間違い1：Webhookを直接送信する（Outboxなし）

HTTPハンドラ内でWebhookを送信して失敗すると、イベントを失うか、ハンドラ自体に複雑なリトライロジックを追加する必要があります。Outboxが送信をハンドリングから分離します。

### 間違い2：データベーストランザクションを使わない

Outboxはビジネス書き込みとOutbox挿入が同じトランザクション内にある場合にのみ機能します。別々だと、デュアルライト問題に逆戻りです。

### 間違い3：受信側で重複を処理しない

Outboxはat-least-once配信を保証し、exactly-onceではありません。ワーカーが送信後、配信済みマーク前にクラッシュすると、受信側が同じイベントを2回受け取る可能性があります。受信側は冪等でなければなりません。

### 間違い4：デッドレター処理がない

最大リトライ後、失敗したイベントはデッドレターテーブルに移動し、アラートを発生させるべきです。イベントを静かに捨てるのはOutboxの目的を台無しにします。

### 攻撃：Outboxテーブルの操作

攻撃者がデータベースアクセスを取得すると、Outboxテーブルに偽のイベントを挿入し、ワーカーに偽造Webhookを送信させる可能性があります。防御：直接DBアクセスを制限、Outboxエントリを検証、HMACでペイロードに署名。

---

## さらに学ぶために

- [webhook.md](webhook.md) -- Outboxが配信するWebhook。
- [retry.md](retry.md) -- Outboxワーカーが使うリトライ戦略。
- [ingestion.md](ingestion.md) -- インバウンドWebhook処理（受信側）。
- [Microservices Patterns, Ch. 3](https://microservices.io/patterns/data/transactional-outbox.html) -- Chris RichardsonのOutboxパターンリファレンス。
