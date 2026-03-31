# Docker Compose

[English version](docker-compose.md)

---

## 一言で言うと？

Docker Composeとは、複数のサービス（データベース、メールサーバー、メッセージキューなど）を、それらがどう連携するかを記述した設定ファイルを使って、1つのコマンドで一斉に起動できるツールです。

---

## バンドリハーサルのたとえ

バンドのリハーサルを企画していると想像してください。必要なのは：

- ドラマー（データベース -- リズム/データを保つ）
- ベーシスト（メッセージキュー -- 物事を流し続ける）
- キーボード奏者（メールサーバー -- コミュニケーションを担当）
- ギタリスト（あなたのアプリ -- メインパフォーマー）

Docker Composeがなければ、各ミュージシャンに個別に電話して、どこに行くか、何時に来るか、どのくらいの音量で演奏するか伝えて、全員が同じスタジオに来ることを確認しなければなりません。1人が来なければリハーサルは台無しです。

Docker Composeなら、1つのセットリスト（`docker-compose.yml`ファイル）を書いて：
- 誰が演奏するか（どのサービス）
- どこに座るか（どのポート）
- どのくらいの音量か（どの設定）
- どの順番で始めるか（依存関係）

そして一度「スタート」と言えば（`docker compose up`）、全員が一緒に演奏を始めます。

---

## Dockerとは（基本）

Docker Composeを理解する前に、Docker自体を理解する必要があります。Dockerはソフトウェアを「コンテナ」で実行できるようにします。コンテナとは、アプリケーションの実行に必要なすべてを含む密閉された箱のようなものです：

- アプリケーションコード
- すべてのライブラリと依存関係
- 必要なOSファイル
- 設定

素晴らしいのは：どのコンピュータで動かしても関係ないということです。コンテナはどこでも同じように動きます。これは有名な「自分のマシンでは動く」問題を解決します。

```
  Dockerなし：
  「MacにPostgreSQL 16をインストールしたけど、君のUbuntuには
   PostgreSQL 14が入ってて、本番のAmazon LinuxにはPostgreSQL 15。
   全部動きが違う。」

  Dockerあり：
  「全員同じコンテナを使う：postgres:16-alpine。
   どこでも同一。」
```

---

## voltaのdocker-compose.ymlを解説

volta-auth-proxyはDocker Composeを使ってサポートサービスを実行します。ファイルを1つずつ読んでみましょう：

```yaml
services:
  postgres:                              # サービス名：「postgres」
    image: postgres:16-alpine            # PostgreSQL 16を使用（Alpine Linux、小さい）
    container_name: volta-auth-postgres  # わかりやすい名前をつける
    ports:
      - "54329:5432"                     # ホストのポート54329をコンテナのポート5432に接続
    environment:                         # このコンテナの環境変数
      POSTGRES_DB: volta_auth            # 「volta_auth」というデータベースを作成
      POSTGRES_USER: volta               # 「volta」というユーザーを作成
      POSTGRES_PASSWORD: volta           # パスワードを「volta」に設定
    volumes:
      - volta_auth_pgdata:/var/lib/postgresql/data  # データを永続的に保存
    healthcheck:                         # データベースが準備できたか確認
      test: ["CMD-SHELL", "pg_isready -U volta -d volta_auth"]
      interval: 5s
      timeout: 5s
      retries: 10
```

これを普通の言葉で言うと：「PostgreSQL 16データベースを起動して。名前はvolta-auth-postgres。自分のコンピュータのポート54329でアクセスできるように。volta_authというデータベースをユーザー名volta、パスワードvoltaで作成。再起動してもデータが残るように保存。5秒ごとに動いているか確認。」

このファイルにはRedis（キャッシュ用）、Mailpit（メールテスト用）、Kafka（イベントストリーミング用）、Elasticsearch（検索と監査ログ用）などの他のサービスも定義されています。

---

## docker-compose.ymlの読み方

よくある設定のチートシート：

| 設定 | 意味 | 例 |
|---|---|---|
| `image` | どのソフトウェアを動かすか | `postgres:16-alpine` = PostgreSQLバージョン16 |
| `container_name` | コンテナのわかりやすい名前 | `volta-auth-postgres` |
| `ports` | 「ホスト:コンテナ」のポートマッピング | `"54329:5432"` = マシンのポート54329でアクセス |
| `environment` | コンテナに渡す設定 | `POSTGRES_DB: volta_auth` = このデータベースを作成 |
| `volumes` | 永続ストレージ | コンテナを再起動してもデータが残る |
| `healthcheck` | サービスが準備できたか確認する方法 | N秒ごとにコマンドを実行 |

---

## よく使うコマンド

```bash
# docker-compose.ymlに定義されたすべてのサービスを起動
docker compose up -d
# （-dは「デタッチド」-- バックグラウンドで実行）

# 何が動いているか確認
docker compose ps

# すべてのサービスのログを表示
docker compose logs

# postgresのログだけ表示
docker compose logs postgres

# すべてのサービスを停止
docker compose down

# すべてのサービスを停止してデータも削除
docker compose down -v
# （-vフラグはボリュームを削除 -- データベースのデータが消える！）
```

---

## 簡単な例

volta-auth-proxyで初めて作業を始めるとき：

```
  ステップ1：サービスを起動
  $ docker compose up -d

  Dockerが起動：
    ✓ volta-auth-postgres      （データベース、ポート54329）
    ✓ volta-auth-redis         （キャッシュ、ポート6379）
    ✓ volta-auth-mailpit       （フェイクメール、ポート8025）
    ✓ volta-auth-kafka         （イベント、ポート9092）
    ✓ volta-auth-elasticsearch （検索、ポート9200）

  ステップ2：volta-auth-proxy自体を起動
  $ ./mvnw spring-boot:run

  voltaがポート54329のpostgres、ポート6379のredisなどに接続。
  Docker Composeがすべてのサービスの実行を保証しているので、すべて動く。

  ステップ3：今日の作業が終わったら
  $ docker compose down
  （すべて停止。データはボリュームに保存済み。）
```

---

## さらに学ぶために

- [environment-variable.md](environment-variable.md) -- Dockerコンテナとvolta自体の両方を設定する設定値。
- [database-migration.md](database-migration.md) -- voltaがPostgreSQLコンテナ内でデータベーステーブルをセットアップする方法。
