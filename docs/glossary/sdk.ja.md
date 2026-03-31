# SDK（Software Development Kit）

[English version](sdk.md)

---

## 一言で言うと？

SDKとは、サービスとやり取りするための既製の関数を提供するライブラリのことです。低レベルのコードを自分で全部書く必要がなくなります。すべての材料を別々に買わせる代わりに、計量済みの材料と説明書が入った料理キットのようなものです。

---

## 料理キットのたとえ

ラーメンを作りたいとしましょう。2つの選択肢があります：

**選択肢A：ゼロから**
- 店に行って小麦粉、かんすい、卵、豚骨、醤油、みりん、海苔、ネギを買う...
- 手打ちで麺を作る
- 12時間かけてスープを煮込む
- 試行錯誤で正しい配分を見つける

**選択肢B：ラーメンキット**
- 箱を開ける
- 手順に従う：「お湯を沸かす。麺を入れる。スープの素を入れる。付属のトッピングを乗せる」
- 15分で完成

ラーメンキットがSDKです。誰かが難しい部分をすでに解決してパッケージにしてくれています。あなたはまだラーメンを作ります（サービスとやり取りする）が、面倒でミスの起きやすい作業をスキップできます。

---

## なぜSDKが必要なの？

SDKなしでアプリからvolta-auth-proxyと通信するには、大量の繰り返しコードを書く必要があります：

```
  SDKなし（すべて自分で書く）：
  ──────────────────────────
  1. リクエストからX-Volta-User-Idヘッダーを読み取る
  2. X-Volta-Tenant-Idヘッダーを読み取る
  3. X-Volta-Rolesヘッダーを読み取る
  4. ロール文字列をリストにパース
  5. voltaのInternal APIを呼ぶHTTPクライアントを構築
  6. サービストークン付きのAuthorizationヘッダーを追加
  7. ネットワークエラー、タイムアウト、リトライを処理
  8. JSONレスポンスをパース
  9. エラーレスポンスを処理（400、401、403、404、500）
  10. JSONを自分の言語のオブジェクトにマッピング

  アプリの本来の目的と何の関係もないコードが
  大量に必要。
```

```
  SDKあり（既製のもの）：
  ──────────────────────
  VoltaUser user = VoltaAuth.getUser(request);
  String tenantId = user.getTenantId();
  boolean isAdmin = user.hasRole("ADMIN");
  List<Member> members = volta.listMembers(tenantId);

  4行で完了。アプリの開発に戻ろう。
```

---

## voltaの2つのSDK

voltaは2つの言語用のSDKを提供しています：

### volta-sdk（Java）

Java/Kotlinアプリ用（Spring Boot、Javalinなど）：

```java
// voltaヘッダーから現在のユーザーを取得
VoltaUser user = VoltaAuth.getUser(request);

// ユーザーの身元を確認
String email = user.getEmail();        // "taro@acme.com"
String tenantId = user.getTenantId();  // "acme-uuid"
String userId = user.getUserId();      // "taro-uuid"

// 権限を確認
if (user.hasRole("ADMIN")) {
    // 管理機能を表示
}

// voltaのInternal APIを呼ぶ
VoltaClient volta = new VoltaClient("http://volta:7070", serviceToken);
List<Member> members = volta.listMembers(tenantId);
```

### volta-sdk-js（JavaScript/TypeScript）

Node.jsアプリ用（Express、Next.jsなど）：

```javascript
// voltaヘッダーから現在のユーザーを取得
const user = VoltaAuth.getUser(req);

// ユーザーの身元を確認
const email = user.email;        // "taro@acme.com"
const tenantId = user.tenantId;  // "acme-uuid"
const userId = user.userId;      // "taro-uuid"

// 権限を確認
if (user.hasRole("ADMIN")) {
    // 管理機能を表示
}

// voltaのInternal APIを呼ぶ
const volta = new VoltaClient("http://volta:7070", serviceToken);
const members = await volta.listMembers(tenantId);
```

---

## SDKが何から救ってくれるか

SDKなしで書く場合とSDKありの実際の比較：

**ユーザーが管理者かどうかの確認（SDKなし）：**
```java
String rolesHeader = request.getHeader("X-Volta-Roles");
if (rolesHeader == null) {
    throw new UnauthorizedException("No roles header");
}
List<String> roles = Arrays.asList(rolesHeader.split(","));
boolean isAdmin = roles.contains("ADMIN") || roles.contains("OWNER");
if (!isAdmin) {
    throw new ForbiddenException("Admin access required");
}
```

**SDKありで同じこと：**
```java
VoltaUser user = VoltaAuth.getUser(request);
if (!user.hasRole("ADMIN")) {
    throw new ForbiddenException("Admin access required");
}
```

SDKはあなたが忘れがちなエッジケースも処理します：
- ヘッダーがない場合は？
- ヘッダーに予期しない空白がある場合は？
- ロール階層は？（OWNERはADMINチェックも通るべき）
- null値は？

SDKがこれらすべてを処理します。メソッドを呼ぶだけです。

---

## 簡単な例

wikiアプリに「ページ削除」ボタンがあって、ADMINとOWNERだけに表示したいとします：

```java
// wikiアプリの削除エンドポイント
app.delete("/api/pages/:id", ctx -> {
    // ステップ1：ユーザーを取得（SDKがvoltaヘッダーを読む）
    VoltaUser user = VoltaAuth.getUser(ctx.req());

    // ステップ2：権限を確認（SDKがロール階層を処理）
    if (!user.hasRole("ADMIN")) {
        ctx.status(403).result("管理者のみがページを削除できます");
        return;
    }

    // ステップ3：テナントを取得（SDKがヘッダーから抽出）
    String tenantId = user.getTenantId();

    // ステップ4：ページを削除（アプリのロジック）
    pageService.delete(ctx.pathParam("id"), tenantId);
    ctx.status(200).result("ページが削除されました");
});
```

SDKがなければ、ステップ1だけでヘッダーのパースとバリデーションに10行以上必要です。SDKがあれば1行です。

---

## さらに学ぶために

- [api.md](api.md) -- SDKがラップするvolta Internal API。
- [downstream-app.md](downstream-app.md) -- SDKを使うアプリ。
- [role.md](role.md) -- SDKの`hasRole()`メソッドが理解するロール階層。
