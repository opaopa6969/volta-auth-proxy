# クロステナントアクセス

[English version](cross-tenant-access.md)

---

## これは何？

クロステナントアクセスとは、テナント A のユーザーがテナント B のデータを閲覧・変更できてしまうこと、またはその存在を知ることができてしまうことです。マルチテナント SaaS では、これは**絶対に**誤って起きてはいけません。他人の郵便物を読むのと同じことです。

---

## なぜ重要？

クロステナントのデータ漏洩は、SaaS 企業にとって最も壊滅的なセキュリティインシデントの一つです：

- **法的責任：** データ保護法（GDPR、SOC 2、HIPAA）に違反
- **顧客の信頼：** 一度の事故で、それを知ったすべての顧客を失う可能性
- **競合への露出：** 企業 A の戦略が企業 B（競合かもしれない）に見えてしまう

一般的なバグ（ボタンが壊れた、色が違う）とは異なり、クロステナントバグは SaaS ビジネスの存亡に関わる問題です。

---

## 簡単な例

### 攻撃

```
ユーザーはテナント A に所属（tenant_id = aaa）

1. ユーザーが呼び出し: GET /api/v1/tenants/bbb/members
                                         ^^^
                          （テナント B の ID。自分のではない！）

2. 保護なし: サーバーがテナント B のメンバーリストを返す
3. 保護あり: サーバーが JWT の volta_tid != bbb をチェック -> 403 拒否
```

### 微妙なバージョン

直接的な API 操作なしでも、クロステナントアクセスは以下を通じて発生する可能性があります：
- 他のテナントのデータを含む**検索結果**
- テナント名や ID を漏洩する**エラーメッセージ**
- 間違ったテナントのデータを提供する**共有キャッシュ**
- テナントでフィルターしない**エクスポート/インポート**機能

---

## volta-auth-proxy では

volta は**構造的な強制**によりクロステナントアクセスを防止します。チェックがアーキテクチャに組み込まれており、個々の開発者に任されていません：

### レイヤー 1: enforceTenantMatch()

テナントスコープのすべての API エンドポイントがこの関数を呼び出し、URL パスのテナント ID とユーザーの JWT 内の `volta_tid` を比較します：

```java
private static void enforceTenantMatch(AuthPrincipal principal, UUID tenantId) {
    if (principal.serviceToken()) {
        return;  // サービストークンはどのテナントにもアクセス可能
    }
    if (!principal.tenantId().equals(tenantId)) {
        throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied");
    }
}
```

これはすべてのテナントエンドポイントで呼ばれます：
```java
app.get("/api/v1/tenants/{tenantId}/members", ctx -> {
    enforceTenantMatch(p, tenantId);  // <-- 常にここにある
    // ... ハンドラーの残り
});
```

### レイヤー 2: クエリレベルのフィルタリング

パスチェックが通った後も、すべての SQL クエリが `WHERE tenant_id = ?` を明示的なフィルターとして含みます。これは多層防御を提供します。

### レイヤー 3: JWT にテナントが含まれる

ユーザーのテナントはログイン時に JWT に埋め込まれます。クライアントがサーバーから新しい JWT を取得せずにテナント ID を「切り替える」方法はなく、新しい JWT の取得にはそのテナントの有効なセッションが必要です。

---

## 関連項目

- [row-level-security.md](row-level-security.md) -- データベースレベルのテナント分離
- [tenant-resolution.md](tenant-resolution.md) -- 正しいテナントの判定方法
