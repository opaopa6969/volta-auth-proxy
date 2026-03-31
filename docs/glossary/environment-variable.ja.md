# 環境変数（Environment Variable）

[English version](environment-variable.md)

---

## 一言で言うと？

環境変数とは、コードの外に保存される設定で、アプリケーションにどう動作すべきかを伝えるものです。レストランの「営業中」「準備中」の看板のようなもので、建物を改装せずに動作を変えられます。

---

## たとえ話：機械の外にある設定

自動販売機を考えてみてください。中の飲み物はコードのようなもの -- 組み込まれていて変わりません。でもオペレーターが機械を開けずに調整できる設定があります：

- **1本あたりの価格** -- 鍵を使って変更できる
- **温度** -- 背面のコントロールパネルで調整
- **「故障中」の表示** -- 外から切り替え可能

これが環境変数に似ています。機械そのものの一部ではないけれど、機械の動作を変えます。オペレーターは機械を作り直すことなく調整できます。

ソフトウェアでは：
- **自動販売機** = アプリケーションコード
- **外部の設定** = 環境変数
- **オペレーター** = あなた（開発者やデプロイ担当者）

---

## なぜパスワードをコードに入れてはいけないのか

これは新人エンジニアにとって最も重要なレッスンの1つです：

**パスワード、APIキー、シークレットを絶対にコードに直接書いてはいけません。**

その理由：

```
  ダメな例（シークレットがコード内）：
  ─────────────────────────────────
  // database.js
  const password = "super-secret-password-123";
  db.connect("localhost", "volta", password);

  問題：
  1. コードを読んだ誰もがパスワードを見える
  2. GitHubにプッシュしたら全世界に見える
  3. 開発と本番で異なるパスワードが必要
  4. パスワード変更にはコード変更と再デプロイが必要
```

```
  良い例（シークレットが環境変数）：
  ─────────────────────────────────
  // database.js
  const password = process.env.DB_PASSWORD;
  db.connect("localhost", "volta", password);

  メリット：
  1. コードにシークレットが含まれない
  2. GitHubにプッシュしても安全
  3. 異なる環境で異なる値を使う
  4. 変数を変えるだけでパスワード変更、コード変更不要
```

---

## .envファイルとは？

`.env`ファイルは環境変数を保存するシンプルなテキストファイルです。プロジェクトディレクトリに置かれ、バージョン管理（Git）にはコミットしません。こんな見た目です：

```
DB_HOST=localhost
DB_PORT=54329
DB_PASSWORD=my-secret-password
GOOGLE_CLIENT_ID=abc123
JWT_PRIVATE_KEY_PEM=replace-me
```

各行は変数名、イコール記号、値です。それだけ。難しい構文はありません。

重要なルール：**`.env`を`.gitignore`ファイルに追加する**こと。GitHubにプッシュされないようにするためです。代わりに、変数名だけで実際の値がない`.env.example`ファイルを用意します：

```
DB_HOST=localhost
DB_PORT=54329
DB_PASSWORD=replace-me
GOOGLE_CLIENT_ID=replace-me
JWT_PRIVATE_KEY_PEM=replace-me
```

新しい開発者は`.env.example`を`.env`にコピーして、自分の値を入力します。

---

## voltaの環境変数設定

volta-auth-proxyはすべての設定に環境変数を使います。主要なグループ：

**データベース接続：**
```
DB_HOST=localhost
DB_PORT=54329
DB_NAME=volta_auth
DB_USER=volta
DB_PASSWORD=volta
```

**Googleログイン（OIDC）：**
```
GOOGLE_CLIENT_ID=replace-me
GOOGLE_CLIENT_SECRET=replace-me
GOOGLE_REDIRECT_URI=http://localhost:7070/callback
```

**セキュリティトークン：**
```
JWT_ISSUER=volta-auth
JWT_AUDIENCE=volta-apps
JWT_PRIVATE_KEY_PEM=replace-me
JWT_PUBLIC_KEY_PEM=replace-me
JWT_TTL_SECONDS=300
VOLTA_SERVICE_TOKEN=replace-me
```

**アプリケーション設定：**
```
PORT=7070
BASE_URL=http://localhost:7070
DEV_MODE=false
APP_CONFIG_PATH=volta-config.yaml
```

`GOOGLE_CLIENT_SECRET`や`JWT_PRIVATE_KEY_PEM`のような機密値が、アプリケーションにハードコードされずに環境変数になっていることに注目してください。これにより：
- 同じコードが開発（テストキー）と本番（実際のキー）で動く
- シークレットがGitリポジトリに入らない
- コード変更なしでシークレットをローテーションできる

---

## 簡単な例

volta-auth-proxyを開発用に初めてセットアップするとき：

```
  ステップ1：サンプルファイルをコピー
  $ cp .env.example .env

  ステップ2：.envを自分の値で編集
  DB_PASSWORD=my-local-password
  GOOGLE_CLIENT_ID=my-google-id
  GOOGLE_CLIENT_SECRET=my-google-secret

  ステップ3：アプリケーションを起動
  $ ./mvnw spring-boot:run
  （アプリが.envを読み込んで値を使う）

  ステップ4：後で本番環境では
  （同じコード、異なる.envの値）
  DB_PASSWORD=production-ultra-secure-password
  GOOGLE_CLIENT_ID=production-google-id
  GOOGLE_CLIENT_SECRET=production-google-secret
```

コードは同一です。環境変数だけが変わります。

---

## さらに学ぶために

- [docker-compose.md](docker-compose.md) -- Dockerコンテナで環境変数がどう設定されるか。
- [sdk.md](sdk.md) -- 環境変数で設定されたvoltaヘッダーをアプリが読めるようにするSDK。
