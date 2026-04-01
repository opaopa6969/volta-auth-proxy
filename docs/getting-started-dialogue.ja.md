# はじめよう：会話で学ぶ volta-auth-proxy

[English](getting-started-dialogue.md) | [日本語](getting-started-dialogue.ja.md)

> 2 人のエンジニアが会議室に入ります。1 人は volta のエンジニア。もう 1 人は自分のアプリに認証を入れたい人。

---

## 登場人物

**リン（volta エンジニア）:** volta-auth-proxy を知り尽くしている。忍耐強い。図が好き。

**カイ（アプリ開発者）:** プロジェクト管理 SaaS を作っている。Javalin アプリがある。マルチテナント認証が必要。OAuth は聞いたことあるけど実装したことない。

---

## Scene 1: 「とりあえずログインさせたい」

**カイ:** Javalin でアプリ作ってるんだけど、ユーザーのログインが必要で。ずっと後回しにしてた。認証って怖くて。Auth0 は高いし、Keycloak は設定地獄っぽいし。volta で何とかなる？

**リン:** アプリは何をするもの？

**カイ:** プロジェクト管理。チームがワークスペースを作って、メンバーを招待して、タスクを管理する。ワークスペースごとにデータは分離したい。

**リン:** それは[マルチテナント](glossary/multi-tenant.ja.md)。各ワークスペースが[テナント](glossary/tenant.ja.md)。volta はまさにそのために作られた。アーキテクチャはこうなる:

```
ブラウザ
  ↓
Traefik（リバースプロキシ）
  ↓                    ↓
volta-auth-proxy       あなたのアプリ
(認証を全部やる)       (タスク/プロジェクトを管理)
```

**カイ:** えっ、2 つのサービス？ volta って「密結合上等」って言ってなかった？

**リン:** 「密結合」は volta の*設定*が 1 箇所にあるという意味。あなたのアプリと volta は別のサービスだけど、Traefik が繋いでくれる。あなたのアプリはログイン処理も、パスワード検証も、セッション管理も一切やらない。volta が全部やる。

**カイ:** じゃあ僕のアプリは何をするの？

**リン:** [HTTP ヘッダ](glossary/header.ja.md)を読む。以上。

**カイ:** …以上？

**リン:** 以上。

---

## Scene 2: 「ヘッダって何が来るの？」

**リン:** ユーザーがアプリにアクセスしたとき、こうなる:

```
1. ブラウザ → Traefik → 「このユーザーはログイン済み？」
2. Traefik → volta-auth-proxy → セッション確認 → 「はい、この人です」
3. Traefik → あなたのアプリ（identity ヘッダ付き）
```

あなたのアプリが受け取る[ヘッダ](glossary/header.ja.md):

```
X-Volta-User-Id:      550e8400-e29b-41d4-a716-446655440000
X-Volta-Email:        kai@example.com
X-Volta-Tenant-Id:    7c9e6679-7425-40de-944b-e07fc1f90ae7
X-Volta-Tenant-Slug:  acme-corp
X-Volta-Roles:        ADMIN,MEMBER
X-Volta-Display-Name: 田中カイ
X-Volta-JWT:          eyJhbGciOiJSUzI1NiIs...
```

**カイ:** つまり `X-Volta-Tenant-Id` を読んで、DB クエリでフィルタすればいい？

**リン:** その通り。

```java
app.get("/api/tasks", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String userId = ctx.header("X-Volta-User-Id");

    var tasks = db.query(
        "SELECT * FROM tasks WHERE tenant_id = ?",
        tenantId
    );
    ctx.json(tasks);
});
```

**カイ:** めちゃシンプル。でもヘッダが偽装されないの？ 誰かが `X-Volta-User-Id: admin` って送ったら？

**リン:** 2 層の防御がある:

1. **ネットワーク分離:** アプリは Traefik の内部ネットワークからだけアクセスを受ける。外から直接アクセスできない
2. **[JWT](glossary/jwt.ja.md) 検証:** もっと安全にしたければ、`X-Volta-JWT` ヘッダを検証する。暗号署名されてる

```java
// オプション（推奨）: JWT 検証
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());

app.get("/api/tasks", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    // user.getTenantId(), user.hasRole("ADMIN"), etc.
});
```

---

## Scene 3: 「セットアップどうするの？」

**カイ:** 分かった、使いたい。どうやってセットアップするの？

**リン:** 4 ステップ。

### Step 1: volta をクローンして起動

```bash
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy
docker compose up -d postgres
cp .env.example .env
# .env を編集: Google OAuth のクレデンシャルを入れる
mvn compile exec:java
```

### Step 2: volta-config.yaml にアプリを登録

```yaml
domain:
  base: example.com

apps:
  - id: project-manager
    subdomain: pm
    upstream: http://my-app:8080
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

**カイ:** これだけ？ 4 行？

**リン:** 4 行。volta が Traefik の設定を自動生成する。

### Step 3: Traefik を追加

```yaml
# docker-compose.yml（あなたのプロジェクト）
services:
  traefik:
    image: traefik:v3.0
    ports: ["80:80", "443:443"]

  volta-auth-proxy:
    image: volta-auth-proxy:latest
    ports: ["7070:7070"]
    env_file: .env

  my-app:
    build: .
    labels:
      - "traefik.http.routers.my-app.rule=Host(`pm.example.com`)"
      - "traefik.http.routers.my-app.middlewares=volta-auth"
```

### Step 4: アプリでヘッダを読む

```java
app.get("/api/tasks", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    // マルチテナント認証、完了。
});
```

**カイ:** …本当にこれだけ。

**リン:** 本当にこれだけ。

---

## Scene 4: 「招待はどうする？」

**カイ:** でもユーザーはどうやってワークスペースに参加するの？ 招待機能が要るよね。

**リン:** volta がそれもやる。招待コードを書く必要はない。volta には招待システムが組み込まれてる:

1. 管理者が `https://auth.example.com/admin/invitations` を開く
2. 招待リンクを作成
3. Slack やメールで共有
4. 新メンバーがリンクをクリック → Google ログイン → 同意画面 → ワークスペース参加

あなたのアプリはこれが起きたことを知る必要がない。次にそのユーザーがアプリにアクセスしたとき、`X-Volta-Tenant-Id` ヘッダに含まれてるだけ。

**カイ:** アプリ内に「メンバー一覧」ページを作りたいときは？

**リン:** volta の [Internal API](glossary/internal-api.ja.md) を叩く:

```java
app.get("/app/team", ctx -> {
    String jwt = ctx.header("X-Volta-JWT");
    String tenantId = ctx.header("X-Volta-Tenant-Id");

    var response = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(
                "http://volta-auth-proxy:7070/api/v1/tenants/" + tenantId + "/members"
            ))
            .header("Authorization", "Bearer " + jwt)
            .build(),
        HttpResponse.BodyHandlers.ofString()
    );

    ctx.json(response.body());
});
```

**カイ:** つまり volta がユーザーとテナントの唯一の情報源で、僕のアプリは必要なときに volta に聞けばいい？

**リン:** その通り。あなたのアプリはタスクとプロジェクトを持つ。volta はユーザー、テナント、ロール、セッションを持つ。きれいに分離。

---

## Scene 5: 「フロントエンドは？」

**カイ:** フロントエンドが [JavaScript](glossary/javascript.ja.md) なんだけど、セッション切れたらどうなる？

**リン:** volta-sdk-js を入れる。script タグ 1 つ。

```html
<script src="http://volta-auth-proxy:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://volta-auth-proxy:7070" });
</script>
```

`fetch()` の代わりに `Volta.fetch()` を使う:

```javascript
// Before（セッション切れ → エラー）
const res = await fetch("/api/tasks");

// After（セッション切れ → 自動リフレッシュ → リトライ → 成功）
const res = await Volta.fetch("/api/tasks");
```

**カイ:** 勝手にセッション更新してくれるの？

**リン:** そう。[JWT](glossary/jwt.ja.md) が期限切れ（5 分ごと）になったら、SDK がバックグラウンドでリフレッシュする。通常利用中はログイン画面を見ない。セッション自体が切れたら（8 時間後）、ログインに飛ばして元の画面に戻す。

**カイ:** テナント切替は？ 複数ワークスペースに所属するユーザーもいるし。

**リン:**

```javascript
await Volta.switchTenant("other-workspace-id");
// ページが自動リロード、新しいテナントのデータが表示
```

---

## Scene 6: 「ロールは？」

**カイ:** 管理者にはチーム管理をさせたいけど、一般メンバーは自分のタスクだけ見えればいい。

**リン:** volta には 4 つの[ロール](glossary/role.ja.md)がある:

```
OWNER  → ワークスペースの削除、オーナー譲渡
ADMIN  → メンバーの招待/削除、ロール変更
MEMBER → 通常利用
VIEWER → 読み取り専用
```

あなたのアプリでは:

```java
app.delete("/api/tasks/{id}", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);

    if (!user.hasRole("ADMIN")) {
        ctx.status(403).json(Map.of("error", "管理者のみ"));
        return;
    }

    db.execute("DELETE FROM tasks WHERE id = ? AND tenant_id = ?",
        ctx.pathParam("id"), user.getTenantId());
    ctx.status(204);
});
```

**カイ:** ロールの割り当ては volta 側でやるの？

**リン:** そう。`https://auth.example.com/admin/members` — 管理者がロールを変更できる。あなたのアプリは `X-Volta-Roles` を読んでビジネスルールを適用するだけ。

---

## Scene 7: 「何を作らなくていいの？」

**カイ:** 確認させて。何を作らなくていいの？

**リン:**

```
作らなくていいもの:
  ❌ ログインページ
  ❌ Google OAuth 連携
  ❌ セッション管理
  ❌ JWT 発行・検証（SDK がやる）
  ❌ ユーザー登録 / サインアップ
  ❌ パスワード管理
  ❌ 招待システム
  ❌ テナント/ワークスペース作成
  ❌ メンバー管理
  ❌ ロール管理
  ❌ パスワードリセット
  ❌ MFA（volta が Phase 3 でやる）
  ❌ 認証の CSRF 対策（volta がやる）

作るもの:
  ✅ ビジネスロジック（タスク、プロジェクト等）
  ✅ フロントエンド UI
  ✅ データベース（タスク、プロジェクト — ユーザー/テナントは不要）
  ✅ テナントスコープのクエリ（WHERE tenant_id = ?）
  ✅ ビジネスロジック内のロールチェック
```

**カイ:** …作らなくていいもの、めちゃ多い。

**リン:** それがポイント。volta が認証インフラをやる。あなたはアプリを特別にするものに集中する。

---

## Scene 8: 「最後に一つ」

**カイ:** volta が落ちたらどうなる？

**リン:** いい質問。volta は [ForwardAuth](glossary/forwardauth.ja.md) のチェックポイント。volta が落ちると:

- 新しいリクエストは認証できない → Traefik が 401 を返す
- キャッシュされたページは見えるかもだけど API は呼べない
- アプリ自体は動いてる — 新しいリクエストの検証ができないだけ

Phase 1（単一インスタンス）ではこれは既知の[トレードオフ](glossary/tradeoff.ja.md)。Phase 2 で Redis セッション + 水平スケーリングで高可用性を実現。

**カイ:** 正直、今の僕の規模なら大丈夫。理解できないシンプルな認証より、理解できるシンプルな認証がいい。

**リン:** それが volta の哲学。[理解できる地獄を選ぶ](glossary/native-implementation.ja.md)。

**カイ:** いつ始める？

**リン:**

```bash
git clone git@github.com:opaopa6969/volta-auth-proxy.git
```

今すぐ。

---

> **この会話に出てくる全ての用語はクリッカブルです。** 意味が分からなければクリックしてください。[恥ずかしいことじゃない — それが学び。](../README.ja.md)
