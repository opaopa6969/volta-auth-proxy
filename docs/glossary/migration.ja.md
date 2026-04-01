# マイグレーション（データベースマイグレーション）

[English version](migration.md)

---

## これは何？

データベースマイグレーションとは、データベーススキーマへのバージョン管理された段階的な変更のことです。SQLコマンドで手動でデータベースを変更する代わりに、各変更を記述したマイグレーションファイルを書き、順番に番号を付け、マイグレーションツールに自動で適用させます。

データベースのためのレシピブックだと考えてください。各レシピ（マイグレーションファイル）にはこう書いてあります：「ステップ3で、'users'テーブルに'phone'カラムを追加する。」マイグレーションツールは前回の続きから本を読み、新しいステップを適用し、どのステップまで進んだか記憶します。新しいデータベースをセットアップするなら、ステップ1から始めてすべてのレシピを順番に実行し、本番データベースとまったく同じ構造になります。

手動で`ALTER TABLE`を実行するのとは違います。マイグレーションはバージョン管理され、再現可能で、テスト可能だからです。すべての開発者とすべての環境がまったく同じデータベース構造を得ます。

---

## なぜ重要なのか？

- **マイグレーションなしではスキーマ変更がカオス。** 「本番にあのカラム追加した？」「たぶん…確認するよ…」この会話は起こるべきではない。
- **マイグレーションはチーム協業を可能にする。** 複数の開発者がお互いの変更を踏まずにスキーマ変更できる。
- **マイグレーションは安全なデプロイを可能にする。** [CI/CD](ci-cd.ja.md)パイプラインが新コード起動前にマイグレーションを実行し、スキーマとコードの一致を保証。
- **マイグレーションはデータベースのgit履歴。** 何がいつなぜ変わったかが正確にわかる。
- **ロールバックが可能。** マイグレーションが問題を起こしたら、何が変わったか正確にわかり、逆マイグレーションを書ける。

---

## どう動くのか？

### マイグレーションファイルの命名

[Flyway](flyway.ja.md)（voltaが使うマイグレーションツール）はバージョンプレフィックス付きのファイル名を期待します：

```
  src/main/resources/db/migration/
  ├── V1__create_users_table.sql
  ├── V2__create_tenants_table.sql
  ├── V3__create_memberships_table.sql
  ├── V4__create_sessions_table.sql
  ├── V5__create_invitations_table.sql
  ├── V6__add_audit_log.sql
  └── V7__add_session_index.sql

  形式: V{番号}__{説明}.sql
        │          │
        │          └─ 人が読める説明
        └─ バージョン番号（順序を決定）
```

### Flywayがマイグレーションを適用する方法

```
  ┌──────────────────────────────────────────────┐
  │  Flywayマイグレーションプロセス                │
  │                                               │
  │  1. flyway_schema_historyテーブルを読む        │
  │     (どのマイグレーションが実行済みか追跡)      │
  │                                               │
  │  2. ディスク上のマイグレーションファイルと比較   │
  │                                               │
  │  3. 新しいマイグレーションを順番に適用          │
  └──────────────────────────────────────────────┘

  flyway_schema_historyテーブル:
  ┌─────────┬───────────────────────┬─────────┐
  │ version │ description           │ success │
  ├─────────┼───────────────────────┼─────────┤
  │ 1       │ create_users_table    │ true    │
  │ 2       │ create_tenants_table  │ true    │
  │ 3       │ create_memberships    │ true    │
  │ 4       │ create_sessions       │ true    │
  └─────────┴───────────────────────┴─────────┘

  ディスク上の新ファイル: V5__create_invitations.sql
  → FlywayがV5が履歴にないことを検知
  → V5を実行
  → V5を履歴テーブルに記録
```

### マイグレーションファイルの例

```sql
-- V1__create_users_table.sql
CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL UNIQUE,
    name       VARCHAR(255),
    google_sub VARCHAR(255) UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
```

```sql
-- V5__create_invitations_table.sql
CREATE TABLE invitations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    email      VARCHAR(255) NOT NULL,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','MEMBER','VIEWER')),
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invited_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL
);
```

### マイグレーションのライフサイクル

```
  開発者が V7__add_phone_to_users.sql を書く
       │
       ▼
  ローカルでテスト（アプリ起動時にFlywayが実行）
       │
       ▼
  gitにコミット＋プッシュ
       │
       ▼
  CI/CDパイプラインがテスト実行（テストDBでFlywayが実行）
       │
       ▼
  ステージングにデプロイ（ステージングDBでFlywayが実行）
       │
       ▼
  本番にデプロイ（本番DBでFlywayが実行）
       │
       ▼
  V7がすべての環境に同一に適用される
```

---

## volta-auth-proxy ではどう使われている？

### Flyway統合

voltaはアプリケーション起動時、JavalinがHTTPリクエストの受け付けを開始する前に、Flywayマイグレーションを自動実行します：

```java
// 簡略化した起動シーケンス
public static void main(String[] args) {
    // 1. まずマイグレーションを実行
    Flyway flyway = Flyway.configure()
        .dataSource(databaseUrl, dbUser, dbPassword)
        .load();
    flyway.migrate();

    // 2. それからWebサーバーを起動
    Javalin app = Javalin.create();
    // ... ルート登録 ...
    app.start(8080);
}
```

これが意味すること：
- 新規デプロイで自動的にすべてのテーブルが作成される
- アップグレードで新しいマイグレーションのみが自動適用される
- アプリがスキーマ不一致の状態で起動することがない

### voltaのマイグレーションファイル

voltaのマイグレーションはマルチテナント認証の完全な[スキーマ](schema.ja.md)を構築します：

```
  V1  → users、tenants、memberships（コアアイデンティティ）
  V2  → sessions（ログイン追跡）
  V3  → invitations（テナントオンボーディング）
  V4  → rate_limits（乱用防止）
  V5  → audit_log（セキュリティ追跡）
  ...
```

### voltaが従うマイグレーションルール

1. **既存のマイグレーションは決して変更しない。** V3がデプロイされたら、その内容は凍結。変更はV4以降で行う。
2. **本番では常に追加、カラムの削除はしない。** ローリングデプロイ中に古いコードがまだ参照しているかもしれない。
3. **安全な場合は`IF NOT EXISTS`を使う。** マイグレーションが誤って2回実行された場合の失敗を防ぐ。
4. **本番データのコピーに対してマイグレーションをテストする。** 空のDBでは動くが、制約のある実データでは失敗するかもしれない。

---

## よくある間違いと攻撃

### 間違い1：デプロイ済みマイグレーションの編集

V3が適用された後に変更すると、Flywayがチェックサムの不一致を検出し、起動を拒否します。これは設計通りで、不整合なスキーマから保護してくれます。

### 間違い2：スキーマ変更のマイグレーション作成忘れ

Javaコードを新しいカラムを使うように変更したのに、マイグレーションを書き忘れると：ノートPC（手動で`ALTER TABLE`を実行した）では動くが、本番で壊れます。

### 間違い3：計画なしの長時間マイグレーション

1000万行のテーブルに対する`ALTER TABLE users ADD COLUMN phone VARCHAR(255)`はテーブルをロックします。本番では、その間ログインできません。まずNULL許容カラムを追加し、後からバックフィルする戦略を使いましょう。

### 間違い4：バックアップなしの破壊的マイグレーション

マイグレーション内の`DROP TABLE sessions`はすべてのアクティブセッションを永久に削除します。本番で破壊的マイグレーションを実行する前に必ずバックアップを取りましょう。

### 間違い5：バージョン番号の飛ばし

V3からV5へ（V4を飛ばして）は技術的には動きますが、将来の開発者を混乱させます。バージョンは連番を保ちましょう。

---

## さらに学ぶ

- [flyway.ja.md](flyway.ja.md) -- voltaが使うマイグレーションツール。
- [schema.ja.md](schema.ja.md) -- マイグレーションが構築するデータベース構造。
- [database.ja.md](database.ja.md) -- マイグレーションが実行されるPostgreSQLデータベース。
- [deployment.ja.md](deployment.ja.md) -- デプロイプロセスにマイグレーションがどう組み込まれるか。
- [ci-cd.ja.md](ci-cd.ja.md) -- 自動パイプラインでのマイグレーション実行。
