# 設定の複雑さ

[English version](complexity-of-configuration.md)

---

設定の複雑さは、ソフトウェアエンジニアリングにおいて最も過小評価されているコストの1つです。見え隠れしています。誰も「設定の複雑さ」をプロジェクトリスク台帳に載せません。理解できない設定のドキュメントを読むのにチームが何時間費やすか見積もる人もいません。それでも設定の複雑さは、悪いアルゴリズムよりも多くのプロジェクトを殺してきました。

---

## 「設定するだけ」という幻想

すべてのエンタープライズソフトウェアベンダーが同じことを言います：「当社の製品は高度に設定可能です。あらゆるユースケースに合わせてカスタマイズできます。」素晴らしく聞こえます。これは罠です。

「高度に設定可能」が実際に意味すること：

1. すべての設定が何をするか誰かが学ぶ必要がある
2. あなたのユースケースにどの値が正しいか誰かが決める必要がある
3. 選んだ値が実際に一緒に動作するか誰かがテストする必要がある
4. なぜその値を選んだか誰かがドキュメント化する必要がある
5. ソフトウェアが進化するにつれてそれらの値を誰かが維持する必要がある
6. 設定変更で何かが壊れたとき誰かがトラブルシューティングする必要がある
7. 設定ファイルを見て困惑する新しいチームメンバーを誰かがオンボーディングする必要がある

その「誰か」はあなたです。

---

## 設定がコンパイラのないプログラミングになるとき

Keycloakの`realm.json`を考えてみましょう。本番設定からの実際の抜粋です：

```json
{
  "realm": "my-saas",
  "sslRequired": "external",
  "bruteForceProtected": true,
  "maxFailureWaitSeconds": 900,
  "minimumQuickLoginWaitSeconds": 60,
  "waitIncrementSeconds": 60,
  "quickLoginCheckMilliSeconds": 1000,
  "maxDeltaTimeSeconds": 43200,
  "failureFactor": 30,
  "otpPolicyType": "totp",
  "otpPolicyAlgorithm": "HmacSHA1",
  "otpPolicyInitialCounter": 0,
  "otpPolicyDigits": 6,
  "otpPolicyLookAheadWindow": 1,
  "otpPolicyPeriod": 30,
  "clients": [{
    "clientId": "my-app",
    "standardFlowEnabled": true,
    "implicitFlowEnabled": false,
    "directAccessGrantsEnabled": false,
    "serviceAccountsEnabled": false,
    "publicClient": true,
    "fullScopeAllowed": true,
    "defaultClientScopes": ["openid", "profile", "email"],
    "optionalClientScopes": ["offline_access"]
  }]
}
```

このファイルをよく見てください。本当によく見てください。

- `quickLoginCheckMilliSeconds`とは何？1000の代わりに500にしたら何が起きる？
- `maxDeltaTimeSeconds`とは何？なぜ43200？秒？時間？「デルタタイム」ってこの文脈で何？
- `fullScopeAllowed`とは何？`true`は安全？`false`にすると何が起きる？「フルスコープ」って何？
- `otpPolicyLookAheadWindow`とは何？なぜ1？0だと？5だと？

これらは修辞的な質問ではありません。実際の開発者がこのファイルに初めて遭遇したときに尋ねる質問です。そして答えは自明ではありません -- Keycloakのドキュメントを読む必要があり、そのドキュメント自体がKeycloakの内部概念の理解を要求し、それには数週間の学習が必要です。

**これはプログラミングです。** システムの動作を制御する命令を書いています。しかし実際のコードとは違い：

- ミスを捕捉するコンパイラがない
- 無効な値を防ぐ型システムがない
- 正しさを検証するテストフレームワークがない
- 自動補完や説明をするIDEがない
- 「何がなぜ変わったか」を意味のある形で示すdiffツールがない

設定としてのプログラミングは最悪のプログラミングです：すべての複雑さがあり、セーフティネットは一切ありません。

---

## 隠れた時間コスト

チームがKeycloakの設定に費やす時間が、必要な認証機能をゼロから構築する時間より長いのを何度も見てきました。典型的なタイムラインはこうです：

```
  1週目:  「Keycloakを使おう！何でもできる！」
  2週目:  Keycloakのドキュメントを読む。「レルムって何？クライアントって何？」
  3週目:  最初のrealm.jsonが動く。ログインページがダサい。
  4週目:  FreeMarkerテンプレートと格闘してログインをカスタマイズ。
  5週目:  「待って、マルチテナンシーはどうやるの？テナントごとに1レルム？」
  6週目:  テナントごとのレルムがスケールしないことに気づく。代替案を調査。
  7週目:  制限を回避するためのカスタムSPI開発。
  8週目:  まだSPIをデバッグ中。Stack Overflowに答えなし。
  9週目:  「やっぱり自分で作った方が良かったんじゃ...」
  10週目: 投資しすぎて切り替えられない。サンクコストの誤謬が支配する。
```

10週間。2ヶ月半。そしてチームは実際の製品のビジネスロジックを1行も書いていません。

---

## voltaの選択：設定よりコード

volta-auth-proxyは真逆のアプローチを取ります。何百もの設定を公開する代わりに、voltaは意見の強い選択を行い、コードに組み込みます。

**Keycloakのアプローチ：** 「すべてあなたが決める。」

```json
"otpPolicyType": "totp",
"otpPolicyAlgorithm": "HmacSHA1",
"otpPolicyInitialCounter": 0,
"otpPolicyDigits": 6,
"otpPolicyLookAheadWindow": 1,
"otpPolicyPeriod": 30
```

**voltaのアプローチ：** 「私たちが決めた。TOTP、SHA1、6桁、30秒。これが正しい選択。次に進もう。」

voltaの全設定：

```bash
# .env
DATABASE_URL=jdbc:postgresql://localhost:5432/volta_auth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
SESSION_SECRET=your-random-secret
BASE_URL=https://auth.example.com
```

```yaml
# volta-config.yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

すべての設定が自明です。すべての値はあなたが既に知っているもの（データベースURL、Google認証情報、ドメイン）です。調べるものも、誤解するものも、間違えるものもありません。

---

## 「でも何か変えたくなったら？」

これはいつも聞かれる質問です。「デフォルトのセッションタイムアウトが自分に合わなかったら？別のJWTアルゴリズムが必要だったら？」

答え：**コードを変えてください。**

voltaはオープンソースです。RS256の代わりにRS384が必要なら、`JwtService.java`の1行を変更します。8時間の代わりに4時間のセッションタイムアウトが必要なら、定数を1つ変更します。これは設定値の変更より難しくありません -- 実際にはより簡単です。なぜなら：

1. コードには型チェックがある（コンパイラがミスを捕捉）
2. コードにはテストがある（変更が動作することを検証可能）
3. コードにはgit履歴がある（なぜ変更されたか見える）
4. コードは自己文書化する（変数名が何をするか説明）

違いは哲学的です。Keycloakは言います：「何でも変える必要があるかもしれないから、すべてを設定可能にする。」voltaは言います：「ほとんどのことは変える必要がないし、変えるときはコードの方が設定ファイルより良い場所だ。」

---

## 設定の80/20ルール

私の経験では、エンタープライズソフトウェアの設定オプションの80%はデフォルト値から変更されることがありません。それらが存在するのは、かつてプロダクトマネージャーが「顧客がこれを変えたいかもしれない」と言ったからです -- そうして設定が生まれました。その設定は今やドキュメント化し、テストし、維持し、すべての新規ユーザーに説明しなければなりません。

voltaはそれらの設定を完全に排除します。デプロイメント間で異なる20%のもの（データベースURL、OAuth認証情報、アプリリスト）は`.env`と`volta-config.yaml`にあります。どこでも同じ80%のもの（JWTアルゴリズム、セッションタイムアウト、最大セッション数、PKCE強制）はコードにあります。

---

## 「柔軟性」のコスト

柔軟性は無料ではありません。すべての設定オプションは：

- 誰かが行わなければならない決定
- 誰かが書かなければならないドキュメント
- 誰かがカバーしなければならないテストケース
- 誰かが間違えたときのサポートチケット
- 誰かが安全でない値に設定したときのセキュリティ脆弱性

voltaの意見のあるデフォルト値は制限ではありません。**既に正しく行われた決定**です。RS256はこのユースケースに正しいJWTアルゴリズムです。8時間のスライディングウインドウは正しいセッションタイムアウトです。PKCEは必須であり、オプションではありません。これらはセキュリティの決定であり、好みではありません。

voltaを使うとき、制限を受け入れているのではありません。良い決定を継承しているのです。

---

## さらに学ぶために

- [config-hell.ja.md](config-hell.ja.md) -- 設定の複雑さの詳細な技術分析。
- [keycloak.ja.md](keycloak.ja.md) -- voltaのミニマリズムに影響を与えたスイスアーミーナイフ。
- [tradeoff.ja.md](tradeoff.ja.md) -- より広いトレードオフのフレームワーク。
- [dashboard.ja.md](dashboard.ja.md) -- GUI vs コード設定。
