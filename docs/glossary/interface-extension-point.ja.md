# インターフェース / 拡張ポイント

[English version](interface-extension-point.md)

---

## これは何？

インターフェース（プログラミングの意味で）は「このコンポーネントはこれができなければならない」という契約で、その方法は指定しません。拡張ポイントは、コード内で意図的に代替実装の余地を残した場所です。

電源コンセントのようなものです。コンセント（インターフェース）は形状と電圧を定義します。コンセントの形に合うデバイス（インターフェースを実装するもの）は何でも接続できます：ランプ、スマホの充電器、トースター。コンセントはデバイスが何であるかを気にしません -- プラグが合うかどうかだけを気にします。コンセントは拡張ポイントです：異なるものを接続できるように設計された場所です。

---

## なぜ重要なのか？

volta-auth-proxyは、将来的に代替実装が必要になるかもしれないコンポーネントにJavaインターフェースを拡張ポイントとして使用しています。これは重要なアーキテクチャの決定です：**今日すべてを実装せずに、拡張性のために設計する。**

`SessionStore`、`AuditSink`、`NotificationService`インターフェースはこのパターンの例です。各インターフェースはコンポーネントが何をすべきかを定義し、voltaは特定の実装を出荷しつつ、他の実装への扉を開いたままにしています。

---

## Javaインターフェースが拡張ポイントとして機能する仕組み

Javaインターフェースは、実装のないメソッドのリストです：

```java
// 契約：「SessionStoreはこれらのことができなければならない」
interface SessionStore {
    void createSession(UUID sessionId, UUID userId, ...);
    Optional<SessionRecord> findSession(UUID sessionId);
    void touchSession(UUID sessionId, Instant expiresAt);
    void revokeSession(UUID sessionId);
    void revokeAllSessions(UUID userId);
    List<SessionRecord> listUserSessions(UUID userId);
    int countActiveSessions(UUID userId);
    // ...
}
```

次に、具体的な実装が「どうやるか」を提供します：

```java
// 実装A: PostgreSQLにセッションを保存
class PostgresSessionStore implements SessionStore {
    @Override
    public void createSession(...) {
        // INSERT INTO sessions ...
    }
    // ... すべてのメソッドをSQLで実装
}

// 実装B: Redisにセッションを保存
class RedisSessionStore implements SessionStore {
    @Override
    public void createSession(...) {
        // jedis.set("volta:session:" + sessionId, ...)
    }
    // ... すべてのメソッドをRedisコマンドで実装
}
```

voltaのコードの残りは`SessionStore`とだけ話します -- PostgreSQLやRedisを直接参照することはありません。つまり、セッションストレージの切り替えに認証ロジックの変更がゼロで済みます。

---

## voltaの拡張ポイント

### SessionStore

**何をするか：** ユーザーセッションの作成、読み取り、更新、無効化。

**実装：**
- `PostgresSessionStore` -- `sessions`テーブルにセッションを保存。デフォルト。
- `RedisSessionStore` -- より高いスループットのためにRedisにセッションを保存。

**選択：** `.env`で`SESSION_STORE=postgres`または`SESSION_STORE=redis`

```
  voltaのコード：                 SessionStoreインターフェース
  ┌─────────────────┐          ┌──────────────────────┐
  │ AuthHandler      │────────►│ createSession()       │
  │ ForwardAuth      │         │ findSession()         │
  │ SessionManager   │         │ touchSession()        │
  │                  │         │ revokeSession()       │
  └─────────────────┘         └──────────┬───────────┘
                                         │
                              ┌──────────┴───────────┐
                              │                      │
                    ┌─────────▼──────┐    ┌─────────▼──────┐
                    │ Postgres       │    │ Redis          │
                    │ SessionStore   │    │ SessionStore   │
                    └────────────────┘    └────────────────┘
```

### AuditSink

**何をするか：** 監査イベント（ログイン、ログアウト、ロール変更など）の発行。

**実装：**
- `NoopAuditSink` -- 何もしない。デフォルト（監査イベントはメインコードパスでPostgresの監査テーブルに記録）。
- `KafkaAuditSink` -- Kafkaトピックにイベントをストリーミング。
- `ElasticsearchAuditSink` -- Elasticsearchにイベントをインデックス。

**選択：** `AUDIT_SINK=postgres`または`AUDIT_SINK=kafka`または`AUDIT_SINK=elasticsearch`

### NotificationService

**何をするか：** メール通知の送信（招待メール）。

**実装：**
- No-opラムダ -- 何もしない。デフォルト（招待はリンク共有を使用）。
- `SmtpNotificationService` -- SMTPサーバー経由で送信。
- `SendGridNotificationService` -- SendGrid API経由で送信。

**選択：** `NOTIFICATION_CHANNEL=none`または`NOTIFICATION_CHANNEL=smtp`または`NOTIFICATION_CHANNEL=sendgrid`

---

## なぜすべてを実装せずに拡張性のために設計するか

### 誘惑：今すぐすべて作る

セッションストレージを設計するとき、こう思うかもしれません：

> 「初日からPostgreSQL、Redis、Memcached、DynamoDB、MongoDB、Cassandraをサポートすべきだ。」

これは罠です。各実装は書いて、テストして、ドキュメント化して、メンテナンスするコードです。6つのセッションストアをサポートして1つしか使わないなら、本番でテストされない5つ分のコードを抱えます。

### voltaのアプローチ：今はインターフェース、後で実装

voltaの戦略は：

1. **インターフェースを定義** -- セッションストアが何をすべきか決める
2. **今必要なものを実装** -- PostgreSQL（Phase 1）
3. **需要があったら次を実装** -- Redis（Phase 2）
4. **扉を開けておく** -- 誰でもインターフェースを実装してDynamoDB、Memcachedなどを追加可能

これはYAGNI原則（「それは必要にならない」）とOpen/Closed原則（「拡張に開いて、修正に閉じる」）の組み合わせです：

```
  Phase 1:
  SessionStore ←── PostgresSessionStore    ← 今必要なもの

  Phase 2:
  SessionStore ←── PostgresSessionStore
               ←── RedisSessionStore       ← 必要になった時に追加

  将来（必要なら）：
  SessionStore ←── PostgresSessionStore
               ←── RedisSessionStore
               ←── DynamoSessionStore      ← あなたやコミュニティが追加
```

インターフェースはPhase 1から存在していました。しかしDynamoDB実装は、誰かが実際に必要になるまで書かれませんでした。無駄な努力ゼロ。

---

## ファクトリメソッドパターン

voltaは設定に基づいて正しい実装を選択するため、インターフェースに静的な`create()`メソッドを使います：

```java
interface SessionStore {
    // ... メソッド ...

    static SessionStore create(AppConfig config, SqlStore store) {
        if ("redis".equalsIgnoreCase(config.sessionStore())) {
            return new RedisSessionStore(config.redisUrl());
        }
        return new PostgresSessionStore(store);
    }
}
```

これがクリーンな理由：
- コードの残りは`SessionStore.create(config, store)`を呼ぶだけで、どの実装を受け取るか知らない
- 新しい実装の追加は1つの`if`分岐と1つのクラスの追加を意味する
- DIフレームワークなし、XML設定なし、アノテーションスキャンなし

---

## さらに学ぶために

- [java.ja.md](java.ja.md) -- これらのインターフェースが書かれているプログラミング言語。
- [session-storage-strategies.ja.md](session-storage-strategies.ja.md) -- SessionStore実装の詳細。
