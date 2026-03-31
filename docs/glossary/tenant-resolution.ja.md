# テナント解決

[English version](tenant-resolution.md)

---

## これは何？

テナント解決とは、リクエストがどのテナント（ワークスペース、組織）に属するかを判定するプロセスです。ユーザーが API にアクセスしたとき、「このリクエストはどのテナントのデータにアクセスすべきか？」という問いに答える必要があります。この答えがすべてを決定します -- 見えるデータ、適用される権限、課金されるアカウント。

ホテルのフロント係があなたの部屋を特定するようなものです。予約名、ルームキー、身分証明書など、異なる手がかりからすべて同じ部屋にたどり着きます。

---

## なぜ重要？

テナント解決が間違っていると、結果は深刻です。ユーザーが別のテナントのデータを見たり、別のテナントの設定を変更したり、間違ったアカウントに課金されたりする可能性があります。すべてのリクエストが 100% の確実性でちょうど1つのテナントに解決されなければなりません。

---

## 簡単な例

一般的なテナント解決の戦略：

| 戦略 | 仕組み | 例 |
|------|--------|-----|
| **サブドメイン** | URL からテナントを解析 | `acme.myapp.com` -> テナント "acme" |
| **パスプレフィックス** | URL パスにテナント ID | `/tenants/abc-123/members` -> テナント "abc-123" |
| **JWT クレーム** | トークンからテナントを読む | JWT 内の `volta_tid: "abc-123"` |
| **リクエストヘッダー** | カスタムヘッダー | `X-Tenant-ID: abc-123` |
| **ユーザー検索** | DB でユーザーのテナントを検索 | ユーザー 42 はテナント "abc-123" に所属 |

各アプローチにはシンプルさ、セキュリティ、柔軟性のトレードオフがあります。

---

## volta-auth-proxy では

volta はログイン時に**多段階の解決**戦略を使用します。新しいユーザーがログインすると、volta は優先度チェーンでテナントを判定します：

### 解決の優先順位（ログイン時）

1. **招待コード**（最高優先度）：ログイン URL に招待コードが含まれていれば、その招待のテナントに参加。
2. **既存のメンバーシップ**：ユーザーが既にテナントに所属していれば、最初に見つかったものを使用。
3. **個人テナントの自動作成**（最低優先度）：テナントが一つもなければ、個人ワークスペースを作成。

```java
private static TenantRecord resolveTenant(SqlStore store, UserRecord user, String inviteCode) {
    if (inviteCode != null) {
        // 優先度1: 招待がテナントを決定
        InvitationRecord invitation = store.findInvitationByCode(inviteCode).orElseThrow();
        return store.findTenantById(invitation.tenantId()).orElseThrow();
    }
    List<TenantRecord> tenants = store.findTenantsByUser(user.id());
    if (tenants.isEmpty()) {
        // 優先度3: 個人テナントを作成
        return store.createPersonalTenant(user);
    }
    // 優先度2: 最初の既存テナントを使用
    return tenants.getFirst();
}
```

### API リクエストの解決（ログイン後）

ログイン後のテナント解決は単純です。セッションが `tenant_id` を保持し、JWT が `volta_tid` を含んでいます。API 呼び出しでは、URL パスのテナント ID が JWT の `volta_tid` と一致する必要があり、`enforceTenantMatch()` で強制されます。

---

## 関連項目

- [cross-tenant-access.md](cross-tenant-access.md) -- 解決がバイパスされた場合の影響
- [row-level-security.md](row-level-security.md) -- クエリが解決されたテナントをどう強制するか
