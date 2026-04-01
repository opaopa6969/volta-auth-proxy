# スキーマ

[English version](schema.md)

---

## これは何？

スキーマとは、データベースの構造または設計図のことです -- どんなテーブルが存在し、各テーブルにどんなカラムがあり、各カラムがどんな型のデータを保持し、テーブル同士がどう関連するかを定義します。スキーマには実際のデータは含まれません。データが入るコンテナの形を記述するものです。

ラベル付きの引き出しとフォルダがあるファイリングキャビネットで考えてみましょう。書類を入れる前に、誰かが決める必要があります：「この引き出しは従業員記録用、この引き出しは請求書用。各従業員記録には名前（テキスト）、入社日（日付）、給与（数値）がある。」その整理計画がスキーマです。実際の従業員記録がデータです。

PostgreSQLのようなリレーショナルデータベースでは、スキーマは`CREATE TABLE`のようなSQL文で定義されます。volta-auth-proxyのスキーマには、ユーザー、テナント、セッション、ロール、招待に関するすべてを格納する9つのテーブルがあります。

---

## なぜ重要なのか？

- **スキーマは契約。** アプリケーションコードは特定のテーブルとカラムの存在を前提とする。スキーマがコードの変更なしに変わると、壊れる。
- **データの整合性はスキーマに依存。** `NOT NULL`、`UNIQUE`、`FOREIGN KEY`などの制約が不正なデータのDB投入を防ぐ。
- **パフォーマンスはスキーマに依存。** インデックス、カラム型、テーブル関係がクエリの速度を決める。
- **セキュリティはスキーマから始まる。** [行レベルセキュリティ](row-level-security.ja.md)、テナント分離、アクセスパターンはスキーマ設計で形作られる。
- **スキーマ変更はリスクが高い。** カラムの名前変更や型の変更は、それを参照するすべてのクエリを壊す可能性がある。

---

## どう動くのか？

### スキーマは構造を定義する

```sql
-- これはスキーマ定義（簡略化）
CREATE TABLE users (
    id          UUID PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    subdomain   VARCHAR(63) NOT NULL UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### スキーマ vs データ

```
  スキーマ（構造）:                 データ（内容）:
  ┌──────────────────────┐        ┌──────────────────────────────┐
  │ TABLE: users         │        │ id: a1b2c3                   │
  │ ├─ id: UUID (PK)    │        │ email: alice@example.com     │
  │ ├─ email: VARCHAR    │        │ name: Alice                  │
  │ ├─ name: VARCHAR     │        │ created_at: 2025-01-15       │
  │ └─ created_at: TS    │        ├──────────────────────────────┤
  │                      │        │ id: d4e5f6                   │
  │                      │        │ email: bob@example.com       │
  │                      │        │ name: Bob                    │
  │                      │        │ created_at: 2025-02-20       │
  └──────────────────────┘        └──────────────────────────────┘
    形を定義                         実際のレコードを含む
    マイグレーションで変更            INSERT/UPDATEで変更
```

### スキーマの主要概念

| 概念 | 何をするか | 例 |
|------|----------|-----|
| **テーブル** | 関連レコードの集合 | `users`、`tenants`、`sessions` |
| **カラム** | レコード内の単一フィールド | `email`、`created_at` |
| **主キー (PK)** | 各行を一意に識別 | `users.id` |
| **外部キー (FK)** | テーブル間をリンク | `memberships.user_id → users.id` |
| **インデックス** | カラムの検索を高速化 | `users.email`のインデックス |
| **制約** | データが従うべきルール | `NOT NULL`、`UNIQUE`、`CHECK` |
| **型** | カラムが保持するデータの種類 | `UUID`、`VARCHAR`、`TIMESTAMP` |

### テーブルの関連

```
  ┌──────────┐     ┌──────────────┐     ┌──────────┐
  │  users   │     │ memberships  │     │ tenants  │
  │          │     │              │     │          │
  │ id (PK)  │◀────│ user_id (FK) │     │ id (PK)  │
  │ email    │     │ tenant_id(FK)│────▶│ name     │
  │ name     │     │ role         │     │ subdomain│
  └──────────┘     └──────────────┘     └──────────┘

  ユーザーは多数のメンバーシップを持つ。
  テナントは多数のメンバーシップを持つ。
  メンバーシップは1ユーザーを1テナントにロール付きで接続。
  これは結合テーブルによる「多対多」リレーション。
```

---

## volta-auth-proxy ではどう使われている？

### voltaの9テーブルスキーマ

volta-auth-proxyは9つのPostgreSQLテーブルを使います：

```
  ┌─────────────────────────────────────────────────────┐
  │                voltaスキーマ                          │
  │                                                      │
  │  ┌──────────┐  ┌──────────────┐  ┌──────────┐      │
  │  │  users   │──│ memberships  │──│ tenants  │      │
  │  └──────────┘  └──────────────┘  └──────────┘      │
  │       │                                │             │
  │       │         ┌──────────────┐       │             │
  │       └─────────│  sessions    │       │             │
  │                 └──────────────┘       │             │
  │                                        │             │
  │  ┌──────────────┐  ┌──────────────┐   │             │
  │  │ invitations  │──│ (tenant FK)  │───┘             │
  │  └──────────────┘  └──────────────┘                 │
  │                                                      │
  │  + rate_limits、audit_log、schema_version 等        │
  └─────────────────────────────────────────────────────┘
```

### 主要テーブルとその目的

| テーブル | 目的 | 主要カラム |
|---------|------|----------|
| `users` | 認証済み全ユーザー | `id`、`email`、`name`、`google_sub` |
| `tenants` | 各テナント/組織 | `id`、`name`、`subdomain` |
| `memberships` | ユーザー-テナント-ロールのマッピング | `user_id`、`tenant_id`、`role` |
| `sessions` | アクティブなログインセッション | `id`、`user_id`、`token_hash`、`expires_at` |
| `invitations` | テナント参加の保留中の招待 | `id`、`tenant_id`、`email`、`role`、`status` |

### スキーマとRBAC

membershipsテーブルの[ロール](role.ja.md)カラムがvoltaのRBAC階層を強制します：

```sql
-- roleカラムはCHECK制約を使用
CREATE TABLE memberships (
    user_id    UUID REFERENCES users(id),
    tenant_id  UUID REFERENCES tenants(id),
    role       VARCHAR(20) CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER')),
    PRIMARY KEY (user_id, tenant_id)
);
```

### マイグレーションによるスキーマ進化

スキーマは手動で変更しません。すべての変更は[Flywayマイグレーション](migration.ja.md)を通じて行います：

```
  V1__init.sql           → 初期テーブルを作成
  V2__add_invitations.sql → 招待テーブルを追加
  V3__add_audit_log.sql   → 監査ログテーブルを追加
  ...
```

---

## よくある間違いと攻撃

### 間違い1：本番でスキーマを手動変更

本番データベースで直接`ALTER TABLE`を実行してはいけません。変更がバージョン管理され、テスト済みで、再現可能になるよう[Flywayマイグレーション](migration.ja.md)を使いましょう。

### 間違い2：インデックスの欠如

`sessions.token_hash`にインデックスがないと、すべてのForwardAuthチェックがフルテーブルスキャンになります。100セッションでは動きますが、100,000で崩壊します。

### 間違い3：外部キー制約がない

`FOREIGN KEY`制約がないと、削除されたユーザーや存在しないテナントを指すメンバーシップが存在できてしまいます。スキーマが参照整合性を強制すべきです。

### 間違い4：間違ったカラム型の使用

UUIDを`UUID`型ではなく`VARCHAR(36)`として格納すると、スペースを浪費し比較が遅くなります。`VARCHAR(255)`で合理的な制限を強制できるのに`TEXT`を使うのも一般的な問題です。

### 間違い5：環境間のスキーマドリフト

誰かが一つの環境で手動`ALTER TABLE`を実行したために開発と本番で異なるスキーマがあると、デバッグが悪夢になります。Flywayがこれを防ぎます。

---

## さらに学ぶ

- [migration.ja.md](migration.ja.md) -- スキーマ変更を安全に適用する方法。
- [database.ja.md](database.ja.md) -- スキーマをホストするPostgreSQLデータベース。
- [sql.ja.md](sql.ja.md) -- スキーマの定義とクエリに使う言語。
- [flyway.ja.md](flyway.ja.md) -- voltaが使うマイグレーションツール。
- [row-level-security.ja.md](row-level-security.ja.md) -- スキーマレベルのテナント分離。
