# データベースマイグレーション（Database Migration）

[English version](database-migration.md)

---

## 一言で言うと？

データベースマイグレーションとは、データベースの構造（テーブル、カラム、インデックス）を、管理された、再現可能で、追跡可能な方法で変更するバージョン付きスクリプトのことです。家具の組み立て説明書のように、番号順にステップを踏む必要があります。

---

## IKEAの家具のたとえ

IKEAの本棚を組み立てていると想像してください。説明書には番号付きのステップがあります：

- **ステップ1：** 側板を底板に取り付ける
- **ステップ2：** 1段目の棚を追加
- **ステップ3：** 2段目の棚を追加
- **ステップ4：** 背板を取り付ける

これらのステップは順番通りにやる必要があります。ステップ1の前にステップ3はできません。途中まで組み立てた状態でIKEAが更新版を出して新しいステップが追加されたら（「ステップ5：装飾用の天板を追加」）、中断したところから続けるだけです。

データベースマイグレーションも同じです：
- **各マイグレーション** = 番号付きの1ステップ（V1、V2、V3...）
- **本棚** = データベースの構造
- **順番にステップを踏む** = マイグレーションを順序通り適用
- **マイグレーションツール** = どのステップが完了済みかを追跡するシステム

---

## なぜ手動でテーブルを編集できないの？

新人エンジニアはよく疑問に思います：「データベースを開いて手動でカラムを追加すればいいのでは？」その理由：

**問題1：誰もあなたが何をしたか知らない**
```
  あなた：「金曜日にtenantsテーブルに'plan'カラムを追加したよ」
  同僚：  「え？自分のデータベースにはそのカラムないけど」
  あなた：「ああ、手動で追加してもらわないと」
  同僚：  「型は？デフォルト値は？NULLは許可？」
  あなた：「えーと...VARCHAR(20)だったかな...VARCHAR(50)だったかな...」
```

**問題2：データベースを再現できない**
新しい開発環境をセットアップしたり、本番にデプロイするとき、データベースが正確にどうあるべきか知る必要があります。手動の変更はどこにも記録されません。

**問題3：元に戻せない**
手動の変更で何か壊れたとき、どうやって取り消す？変更前のデータベースが正確にどうだったか覚えている？

**マイグレーションはこれらすべてを解決します：**
- すべての変更がファイルに書かれている（永久に記録）
- マイグレーションツールが順番に変更を適用（再現可能）
- 全員が同じデータベース構造になる（一貫性）
- コードレビューで変更を確認できる（監査可能）

---

## Flywayとは？

Flywayはvolta-auth-proxyが使っているマイグレーションツールです。シンプルです：

1. 特別な名前のSQLファイルを書く（V1__init.sql、V2__add_plans.sqlなど）
2. アプリ起動時にFlywayが確認：「どのマイグレーションを実行済み？」
3. まだ見ていない新しいマイグレーションを実行
4. 実行したものを追跡テーブルに記録

命名規則は重要です：

```
  V1__init.sql           V = バージョン付き、1 = バージョン番号、init = 説明
  V2__add_plans.sql      V2 = バージョン2、add_plans = 何をするか
  V3__add_mfa_table.sql  V3 = バージョン3、add_mfa_table = 何をするか

  ルール：
  - Vの後に数字（バージョン）
  - アンダースコア2つ（__）でバージョンと説明を区切る
  - 説明にはスペースの代わりにアンダースコアを使う
  - バージョンは連番（V1、V2、V3...）
  - 一度実行されたマイグレーションは絶対に編集してはいけない
```

---

## voltaのV1__init.sql

voltaの最初のマイグレーションは、アプリケーションに必要なすべての初期テーブルを作成します。簡略化した表示：

```sql
-- V1__init.sql：最初のマイグレーション。すべての基本テーブルを作成。

-- usersテーブル：ログインした全員を保存
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    google_sub VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- tenantsテーブル：ワークスペースを保存
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(50) NOT NULL UNIQUE,
    plan VARCHAR(20) NOT NULL DEFAULT 'FREE',
    max_members INT NOT NULL DEFAULT 50
);

-- membershipsテーブル：ユーザーとテナントをロール付きで接続
CREATE TABLE memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    role VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
    UNIQUE (user_id, tenant_id)
);

-- sessionsテーブル：誰がログイン中かを追跡
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id)
);
```

voltaが初めて起動して、Flywayが空のデータベースを見つけると、V1__init.sqlを実行してこれらすべてのテーブルを作成します。次の起動時、Flywayは「V1は実行済み、何もすることなし」と言います。

誰かが後でV2マイグレーションを追加すると、Flywayは次の起動時にV2だけを実行します（V1は完了済みと知っているから）。

---

## 簡単な例

```
  1日目：voltaを初めてセットアップ
  ──────────────────────────────
  データベース：空
  マイグレーションフォルダ：V1__init.sql

  $ ./mvnw spring-boot:run
  Flyway：「データベースが空。V1__init.sqlを実行中...」
  Flyway：「users、tenants、memberships、sessionsテーブルを作成」
  Flyway：「記録：V1完了」

  30日目：新機能に新しいテーブルが必要
  ─────────────────────────────────────
  データベース：V1テーブルあり
  マイグレーションフォルダ：V1__init.sql、V2__add_audit_logs.sql

  $ ./mvnw spring-boot:run
  Flyway：「V1は完了済み。新しいマイグレーションを確認中...」
  Flyway：「V2__add_audit_logs.sqlを発見。実行中...」
  Flyway：「audit_logsテーブルを作成」
  Flyway：「記録：V2完了」

  31日目：別の開発者がゼロからセットアップ
  ──────────────────────────────────────────
  データベース：空（新規セットアップ）
  マイグレーションフォルダ：V1__init.sql、V2__add_audit_logs.sql

  $ ./mvnw spring-boot:run
  Flyway：「データベースが空。V1__init.sqlを実行中...」
  Flyway：「V2__add_audit_logs.sqlを実行中...」
  Flyway：「すべて完了。データベースはV2。」
```

新しい開発者は他の全員と全く同じデータベースを自動的に得ます。

---

## さらに学ぶために

- [docker-compose.md](docker-compose.md) -- PostgreSQLデータベースの起動方法（マイグレーションが実行される場所）。
- [environment-variable.md](environment-variable.md) -- Flywayが使用するデータベース接続設定。
