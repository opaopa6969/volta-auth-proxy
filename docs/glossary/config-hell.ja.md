# 設定地獄（Configuration Hell）

[English version](config-hell.md)

---

## これは何？

設定地獄とは、ソフトウェアの設定項目、オプション、調整ツマミが多すぎて、セットアップ（またはメンテナンス）がドキュメントの読み漁り、値の推測、何も壊れないことを祈る悪夢になる状態のことです。

97個のボタンがあるテレビのリモコンで考えてみましょう。チャンネルを変えて音量を調整したいだけなのに、リモコンには「入力切替」「アスペクト比」「色温度」「モーションスムージング」「オーディオリターンチャネル」「CEC制御」、そして聞いたこともない91個のボタンがあります。間違ったボタンを押すと画面が青くなります。設定地獄とは、3つで済むのに97個のボタンを渡されることです。

---

## なぜ重要なのか？

設定地獄はエンタープライズソフトウェアの実在する問題です。ソフトウェアがすべての人のすべてのことに対応しようとすると起きます。ユースケースが追加されるたびに設定が増えます。年月が経つにつれ、設定ファイルは元の開発者ですら完全に理解できないモンスターに成長します。

その結果は深刻です：

1. **セットアップが遅い：** 1時間で終わるべきことが、ドキュメントを読む1週間になる。
2. **隠れたバグ：** 500行の設定ファイルの347行目の間違った設定が、微妙なセキュリティの欠陥を引き起こす。
3. **変更への恐怖：** 「設定には触るな、なんとか動いてるから」がチームのマントラになる。
4. **オンボーディングの苦痛：** 新しいチームメンバーが何か有用なことをする前に、設定の理解に何日も費やす。

---

## 現実の例としてのKeycloak

[Keycloak](keycloak.ja.md)は認証の世界における設定地獄の代表例です。Keycloakのレルムエクスポート（`realm.json`）は500行以上になることが簡単にあります：

```json
{
  "realm": "my-saas",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "registrationEmailAsUsername": true,
  "rememberMe": false,
  "verifyEmail": false,
  "loginWithEmailAllowed": true,
  "duplicateEmailsAllowed": false,
  "resetPasswordAllowed": false,
  "editUsernameAllowed": false,
  "bruteForceProtected": true,
  "permanentLockout": false,
  "maxFailureWaitSeconds": 900,
  "minimumQuickLoginWaitSeconds": 60,
  "waitIncrementSeconds": 60,
  "quickLoginCheckMilliSeconds": 1000,
  "maxDeltaTimeSeconds": 43200,
  "failureFactor": 30,
  "roles": {
    "realm": [
      { "name": "offline_access", "composite": false },
      { "name": "uma_authorization", "composite": false }
    ],
    "client": {
      "my-app": [
        { "name": "user", "composite": false },
        { "name": "admin", "composite": false }
      ]
    }
  },
  "defaultRoles": ["offline_access", "uma_authorization"],
  "requiredCredentials": ["password"],
  "otpPolicyType": "totp",
  "otpPolicyAlgorithm": "HmacSHA1",
  "otpPolicyInitialCounter": 0,
  "otpPolicyDigits": 6,
  "otpPolicyLookAheadWindow": 1,
  "otpPolicyPeriod": 30,
  "clients": [
    {
      "clientId": "my-app",
      "enabled": true,
      "clientAuthenticatorType": "client-secret",
      "redirectUris": ["https://myapp.example.com/*"],
      "webOrigins": ["https://myapp.example.com"],
      "standardFlowEnabled": true,
      "implicitFlowEnabled": false,
      "directAccessGrantsEnabled": false,
      "serviceAccountsEnabled": false,
      "publicClient": true,
      "frontchannelLogout": false,
      "protocol": "openid-connect",
      "attributes": {
        "pkce.code.challenge.method": "S256"
      },
      "fullScopeAllowed": true,
      "defaultClientScopes": ["openid", "profile", "email"],
      "optionalClientScopes": ["offline_access"]
    }
  ]
  // ... この先さらに何百行も続く ...
}
```

そしてこれは**1つのレルム**だけです。複数レルムでマルチテナンシーを実現しているなら、テナント数分これを掛け算します。

開発者がこのファイルで直面する疑問：

- `uma_authorization`って何？必要なの？
- `quickLoginCheckMilliSeconds`って何？間違って設定したらどうなる？
- `fullScopeAllowed`って何？`true`で安全？
- `defaultClientScopes`と`optionalClientScopes`の違いは？
- なぜOTPポリシー設定が6つもある？

ほとんどの開発者は、ドキュメントに何時間も費やさないとこれらの質問に答えられません。

---

## voltaが設定地獄をどう回避しているか

voltaは逆のアプローチを取ります：妥当なデフォルト値を持つ最小限の設定。

### voltaの全設定

**環境変数（.env）：**

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/volta_auth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
SESSION_SECRET=your-random-secret
BASE_URL=https://auth.example.com
```

**アプリケーション設定（volta-config.yaml）：**

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

これだけです。全設定が1画面に収まります。すべての設定が明白です：

- `DATABASE_URL` -- データベースの場所
- `GOOGLE_CLIENT_ID` -- GoogleのOAuth認証情報
- `SESSION_SECRET` -- Cookie署名用のランダム文字列
- `apps` -- voltaが保護するアプリケーションと、誰がアクセスできるか

### なぜこれで機能するか

voltaはオプションを公開する代わりに、確固たる選択をすることで設定をシンプルに保てます：

| Keycloak: あなたが決める | volta: もう決めてある |
|------------------------|---------------------|
| どのOIDCフローを有効にする？ | Authorization Code + PKCEのみ |
| セッションタイムアウト？ | 8時間スライディングウィンドウ（妥当なデフォルトをハードコード） |
| JWTアルゴリズム？ | RS256のみ（[rs256.ja.md](rs256.ja.md)参照） |
| 最大同時セッション数？ | ユーザーあたり5 |
| JWT有効期限？ | 5分 |
| パスワードポリシー？ | N/A（Googleがパスワードを管理） |
| トークン署名鍵のフォーマット？ | RSA 2048ビット、初回起動時に自動生成 |

これは「パワーが劣る」のではありません。「あなたが迷わなくて済むように正しい選択をした」のです。voltaのすべてのデフォルトはプレースホルダーではなく、意図的なセキュリティ上の判断です。

---

## トレードオフ

設定地獄は、ソフトウェアがシンプルさよりも柔軟性を優先したときに起きます。voltaは柔軟性よりもシンプルさを優先します。そのトレードオフ：

- **Keycloak：** ほぼ何でもできる。正しく設定するのに1週間かかる。
- **volta：** 一つのこと（マルチテナントSaaS認証）をうまくやる。設定に1時間。

voltaが設定を公開していない機能が必要なら、ソースコードを変更します。voltaがオープンソースなのはまさにこの理由です。

---

## さらに学ぶために

- [keycloak.ja.md](keycloak.ja.md) -- 設定の複雑さの現実の例。
- [self-hosting.ja.md](self-hosting.ja.md) -- voltaがシンプルな設定でセルフホスティングを実用的にする方法。
- [flyway.ja.md](flyway.ja.md) -- voltaが設定を自動化するもう一つの領域（データベースマイグレーション）。
