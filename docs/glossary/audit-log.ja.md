# Audit Log（監査ログ）

[English version](audit-log.md)

---

## これは何？

監査ログとは、誰が何を、いつ、どこから行ったかの時系列の記録です。ログイン、ログアウト、ロール変更、メンバー削除など、システム内のすべての重要なアクションが、事後に何が起きたかを再構成するのに十分な詳細とともに記録されます。監査ログは不変です：一度書かれたら、編集も削除もされません。

銀行の監視カメラのようなものです。カメラは強盗を防ぎませんが、起きたことすべてを記録します。インシデントの後、テープを巻き戻して、誰が入り、何をし、いつ出たかを正確に見ます。誰もテープを消せません。記録は記録された行為とは独立して存在します。

volta-auth-proxyでは、すべての認証イベントが`audit_logs`テーブルに記録されます。`AuditService.java`クラスが、アクター、ターゲット、タイムスタンプ、IPアドレス、リクエストIDを含む構造化されたログエントリを書き込みます。これらのログはテナントにスコープされ、デフォルトで365日保持されます。

---

## なぜ重要なのか？

監査ログがなければ：

- **フォレンジックなし**：セキュリティインシデント後に「誰が何にアクセスした？」に答えられない
- **説明責任なし**：ユーザーが行為を否定できる（「そのメンバーを削除していない」）
- **コンプライアンス不可**：SOC 2、GDPR、HIPAAなどの規制は監査証跡を要求
- **デバッグ不可**：何かがうまくいかないとき、イベントの連鎖を追跡できない
- **異常検出不可**：異常なパターン（1時間に3か国からのログイン）を発見できない

---

## どう動くのか？

### 監査ログエントリの構造

```
  ┌────────────────────────────────────────────────────┐
  │ 監査ログエントリ                                    │
  │                                                    │
  │ timestamp:    2026-03-31T14:32:07.123Z             │
  │ event_type:   MEMBER_ROLE_CHANGED                  │
  │ actor_id:     550e8400-e29b-41d4-...  （誰が）     │
  │ actor_ip:     192.168.1.100           （どこから） │
  │ tenant_id:    7c9e6679-7425-40de-...  （どの組織） │
  │ target_type:  MEMBER                  （何の種類） │
  │ target_id:    a8098c1a-f86e-11da-...  （どれ）     │
  │ detail:       { "old_role": "MEMBER",              │
  │                 "new_role": "ADMIN" }  （詳細）    │
  │ request_id:   b3e2a1f4-9d7c-4e8a-...  （追跡）    │
  └────────────────────────────────────────────────────┘
```

### ログ対象のイベント

| イベント | 発生タイミング | 詳細 |
|---------|---------------|------|
| LOGIN_SUCCESS | ユーザーがOIDCログインを完了 | プロバイダー、メール |
| LOGIN_FAILURE | OIDCコールバックが失敗 | 理由（stateの不一致、nonce無効等） |
| LOGOUT | ユーザーがログアウト | - |
| TENANT_JOINED | ユーザーが招待を承認 | テナント名、ロール |
| TENANT_SWITCHED | ユーザーがテナントを切り替え | 切替前/後のテナント |
| INVITATION_CREATED | 管理者が招待を作成 | メール、ロール、最大使用回数 |
| INVITATION_ACCEPTED | ユーザーが招待を承認 | 招待コード |
| INVITATION_CANCELLED | 管理者が招待をキャンセル | 招待コード |
| MEMBER_ROLE_CHANGED | 管理者がロールを変更 | 旧/新ロール |
| MEMBER_REMOVED | 管理者がメンバーを削除 | メンバーのメール |
| SESSION_REVOKED | ユーザーがセッションを無効化 | セッションID |
| ALL_SESSIONS_REVOKED | ユーザーが全セッションを無効化 | 件数 |
| KEY_ROTATED | オーナーがキーをローテート | Kid |
| KEY_REVOKED | オーナーがキーを失効 | Kid |

### 不変性

監査ログは追記専用です。データベーステーブルにはUPDATEやDELETE操作はなく、INSERTのみです。これにより、記録されたイベントが事後に改ざんされないことが保証されます。

```
  ┌──────────────────────────────────────┐
  │ audit_logsテーブル                    │
  │                                      │
  │ INSERT: ✓ 常に許可                   │
  │ SELECT: ✓ 認可されたユーザーに対して │
  │ UPDATE: ✗ 絶対に不可                 │
  │ DELETE: ✗ 絶対に不可（保持ポリシー   │
  │            が古いエントリを処理）     │
  └──────────────────────────────────────┘
```

---

## volta-auth-proxyではどう使われているか？

### AuditService.java

```java
// AuditService.java
public void log(Context ctx, String eventType, AuthPrincipal actor,
                String targetType, String targetId, Map<String, Object> detail) {
    UUID requestId = ctx.attribute("requestId");
    String detailJson = objectMapper.writeValueAsString(detail);
    store.insertAuditLog(
        eventType,
        actor.userId(),
        clientIp(ctx),          // X-Forwarded-For → 最初のIP
        actor.tenantId(),
        targetType,
        targetId,
        detailJson,
        requestId
    );
    sink.publish(sinkEvent);    // 外部消費者向けのAuditSink
}
```

### DSL定義の監査イベント

[状態マシン](state-machine.ja.md)のDSLが遷移の一部として監査アクションを定義します：

```yaml
# auth-machine.yaml
callback_success:
  actions:
    - { type: side_effect, action: upsert_user }
    - { type: side_effect, action: create_session }
    - { type: audit, event: LOGIN_SUCCESS }     # ← 監査ログエントリ

logout_browser:
  actions:
    - { type: side_effect, action: invalidate_session }
    - { type: audit, event: LOGOUT }            # ← 監査ログエントリ
```

### テナントスコープの監査アクセス

監査ログはテナントにスコープされます。ADMIN+のユーザーが管理UIから自テナントのログを閲覧できます：

```yaml
# auth-machine.yaml
show_audit_logs:
  trigger: "GET /admin/audit"
  guard: "membership.role in ['ADMIN', 'OWNER']"
  next: AUTHENTICATED
```

### 外部消費者向けのAuditSink

`AuditSink.java`はデータベースに加えて外部システム（SIEM、ロギングサービス等）に監査イベントを発行します。

### 保持ポリシー

```yaml
# dsl/policy.yaml
audit:
  retention:
    default_days: 365
    configurable: true
    env_var: AUDIT_RETENTION_DAYS
```

古い監査エントリは保持期間後にパージされます。コンプライアンス要件とストレージコストのバランスを取ります。

### リクエストID追跡

すべての監査ログエントリには`request_id`が含まれます。このUUIDは各HTTPリクエストの開始時に生成され、リクエストのライフサイクル全体を通じて流れます。1つのリクエストが複数の監査イベントをトリガーした場合（まれですが可能）、同じrequest_idを共有するため、相関が容易です。

---

## よくある間違いと攻撃

### 間違い1：ログが少なすぎる

「ログイン」と「ログアウト」しかログしなければ、ロール昇格攻撃を調査できません。voltaはセキュリティに関連するすべてのアクションをカバーする14のイベントタイプをログします。

### 間違い2：不必要にPIIをログする

監査ログには誰が何をしたかを特定するのに十分な情報を含むべきですが、パスワードやリクエストボディ全体のような機密データは含めるべきではありません。voltaはユーザーIDとメールをログしますが、パスワードやトークンは決してログしません。

### 間違い3：変更可能な監査ログ

監査ログが編集可能であれば、アクセスを得た攻撃者が痕跡を消せます。voltaのaudit_logsテーブルはUPDATEやDELETEアクセスのない追記専用です。

### 間違い4：監査アクセスのテナント分離なし

テナントAのADMINがテナントBの監査ログを見られたら、データ漏洩です。voltaはすべての監査クエリをJWTの`tenant_id`でフィルタリングします。

### 攻撃：監査ログの洪水

攻撃者が何千ものリクエストを行って監査ログにノイズを生成し、実際の攻撃を洪水の中に隠そうとする。voltaの[レート制限](enforcement.ja.md)がこれを防ぎ、`request_id`フィールドが関連イベントの相関に役立ちます。

---

## さらに学ぶために

- [dsl.md](dsl.md) -- DSLで定義される監査イベント。
- [session.md](session.md) -- 監査にログされるセッションイベント。
- [rbac.md](rbac.md) -- 監査ログ閲覧のロール要件。
- [tenant.md](tenant.md) -- 監査ログのテナントスコーピング。
- [internal-api.md](internal-api.md) -- 監査エントリを生成するAPI操作。
- [invitation-flow.md](invitation-flow.md) -- 監査にログされる招待イベント。
