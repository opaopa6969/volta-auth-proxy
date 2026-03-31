# Flyway

[English version](flyway.md)

---

## これは何？

Flywayは、データベーススキーマの変更を管理・バージョン管理し、再現可能な方法で適用するデータベースマイグレーションツールです。

データベースのレシピ本のようなものです。家を建てることを想像してください。まず基礎を作り（V1）、次に壁を追加し（V2）、配管を通し（V3）、電気を引きます（V4）。壁がないのに配管はできません。同じ家をもう一度建てる必要がある場合、同じ手順を同じ順番でたどります。Flywayは、データベースが毎回正しい順序で段階的に構築されることを保証するレシピ本です。

---

## なぜ重要なのか？

マイグレーションツールがないと、データベースの変更はカオスです：

```
  Flywayなし：
  ┌──────────────────────────────────────────┐
  │ 開発者A: 「usersテーブル追加した」         │
  │ 開発者B: 「usersにカラム追加した」         │
  │ 本番:    「え？どっちもない」             │
  │ ステージング: 「テーブルはあるけどカラムが  │
  │                ない」                     │
  │ 全員:    「SQLファイル送るわ」             │
  └──────────────────────────────────────────┘

  Flywayあり：
  ┌──────────────────────────────────────────┐
  │ V1__init.sql      → ベーステーブル作成    │
  │ V2__add_column.sql → カラム追加           │
  │ すべての環境がこれを順番に実行。           │
  │ Flywayが何が適用済みか追跡。              │
  │ Slackでsqlファイルを送る人はいない。       │
  └──────────────────────────────────────────┘
```

### V1__の命名規則

Flywayのマイグレーションファイルは特定の命名パターンに従います：

```
V{バージョン}__{説明}.sql

例：
V1__init.sql
V2__oidc_flows.sql
V3__csrf_token.sql
V4__phase2_phase4_foundations.sql
```

分解すると：

- `V` -- バージョン管理されたマイグレーションを意味する
- `1` -- バージョン番号（Flywayはこの順番で実行：1, 2, 3...）
- `__` -- アンダースコア2つ（必須。Flywayがセパレータとして使う）
- `init` -- 人間が読める説明
- `.sql` -- SQLファイル

Flywayはデータベースに`flyway_schema_history`というテーブルを作り、どのマイグレーションが適用されたかを記録します：

```
| version | description              | installed_on        | success |
|---------|--------------------------|---------------------|---------|
| 1       | init                     | 2026-03-01 10:00:00 | true    |
| 2       | oidc flows               | 2026-03-01 10:00:01 | true    |
| 3       | csrf token               | 2026-03-05 14:30:00 | true    |
```

Flywayが実行されると、このテーブルを確認し、まだ適用されていないマイグレーションだけを適用します。つまり：

- Flywayを2回実行しても安全（適用済みのマイグレーションはスキップ）
- すべての環境が同じスキーマになる（開発、ステージング、本番）
- スキーマ変更がコードと一緒にバージョン管理される

---

## voltaでのFlywayの使い方

volta-auth-proxyは起動時に自動マイグレーションを実行します。voltaを起動すると、リクエストの処理を始める前にFlywayが実行され、保留中のマイグレーションが適用されます。つまり：

1. voltaの新しいバージョンをデプロイ
2. voltaが起動
3. Flywayが確認：「新しいマイグレーションファイルはある？データベースはそれを見た？」
4. Flywayが新しいマイグレーションを適用
5. voltaがリクエスト処理を開始

データベースに対して手動でSQLを実行することはありません。データベーススキーマがコードと一致しているか心配する必要もありません。ただ動きます。

### voltaのマイグレーションファイル

```
src/main/resources/db/migration/
├── V1__init.sql                         ← ベーステーブル（users、tenantsなど）
├── V2__oidc_flows.sql                   ← OIDC状態追跡
├── V3__csrf_token.sql                   ← CSRF保護
├── V4__phase2_phase4_foundations.sql     ← M2M、webhooks、IdP設定
├── V5__phase2_phase4_features.sql       ← MFA、SCIM、課金
├── V6__outbox_delivery_retry.sql        ← Webhookリトライ追跡
├── V7__mfa_unique_constraint.sql        ← MFAデータ整合性
├── V8__outbox_claim_lock.sql            ← Webhookワーカーロック
├── V9__sessions_mfa_verified.sql        ← MFAセッション状態
├── V10__phase2_user_identities_backfill.sql  ← ユーザーIDバックフィル
└── V11__idp_x509_cert.sql              ← SAML証明書保存
```

各ファイルがデータベーススキーマに何かを追加します。バージョン順に適用されます。V1が他のすべての基盤となるベーステーブルを作成します。V11は後で必要になったSAML証明書ストレージを追加します。

### 例：V1__init.sql（簡略化）

```sql
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    display_name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenant_members (
    tenant_id UUID REFERENCES tenants(id),
    user_id UUID REFERENCES users(id),
    role TEXT NOT NULL DEFAULT 'MEMBER',
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    tenant_id UUID REFERENCES tenants(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false
);
```

これが基盤です。以降のすべてのマイグレーションがこれらのテーブルの上に構築されます。

---

## セルフホスティングで自動マイグレーションが重要な理由

[セルフホスト](self-hosting.ja.md)製品にとって、自動マイグレーションは決定的に重要です。これがないと：

```
  手動マイグレーション地獄：
  1. 新しいvoltaバージョンをダウンロード
  2. リリースノートを読む：「この3つのSQLファイルを実行してください」
  3. 本番データベースに接続
  4. SQLファイルを正しい順番で実行
  5. 1つ漏らしていないことを祈る
  6. 新しいバージョンを起動
  7. V4の前にV5を実行してしまったので何か壊れる

  voltaの自動マイグレーション：
  1. 新しいvoltaバージョンをダウンロード
  2. 起動する
  3. 完了
```

これは[設定](config-hell.ja.md)と運用をシンプルに保つというvoltaの哲学の一部です。

---

## さらに学ぶために

- [Flywayドキュメント](https://flywaydb.org/documentation/) -- Flywayの公式ドキュメント。
- [config-hell.ja.md](config-hell.ja.md) -- voltaが運用の複雑さを自動化で取り除く理由。
- [self-hosting.ja.md](self-hosting.ja.md) -- 自動マイグレーションがセルフホスティングを実用的にする方法。
- [hikaricp.ja.md](hikaricp.ja.md) -- Flywayがデータベースに接続するために使うコネクションプール。
