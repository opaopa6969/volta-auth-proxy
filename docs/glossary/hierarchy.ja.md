# Hierarchy（階層）

[English version](hierarchy.md)

---

## これは何？

階層とは、上位レベルが下位レベルの能力を含む順序付きレベルのシステムです。アクセス制御において、ロール階層は上位ロールのユーザーが自動的に下位のすべてのロールの権限を持つことを意味します。権限を個別に割り当てる必要はなく、階層を通じて継承されます。

軍の階級のようなものです。将軍は大佐ができるすべてのことができ、大佐は大尉ができるすべてのことができます。将軍に「大尉の権限」の別リストを渡す必要はありません。チェーンで大尉の上にいることで自動的にそれを得ます。これが階層ベースの継承です。

volta-auth-proxyは4レベルのロール[階層](role.ja.md)を定義します：OWNER > ADMIN > MEMBER > VIEWER。各ロールが下位のロールのすべての権限を継承します。

---

## なぜ重要なのか？

階層がなければ、すべてのロールにすべての権限を明示的に割り当てる必要があります：

- **割り当ての爆発**：4ロール x 20権限 = 保守すべき80の個別割り当て
- **不整合のリスク**：MEMBERに新しい権限を追加してADMINに追加し忘れると、ADMINの権限がMEMBERより少なくなる
- **監査の困難**：ADMINが何をできるかを見るには、すべての個別の権限割り当てを調べなければならない

階層があれば：

- **継承**：MEMBERに権限を追加すれば、自動的にADMINとOWNERにも付与される
- **シンプルなメンタルモデル**：「ADMINはMEMBERができるすべてのこと＋α ができる」
- **容易な監査**：各ロールは自身固有の権限のみリストする

---

## どう動くのか？

### ロールの継承

```
  OWNER（最上位）
  │
  │ ADMINからすべてを継承、さらに：
  │   - delete_tenant
  │   - transfer_ownership
  │   - manage_signing_keys
  │   - change_tenant_slug
  │
  └── ADMIN
      │
      │ MEMBERからすべてを継承、さらに：
      │   - invite_members
      │   - remove_members
      │   - change_member_role
      │   - view_audit_logs
      │
      └── MEMBER
          │
          │ VIEWERからすべてを継承、さらに：
          │   - use_apps
          │   - manage_own_sessions
          │   - switch_tenant
          │   - accept_invitation
          │
          └── VIEWER（最下位）
              │
              │ 基本権限：
              │   - read_only
              └──
```

### 実効権限

ロールの実効権限は自身の権限＋すべての継承された権限です：

```
  ┌─────────┬────────────────────────────────────────────────┐
  │ ロール   │ 実効権限                                      │
  │─────────│────────────────────────────────────────────────│
  │ VIEWER  │ read_only                                     │
  │ MEMBER  │ read_only + use_apps + manage_sessions + ...  │
  │ ADMIN   │ 全MEMBER権限 + invite + remove + audit        │
  │ OWNER   │ 全ADMIN権限 + delete_tenant + transfer        │
  └─────────┴────────────────────────────────────────────────┘
```

### 階層比較演算子

voltaは`>=`で階層をチェックします。「ADMIN+」は「ADMIN以上」を意味します：

```
  OWNER  >= ADMIN?   → true  （OWNERはADMINの上）
  ADMIN  >= ADMIN?   → true  （同レベル）
  MEMBER >= ADMIN?   → false （MEMBERはADMINの下）
  VIEWER >= ADMIN?   → false （VIEWERはADMINの下）
```

---

## volta-auth-proxyではどう使われているか？

### `dsl/policy.yaml`での定義

```yaml
roles:
  hierarchy:
    - OWNER     # 最上位
    - ADMIN
    - MEMBER
    - VIEWER    # 最下位

permissions:
  OWNER:
    inherits: ADMIN
    can:
      - delete_tenant
      - transfer_ownership
      - manage_signing_keys
  ADMIN:
    inherits: MEMBER
    can:
      - invite_members
      - remove_members
      - change_member_role
      - view_audit_logs
  MEMBER:
    inherits: VIEWER
    can:
      - use_apps
      - manage_own_sessions
      - switch_tenant
  VIEWER:
    can:
      - read_only
```

### ForwardAuthアプリアクセス

各アプリが`volta-config.yaml`で`allowed_roles`を定義。階層がアクセスを決定します：

```yaml
apps:
  - id: app-wiki
    allowed_roles: [MEMBER, ADMIN, OWNER]    # MEMBER+
  - id: app-admin
    allowed_roles: [ADMIN, OWNER]            # ADMIN+
  - id: app-billing
    allowed_roles: [OWNER]                   # OWNERのみ
```

### APIエンドポイント認可

`protocol.yaml`は「ADMIN+」のような階層表記を使い、「ADMINまたはADMINより上のロール」を意味します：

```yaml
- method: GET
  path: /tenants/{tenantId}/members
  auth: "MEMBER+"        # MEMBER、ADMIN、またはOWNER

- method: PATCH
  path: /tenants/{tenantId}/members/{memberId}
  auth: "ADMIN+"         # ADMINまたはOWNERのみ

- method: POST
  path: /tenants/{tenantId}/transfer-ownership
  auth: "OWNER"          # OWNERのみ、継承なし
```

### プロモート制限

階層はユーザーが自分のレベルより上に他者をプロモートできないよう強制します：

```yaml
# policy.yaml制約
- id: promote_limit
  rule: "自分のロールより上にはプロモートできない"
  enforcement: "ADMINはADMINまでプロモート可能。OWNERにできるのはOWNERのみ"
```

```
  ADMINがMEMBERをOWNERにプロモートしようとする？
  ├── ADMIN < OWNER → 拒否
  │
  ADMINがMEMBERをADMINにプロモートしようとする？
  ├── ADMIN >= ADMIN → 許可
  │
  OWNERがMEMBERをOWNERにプロモートしようとする？
  ├── これは「オーナー譲渡」という特別な操作
  └── POST /tenants/{id}/transfer-ownershipが必要
```

### 階層を使うガード式

```yaml
# auth-machine.yaml
show_members:
  trigger: "GET /admin/members"
  guard: "membership.role in ['ADMIN', 'OWNER']"   # ADMIN+
  next: AUTHENTICATED
```

---

## よくある間違いと攻撃

### 間違い1：フラットなロールシステム

ADMINとMEMBERを無関係なロールとして扱うと、ADMINが自動的にMEMBER権限を得ません。新しいMEMBER権限をADMINにも手動で追加する必要があります。階層がこの保守負担を排除します。

### 間違い2：上方プロモートを許可

ADMINがユーザーをOWNERにプロモートできれば、階層が崩壊します -- どのADMINも完全な制御に昇格できます。voltaは自分のレベルまでしかプロモートできないよう強制します。

### 間違い3：「最後のOWNER」保護なし

最後のOWNERがMEMBERにデモートされると、テナント削除のようなOWNERレベルの操作を誰も実行できなくなります。voltaの`last_owner`制約がこれを防ぎます。

### 攻撃：自己プロモートによる権限昇格

ADMINが`PATCH /members/{self}`で自分のロールをOWNERに変更しようとする。voltaは`promote_limit`制約が自分のロール以上へのプロモートをブロックするため防ぎます。OWNER譲渡は既存のOWNER認可を必要とする別のエンドポイントです。

---

## さらに学ぶために

- [role.md](role.md) -- voltaの階層の4つのロール。
- [rbac.md](rbac.md) -- ロールが権限にどうマッピングされるか。
- [policy-engine.md](policy-engine.md) -- Phase 4の外部ポリシー評価。
- [enforcement.md](enforcement.md) -- 階層が実行時にどう強制されるか。
- [membership.md](membership.md) -- ロール割り当てが保存される場所。
- [invitation-flow.md](invitation-flow.md) -- 招待時にロールがどう割り当てられるか。
