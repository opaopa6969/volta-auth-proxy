# realm.json

[English version](realm-json.md)

---

## これは何？

**realm.jsonはKeycloakの巨大な設定ファイルです。建物全体のセキュリティシステムの500ページの取扱説明書のようなものです。**

知らなくても大丈夫です！これはKeycloak固有のもので、ほとんどの人は触る必要がありません。

---

## 身近なたとえ

大きなオフィスビルのセキュリティ管理者だと想像してください。ビルのセキュリティに関するすべてのルールを書き出す必要があります：

- 誰がどのドアに入れるか？
- ドアは何時に施錠されるか？
- バッジを再提示するまでどのくらい滞在できるか？
- パスワードを3回間違えたらどうなるか？
- ロビーの画面のウェルカムメッセージは何と表示するか？
- 来客と社員のバッジの色は何色か？
- ...このようなルールがさらに500個

そして、これらのルールをすべて、非常に特殊な形式の1つの巨大な文書に書く必要があり、カンマ1つ間違えるだけで全部壊れます。

それが`realm.json`です。

---

## どんな見た目？

実際のrealm.jsonの一部です（全体は数千行になります）：

```json
{
  "realm": "my-company",
  "enabled": true,
  "sslRequired": "external",
  "bruteForceProtected": true,
  "maxFailureWaitSeconds": 900,
  "loginTheme": "my-theme",
  "accessTokenLifespan": 300,
  "ssoSessionMaxLifespan": 28800,
  "clients": [
    {
      "clientId": "my-app",
      "redirectUris": ["https://my-app.example.com/*"],
      "webOrigins": ["https://my-app.example.com"],
      ...クライアントごとにさらに何百もの設定...
    }
  ],
  "roles": { ... },
  "users": { ... },
  "authenticationFlows": { ... },
  ...さらに何百ものセクション...
}
```

実際のrealm.jsonファイルは**2,000〜5,000行**にもなります。手作業で編集するのは恐ろしいです。

---

## なぜrealm.jsonは大変なのか？

```
  こんなシナリオを想像してください：

  上司：   「ログインのタイムアウトを5分から10分に変えてくれる？」
  あなた： 「もちろん！」

  ステップ1：3,000行のファイルを開く
  ステップ2：似た名前の設定がたくさんある中から正しいものを探す
             （accessTokenLifespan？ssoSessionIdleTimeout？
              ssoSessionMaxLifespan？offlineSessionIdleTimeout？）
  ステップ3：数字を変更する
  ステップ4：2,847行目のカンマをうっかり消していないことを祈る
  ステップ5：Keycloakを再起動する
  ステップ6：動かない。ステップ2に戻る。
```

楽しい午後にはなりません。

---

## voltaは代わりに何を使っている？

volta-auth-proxyはこの巨大ファイルの代わりに、小さな`.env`ファイルに書けるシンプルな設定を使います：

```
# voltaのやり方：シンプルで明確
VOLTA_SESSION_TIMEOUT=10m
VOLTA_MAX_LOGIN_FAILURES=5
VOLTA_LOCKOUT_DURATION=15m
```

これだけです。3,000行のファイルはありません。正しい設定名を探し回る必要もありません。カンマの置き間違いで全部壊れることもありません。

---

## volta-auth-proxyでは

**volta-auth-proxyでは：** realm.jsonは存在しません。すべての設定はシンプルな環境変数で.envファイルに記述でき、Keycloakのアプローチと比べてセットアップが劇的に簡単になっています。
