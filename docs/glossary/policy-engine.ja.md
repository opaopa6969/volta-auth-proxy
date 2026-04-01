# Policy Engine（ポリシーエンジン）

[English version](policy-engine.md)

---

## これは何？

ポリシーエンジンとは、アクセス制御ルールを評価する専用ソフトウェアです。「ユーザーXはリソースZに対してアクションYを実行できるか？」という質問を与えると、許可または拒否の判定を返します。重要なのは、ルール（ポリシー）がアプリケーションコードから分離されていること。誰が何をできるかの変更はポリシーの編集で行い、アプリケーションの書き換えは不要です。

法廷の裁判官のようなものです。裁判官は法律（ポリシー）を書きませんし、人を逮捕（強制）もしません。裁判官は事件（「この人はこれをできるか？」）を受け取り、法典（ポリシーファイル）を参照し、判決（許可/拒否）を下します。法律は裁判官とは独立に更新できます。

volta-auth-proxyは現在、`dsl/policy.yaml`で定義された独自の[RBAC](rbac.ja.md)ロジックでアクセス制御を処理しています。Phase 4ではABAC（属性ベースアクセス制御）のようなより複雑なルールのために、専用のポリシーエンジンとしてjCasbinの統合を計画しています。

---

## なぜ重要なのか？

ポリシーエンジンがなければ、アクセス制御ルールはif/elseチェックとしてコードベース全体に散らばります：

- **監査が困難**：「誰がテナントを削除できる？」に答えるには、コードベース全体を検索する必要がある
- **変更が困難**：新しいロールの追加で何十ものファイルを変更する必要がある
- **不整合**：異なるエンドポイントが同じアクションに異なるルールを適用するかもしれない
- **履歴なし**：権限がいつ追加・削除されたか確認できない
- **密結合**：ポリシー変更にはアプリケーションの再起動が必要

ポリシーエンジンがあれば：

- **ルールの一元化**：すべてのポリシーが1か所に
- **ホットリロード**：アプリケーションを再起動せずにポリシーを変更
- **監査証跡**：ポリシー変更がバージョン管理されログに残る
- **関心の分離**：開発者がコードを書き、セキュリティチームがポリシーを書く

---

## どう動くのか？

### 3つのコンポーネント

```
  ┌─────────────┐    ┌──────────────────┐    ┌───────────────┐
  │ 強制ポイント │    │ ポリシーエンジン  │    │ ポリシーストア │
  │ (PEP)       │───►│ (決定ポイント)    │◄───│ (ルール)      │
  │              │    │                  │    │               │
  │ "リクエストを│    │ "ルールを評価、   │    │ "誰が何を     │
  │  ブロックか  │    │  許可/拒否を     │    │  どれに対して │
  │  許可か"     │    │  返す"           │    │  できるか"    │
  └─────────────┘    └──────────────────┘    └───────────────┘
      (コード)            (エンジン)              (データ)
```

### 一般的なポリシーモデル

| モデル | 仕組み | 例 |
|--------|--------|-----|
| **RBAC**（ロールベース） | 権限がロールに割り当てられ、ロールがユーザーに割り当てられる | ADMINがinvite_membersできる |
| **ABAC**（属性ベース） | ユーザー、リソース、環境の任意の属性に基づくルール | user.department == resource.department |
| **ReBAC**（関係ベース） | エンティティ間の関係に基づくルール | ユーザーがドキュメントを所有する組織のメンバー |
| **ACL**（アクセス制御リスト） | リソースごとの明示的な権限リスト | file.aclにuser_idが含まれる |

### jCasbinモデル（volta Phase 4）

jCasbinはPERM（Policy, Effect, Request, Matchers）モデルを使用します：

```
  モデル定義 (model.conf):
  ┌─────────────────────────────────────────────┐
  │ [request_definition]                        │
  │ r = sub, obj, act                           │
  │                                             │
  │ [policy_definition]                         │
  │ p = sub, obj, act                           │
  │                                             │
  │ [role_definition]                           │
  │ g = _, _                                    │
  │                                             │
  │ [policy_effect]                             │
  │ e = some(where (p.eft == allow))            │
  │                                             │
  │ [matchers]                                  │
  │ m = g(r.sub, p.sub) && r.obj == p.obj       │
  │     && r.act == p.act                       │
  └─────────────────────────────────────────────┘

  ポリシーファイル (policy.csv):
  ┌─────────────────────────────────────────────┐
  │ p, ADMIN, /admin/members, read              │
  │ p, ADMIN, /admin/invitations, read          │
  │ p, OWNER, /admin/keys, manage               │
  │ g, alice, ADMIN                             │
  │ g, ADMIN, MEMBER   (ロール継承)              │
  └─────────────────────────────────────────────┘
```

---

## volta-auth-proxyではどう使われているか？

### 現在のアプローチ：組み込みRBAC（Phase 1-3）

voltaは現在、`dsl/policy.yaml`のルールを使ってJavaコードで直接ポリシー評価を実装しています：

```
  リクエスト到着
  │
  ├── ForwardAuth (/auth/verify)
  │   │
  │   ├── セッション/JWTからユーザーのロールを読む
  │   ├── volta-config.yamlからアプリのallowed_rolesを読む
  │   └── チェック：user.role が app.allowed_roles に含まれる？
  │       ├── はい → 200 + X-Volta-*ヘッダー
  │       └── いいえ → 403 ROLE_INSUFFICIENT
  │
  └── 内部API (/api/v1/*)
      │
      ├── JWTからユーザーのロールを読む
      └── チェック：role >= required_role？
          （階層を使用：OWNER > ADMIN > MEMBER > VIEWER）
```

強制ロジックは`AppRegistry.java`（ForwardAuthのアプリマッチング用）と`AuthService.java`（API認可用）にあります。

### Phase 4：jCasbin統合

```
  現在（Phase 1-3）：              将来（Phase 4）：
  ┌──────────────────────┐         ┌──────────────────────┐
  │ AuthService.java     │         │ AuthService.java     │
  │                      │         │                      │
  │ if (role >= ADMIN) { │  ───►   │ if (casbin.enforce(  │
  │   allow();           │         │   user, resource,    │
  │ }                    │         │   action)) {         │
  │                      │         │   allow();           │
  └──────────────────────┘         │ }                    │
                                   └──────────────────────┘
  ルールがJavaコード内              ルールがポリシーファイル内
  変更が困難                        再起動なしで変更可能
  RBACのみ                          RBAC + ABAC + カスタム
```

### voltaがまだポリシーエンジンを使わない理由

voltaの哲学は「必要なものだけを作る」。現在の4ロールRBAC階層とアプリごとの`allowed_roles`はPhase 1-3のすべてのユースケースをカバーします。今jCasbinを追加しても即座の利益なく複雑さが増すだけ。Phase 4でマルチテナントABACポリシーが必要になったとき（例：「部門Xのユーザーは営業時間中のみアプリYにアクセスできる」）に導入します。

### policy.yamlが今日定義しているもの

```yaml
# dsl/policy.yaml
roles:
  hierarchy: [OWNER, ADMIN, MEMBER, VIEWER]

permissions:
  OWNER:
    inherits: ADMIN
    can: [delete_tenant, transfer_ownership, manage_signing_keys]
  ADMIN:
    inherits: MEMBER
    can: [invite_members, remove_members, view_audit_logs]
  MEMBER:
    inherits: VIEWER
    can: [use_apps, manage_own_sessions, switch_tenant]
  VIEWER:
    can: [read_only]
```

---

## よくある間違いと攻撃

### 間違い1：ポリシーエンジンの早すぎる導入

3ロール5エンドポイントのプロジェクトにOPAやCedarを追加するのはオーバーエンジニアリング。ルールを書くより設定に時間がかかります。コードベースのRBACから始め、複雑さが要求したときに移行しましょう。

### 間違い2：強制なきポリシーエンジン

「拒否」を返すポリシーエンジンも、コードがその結果をチェックしなければ無用です。保護されたすべてのエンドポイントがエンジンを呼び出し、その判定に従う必要があります。

### 間違い3：過度に複雑なポリシー

500行のRegoポリシーは、それが置き換えたスパゲッティコードより監査が困難です。ポリシーはシンプルに保ち、徹底的にテストし、各ルールが存在する理由を文書化しましょう。

### 攻撃：ポリシーのバイパス

ポリシーエンジンが別サービスの場合、攻撃者がエンジンを迂回してアプリケーションに直接アクセスしようとするかもしれません。voltaは外部サービスではなく同じプロセス（ForwardAuth）内でポリシーを強制してこれを防ぎます。

---

## さらに学ぶために

- [rbac.md](rbac.md) -- voltaの現在のロールベースアクセス制御。
- [role.md](role.md) -- voltaの階層の4つのロール。
- [hierarchy.md](hierarchy.md) -- ロール継承の仕組み。
- [enforcement.md](enforcement.md) -- ポリシーの強制方法。
- [dsl.md](dsl.md) -- voltaの現在のポリシーが定義される場所。
- [forwardauth.md](forwardauth.md) -- アプリアクセスポリシーが強制される場所。
