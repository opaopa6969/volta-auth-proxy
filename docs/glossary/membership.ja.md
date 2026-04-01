# Membership（メンバーシップ）

[English version](membership.md)

---

## これは何？

メンバーシップとは、ユーザーと[テナント](tenant.ja.md)（ワークスペース）の間の関係です。特定の人が特定の組織に所属していること、どの[ロール](role.ja.md)を持っているか、現在アクティブかどうかを記録します。ユーザーは複数のテナントにメンバーシップを持てますが、各メンバーシップは独立しています。あるワークスペースではADMIN、別のワークスペースではVIEWERということがあり得ます。

現実のクラブ会員のようなものです。ジム、読書会、専門家団体に同時に会員になれます。各会員資格には独自のステータス（アクティブ/非アクティブ）、独自のレベル（スタンダード/プレミアム）があり、独立して管理されます。ジムを退会させられても、読書会の会員資格には影響しません。

volta-auth-proxyでは、メンバーシップは`tenant_members`テーブルに`user_id`、`tenant_id`、`role`、`is_active`のフィールドで保存されます。`MembershipRecord` Javaレコードがコード内でこの関係を表現します。

---

## なぜ重要なのか？

メンバーシップはマルチテナント認可の基盤です。なければ：

- **テナント分離なし**：どのユーザーがどのワークスペースに所属するか判断できない
- **ロール割り当てなし**：ロールは抽象的に存在するが、誰も持っていない
- **アクセス制御なし**：ユーザーにロールがなければForwardAuthは`allowed_roles`をチェックできない
- **招待フローなし**：招待はメンバーシップを作成する -- この概念がなければフローに終着点がない
- **監査証跡なし**：「ユーザーXがテナントZでYをした」にはXがZのメンバーであることの知識が必要

---

## どう動くのか？

### メンバーシップのデータモデル

```
  ┌─────────────────────────────────────────────────────┐
  │ tenant_membersテーブル                               │
  │                                                     │
  │ id:         uuid (主キー)                           │
  │ user_id:    uuid → users.id                         │
  │ tenant_id:  uuid → tenants.id                       │
  │ role:       enum [OWNER, ADMIN, MEMBER, VIEWER]     │
  │ is_active:  boolean                                 │
  │ joined_at:  timestamp                               │
  │ invited_by: uuid → users.id (nullable)              │
  │                                                     │
  │ UNIQUE制約: (user_id, tenant_id)                    │
  │ ユーザーは1テナントにつき1つのメンバーシップのみ。   │
  └─────────────────────────────────────────────────────┘
```

### 多対多の関係

```
  ユーザー              メンバーシップ             テナント
  ┌──────────┐          ┌──────────────┐         ┌──────────┐
  │ Alice    │─────────►│ ADMIN        │◄────────│ Acme     │
  │          │          └──────────────┘         │          │
  │          │─────────►│ VIEWER       │◄────────│ Side LLC │
  └──────────┘          └──────────────┘         └──────────┘
  ┌──────────┐          ┌──────────────┐
  │ Bob      │─────────►│ OWNER        │◄────────┐
  │          │          └──────────────┘         │ Acme
  │          │─────────►│ MEMBER       │◄────────┘
  └──────────┘          └──────────────┘         ┌──────────┐
                        │ MEMBER       │◄────────│ OpenOrg  │
                        └──────────────┘         └──────────┘
```

### メンバーシップのライフサイクル

```
  ┌──────────────┐    招待承認        ┌──────────────┐
  │メンバーシップ │ ─────────────────►│ アクティブ    │
  │ なし         │                   │ メンバー      │
  └──────────────┘                   │ (is_active=  │
                                     │  true)       │
                                     └──────┬───────┘
                                            │
                              ┌──────────────┤
                              │              │
                         ロール変更     管理者による削除
                              │              │
                              ▼              ▼
                     ┌──────────────┐ ┌──────────────┐
                     │ アクティブ    │ │ 無効化       │
                     │ メンバー      │ │ (is_active=  │
                     │ (新ロール)   │ │  false)       │
                     └──────────────┘ └──────────────┘
```

---

## volta-auth-proxyではどう使われているか？

### Models.javaのMembershipRecord

```java
record MembershipRecord(
    UUID id,
    UUID userId,
    UUID tenantId,
    String role,
    boolean active
) {}
```

### 招待によるメンバーシップ作成

メンバーシップは直接作成されません。ユーザーが[招待](invitation-flow.ja.md)を承認したときに作成されます：

```yaml
# auth-machine.yaml — INVITE_CONSENT状態
accept:
  trigger: "POST /invite/{code}/accept"
  guard: "invite.valid && !invite.expired && !invite.used && invite.email_match"
  actions:
    - { type: guard_check, check: csrf_token_valid }
    - { type: side_effect, action: create_membership }   # ← ここでメンバーシップ作成
    - { type: side_effect, action: consume_invitation }
    - { type: side_effect, action: set_session_tenant }
    - { type: audit, event: INVITATION_ACCEPTED }
    - { type: audit, event: TENANT_JOINED }
  next: AUTHENTICATED
```

### ガード内のメンバーシップチェック

状態マシンは`membership.*`コンテキスト変数を使用します：

```yaml
# ユーザーはターゲットテナントにメンバーシップがあるか？
guard: "membership.exists && membership.active && tenant.active"

# ユーザーはADMIN以上か？
guard: "membership.role in ['ADMIN', 'OWNER']"
```

### セッション-テナントバインディング

[セッション](session.ja.md)は一度に正確に1つのテナントにバインドされます。ユーザーがテナントを切り替えると、セッションの`tenant_id`が更新されます（実際には新しいセッションが作成されます）：

```yaml
# policy.yaml
- id: session_tenant_bound
  rule: "セッションは一度に正確に1つのテナントにバインドされる"
  enforcement: "session.tenant_idが設定される。テナント変更は新セッションを作成"
```

### ForwardAuthレスポンスのメンバーシップ

ForwardAuthが成功すると、メンバーシップからのユーザーのロールがレスポンスヘッダーに含まれます：

```yaml
# protocol.yaml
X-Volta-Roles:
  type: csv
  source: membership.roles
  example: "ADMIN,MEMBER"
```

### メンバーシップの制約

```yaml
# policy.yaml制約
- id: last_owner
  rule: "テナントには常に最低1人のOWNERが必要"
  error: "LAST_OWNER_CANNOT_CHANGE"

- id: max_tenants
  rule: "ユーザーは最大MAX_TENANTS_PER_USERのテナントに所属可能"
  default: 10
  error: "MAX_TENANTS_REACHED"

- id: max_members
  rule: "テナントは最大tenant.max_membersのメンバーを持てる"
  default: 50
  error: "MAX_MEMBERS_REACHED"
```

### 内部APIによるメンバーシップCRUD

```yaml
# protocol.yaml
GET    /tenants/{tenantId}/members           # 一覧（MEMBER+）
GET    /tenants/{tenantId}/members/{id}      # 1件取得（MEMBER+）
PATCH  /tenants/{tenantId}/members/{id}      # ロール変更（ADMIN+）
DELETE /tenants/{tenantId}/members/{id}      # 削除/無効化（ADMIN+）
```

### ソフトデリート

メンバーが削除されると、`is_active`が`false`に設定されます。レコードは削除されません：

```
  削除前：  { user_id: alice, tenant_id: acme, role: MEMBER, is_active: true }
  削除後：  { user_id: alice, tenant_id: acme, role: MEMBER, is_active: false }
  さらに：  acme内のaliceのすべてのセッションが無効化
```

これにより監査履歴が保持され、再有効化の可能性も残ります。

---

## よくある間違いと攻撃

### 間違い1：(user_id, tenant_id)の一意制約なし

この制約がなければ、ユーザーが同じテナントに異なるロールで複数のメンバーシップを持てます。どのロールが適用されるか曖昧になります。voltaはデータベースレベルで一意性を強制します。

### 間違い2：メンバーシップのハードデリート

行を削除すると、誰がメンバーでいつからだったかの記録を失います。voltaは`is_active = false`を設定してソフトデリートします。

### 間違い3：削除時にセッションを無効化しない

メンバーを削除してもセッションに古い`tenant_id`が残っていれば、セッションが期限切れになるまでテナントにアクセスし続けられます。voltaは削除時にそのテナントのユーザーのすべてのセッションを即座に無効化します。

### 攻撃：メンバーシップの列挙

攻撃者が所属していないテナントのメンバーを一覧しようとするかもしれません。voltaのテナント分離[強制](enforcement.ja.md)により、`GET /tenants/{tenantId}/members`はJWTの`volta_tid`が`{tenantId}`に一致する場合のみ機能します。

### 攻撃：ロール操作

攻撃者が`PATCH /members/{self}`を`role: OWNER`で呼び出して自分のロールを変更しようとするかもしれません。voltaの[階層](hierarchy.ja.md)制約が自分のロール以上へのプロモートを防ぎ、OWNER譲渡は別の特権エンドポイントです。

---

## さらに学ぶために

- [tenant.md](tenant.md) -- メンバーシップが所属する組織。
- [role.md](role.md) -- メンバーシップで割り当てられるロール。
- [hierarchy.md](hierarchy.md) -- ロールの順序付け。
- [invitation-flow.md](invitation-flow.md) -- メンバーシップの作成方法。
- [rbac.md](rbac.md) -- メンバーシップがロールベースアクセス制御を可能にする方法。
- [session.md](session.md) -- セッションがメンバーシップにどうバインドされるか。
- [audit-log.md](audit-log.md) -- 監査のためにログされるメンバーシップ変更。
- [crud.md](crud.md) -- メンバーシップのCRUD操作。
