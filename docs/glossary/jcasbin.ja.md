# jCasbin

[English version](jcasbin.md)

---

## これは何？

jCasbinは、**誰が何をできるか**を判断するJava認可ライブラリです。CNCFサンドボックスプロジェクトであるCasbinプロジェクトのJava実装です。認証が「あなたは誰？」に答えるのに対し、認可は「これを行う権限がありますか？」に答えます。jCasbinは後者を担当します。

マンションの共用ルールを想像してください。認証は玄関の鍵です -- ここに住んでいることを証明します。認可はルールのリストです：「住人は午前6時から午後10時までプールを使用可能。ボイラー室にアクセスできるのは管理人だけ。ゲストは共用エリアのみ入室可能。」jCasbinは、誰かがドアを開けようとするたびにこれらのルールをチェックするシステムです。

jCasbinはモデルファイル（PERMと呼ばれるシンプルな設定言語で記述）で認可ロジックを定義し、ポリシーファイル（またはデータベーステーブル）で具体的なルールを定義します。モデルを変更すると、アプリケーションコードを変えずに認可スキーム全体が変わります -- シンプルなACLからRBAC、ABACまで。

---

## なぜ重要なのか？

認可ロジックは見た目以上に複雑です。シンプルな「管理者かどうか」のチェックが、すぐにコードベース中に散らばったif-elseの絡み合いに成長します。典型的な進化：

1. 初日：`if (user.isAdmin()) { ... }`
2. 3ヶ月後：`if (user.isAdmin() || user.isTenantOwner()) { ... }`
3. 6ヶ月後：`if (user.isAdmin() || (user.isTenantOwner() && resource.getTenantId().equals(user.getTenantId()))) { ... }`
4. 2年後：認可ロジックを誰も理解できない

jCasbinは認可モデルをアプリケーションコードから分離することでこれを解決します。ルールはポリシーストア（ファイル、データベース、またはAPI）に格納され、アプリケーションはjCasbinに尋ねるだけです：「ユーザーXはリソースZに対してアクションYを実行できますか？」答えはYesかNoです。

volta-auth-proxyのようなマルチテナントSaaSにとって、この分離は極めて重要です。テナントごとに異なる権限モデルが必要かもしれません。あるテナントはシンプルなロールベースアクセスを望み、別のテナントは属性ベースポリシーを望む。jCasbinは同じエンジンで両方をサポートします。

---

## どう動くのか？

### PERMモデル

jCasbinは4つの要素で定義されるモデルを使用します：**P**olicy（ポリシー）、**E**ffect（効果）、**R**equest（リクエスト）、**M**atcher（マッチャー）（PERM）。

```ini
# model.conf -- RBACの例
[request_definition]
r = sub, obj, act

[policy_definition]
p = sub, obj, act

[role_definition]
g = _, _

[policy_effect]
e = some(where (p.eft == allow))

[matchers]
m = g(r.sub, p.sub) && r.obj == p.obj && r.act == p.act
```

これは次のように読みます：「リクエストにはサブジェクト、オブジェクト、アクションがある。ポリシーも同じ。ロール（g）がある。効果は『ポリシーに一致するものがあれば許可』。マッチャーは、サブジェクト（またはそのロール）がポリシーサブジェクトに一致し、かつオブジェクトが一致し、かつアクションが一致するかをチェックする。」

### ポリシーの例

```csv
# policy.csv
p, admin, /api/users, GET
p, admin, /api/users, POST
p, admin, /api/users, DELETE
p, editor, /api/articles, GET
p, editor, /api/articles, POST
p, viewer, /api/articles, GET

# ロール割り当て
g, alice, admin
g, bob, editor
g, charlie, viewer
```

このポリシーでは：
- Alice（admin）はユーザーと記事のGET、POST、DELETEが可能
- Bob（editor）は記事のGETとPOSTが可能
- Charlie（viewer）は記事のGETのみ可能

### サポートされるモデル

| モデル | 説明 | ユースケース |
|--------|------|-------------|
| **ACL** | アクセス制御リスト。ユーザーと権限の直接マッピング。 | シンプルなアプリ、少数ユーザー |
| **RBAC** | ロールベースアクセス制御。ユーザーにロール、ロールに権限。 | ほとんどのSaaSアプリ |
| **RBAC with domains** | ロールがドメイン（テナント）にスコープされたRBAC。 | マルチテナントSaaS |
| **ABAC** | 属性ベースアクセス制御。ユーザー、リソース、環境の属性に基づくポリシー。 | 複雑なエンタープライズルール |
| **RESTful** | HTTPメソッド＋パスパターンにマッチ。 | API認可 |

### jCasbin vs 代替手段

| 機能 | jCasbin | [OPA](opa.md) | Cedar (AWS) | Spring Security |
|------|---------|------|-------|-----------------|
| 言語 | Java（純粋なライブラリ） | Go（HTTPサイドカー） | Rust（ライブラリ/サービス） | Java（フレームワーク） |
| ポリシー言語 | PERM（モデル）+ CSV/DB | Rego | Cedar | SpEL / アノテーション |
| デプロイ | インプロセス | サイドカー / デーモン | インプロセスまたはサービス | インプロセス |
| レイテンシ | マイクロ秒 | ミリ秒（HTTP呼び出し） | マイクロ秒 | マイクロ秒 |
| CNCFステータス | サンドボックス | 卒業 | N/A | N/A |
| マルチテナント | ドメイン付きRBAC | 手動 | 手動 | 手動 |
| 学習コスト | 低い | 高い（Regoは独特） | 中程度 | 中程度（Springエコシステム） |

---

## volta-auth-proxy ではどう使われている？

jCasbinはvolta-auth-proxyの**Phase 4で推奨されるポリシーエンジン**です。初期フェーズでは、voltaはJavaにハードコードされたシンプルなロールチェックを使用します。Phase 4ではプラガブルなポリシーエンジンが導入され、jCasbinが主要な候補です。

### なぜOPAよりjCasbinか？

| 観点 | jCasbin | [OPA](opa.md) |
|------|---------|------|
| デプロイ | インプロセス（JARだけ） | 別のサイドカープロセス |
| レイテンシ | サブミリ秒 | HTTP呼び出しごとに1-5ms |
| 言語 | Java（voltaと同じ） | Go + Rego |
| デバッグ | IDEでステップ実行可能 | 別プロセス、別ログ |
| 依存関係 | Maven依存関係1つ | Dockerコンテナ + HTTPクライアント |

voltaの哲学は「すべての行を理解する」「ブラックボックスなし」です。jCasbinはこれに完璧にフィットします -- ネットワークホップなし、別プロセスなし、馴染みのないクエリ言語なしで直接呼び出せる純粋なJavaライブラリです。

### 計画されている統合

```java
// Phase 4 -- 計画
Enforcer enforcer = new Enforcer("model.conf", new JDBCAdapter(dataSource));

// ForwardAuthハンドラー内:
String user = session.getUserEmail();
String tenant = session.getTenantId();
String resource = request.getPath();
String action = request.getMethod();

if (enforcer.enforce(user, tenant, resource, action)) {
    // ヘッダーを設定、200を返す
} else {
    // 403を返す
}
```

### マルチテナント認可

jCasbinの「ドメイン付きRBAC」モデルは、voltaのマルチテナントアーキテクチャに直接マッピングされます：

```ini
[matchers]
m = g(r.sub, p.sub, r.dom) && r.dom == p.dom && r.obj == p.obj && r.act == p.act
```

```csv
# Aliceはtenant-1ではadminだが、tenant-2ではviewerのみ
p, admin, tenant-1, /api/*, *
p, viewer, tenant-2, /api/articles, GET

g, alice, admin, tenant-1
g, alice, viewer, tenant-2
```

これにより、同じユーザーが異なるテナントで異なるロールを持てます -- マルチテナントSaaSにまさに必要なことです。

---

## よくある間違いと攻撃

### 間違い1：変更後にポリシーをリロードしない

ポリシーをデータベースに保存して更新しても、メモリ内のエンフォーサーは自動的に変更を取得しません。`enforcer.loadPolicy()`を呼び出すか、リロードをトリガーするウォッチャーを使用する必要があります。これを忘れると、再起動まで変更が反映されません。

### 間違い2：RBACが必要なのにACLを使う

ユーザーと権限の直接マッピングから始める方が簡単に見えますが、スケールしません。100人のユーザーと50の権限がある場合、5,000のポリシー行が必要になります。最初からロールを使いましょう。

### 間違い3：マッチャーが広すぎる

`r.obj == p.obj || r.sub == "admin"`のようなマッチャーは便利に見えますが、管理者のオブジェクトレベルチェックをすべてバイパスします。各ロールがアクセスできるものを明示的にしてください。

### 間違い4：エッジケースでモデルをテストしない

PERMモデルには微妙なバグがあり得ます。以下のケースで必ずテストしてください：「ロールがないユーザーはどうなるか？矛盾するロールがある場合は？ポリシーにリソースが存在しない場合は？」

### 攻撃：ポリシーインジェクション

ポリシーデータがユーザー入力から来る場合（例：テナント管理者がポリシーを作成できる場合）、自身の権限をエスカレーションできないことを検証してください。テナント管理者が自分にスーパー管理者アクセスを付与するポリシーを作成できてはいけません。

---

## さらに学ぶ

- [Casbinドキュメント](https://casbin.org/docs/overview) -- すべてのサポートモデルの例を含む公式ドキュメント。
- [jCasbin GitHub](https://github.com/casbin/jcasbin) -- Java実装。
- [Casbinオンラインエディター](https://casbin.org/editor/) -- モデルとポリシーをインタラクティブにテスト。
- [opa.md](opa.md) -- 代替ポリシーエンジン（サイドカーパターン）。
- [oauth2.md](oauth2.md) -- 認証と認可のコンテキスト。
- [sso.md](sso.md) -- SSOと認可の関係。
