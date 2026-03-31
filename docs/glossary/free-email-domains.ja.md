# フリーメールドメイン問題

[English version](free-email-domains.md)

---

## これは何？

フリーメールドメインとは、`gmail.com`、`yahoo.com`、`outlook.com`、`hotmail.com` のような、誰でもサインアップできるドメインです。「フリーメールドメイン問題」は、SaaS 製品がメールドメインに基づいてユーザーを自動的にテナントにグループ化しようとする時に発生します。

アイデアは合理的に聞こえます：「`@acme.com` のメールユーザーは全員 Acme テナントに所属」。しかし、`@gmail.com` でサインアップする人が来ると破綻します。Gmail ユーザー全員を同じテナントに入れるわけにはいきません。

---

## なぜ重要？

多くの SaaS 製品が自動テナント割り当てにメールドメインマッチングを使っています：

```
jane@acme.com   -> "Acme Corp" テナントに自動参加  （正しい！）
bob@acme.com    -> "Acme Corp" テナントに自動参加   （正しい！）
alice@gmail.com -> "Gmail" テナントに自動参加??      （間違い！）
```

フリーメールドメインを処理しないと：
- **プライバシー違反：** 見知らぬ人が同じテナントにグループ化され、お互いのデータが見える
- **セキュリティ侵害：** 攻撃者が `@gmail.com` でサインアップし、他の Gmail ユーザーのデータを見る
- **UX の崩壊：** ユーザーが何千人もの知らない人がいる巨大な「Gmail」テナントに参加

---

## 簡単な例

### 問題

```
テナント自動参加ルール: 「同じメールドメイン = 同じテナント」

ユーザー1: alice@acme.com     -> テナント "Acme Corp"     OK
ユーザー2: bob@acme.com       -> テナント "Acme Corp"     OK
ユーザー3: charlie@gmail.com  -> テナント "Gmail"          NG
ユーザー4: diana@gmail.com    -> テナント "Gmail"          NG
  （Charlie と Diana は他人なのにテナントを共有！）
```

### 解決策

フリーメールドメインのブロックリストを管理します。フリーメールドメインのユーザーがサインアップした場合、共有テナントに自動割り当てしません。代わりに個人ワークスペースを作成するか、招待を要求します。

一般的なフリーメールドメインには以下が含まれます：
`gmail.com`、`yahoo.com`、`yahoo.co.jp`、`outlook.com`、`hotmail.com`、`icloud.com`、`protonmail.com`、`mail.com` など数百種類。

---

## volta-auth-proxy では

volta はテナント解決の設計により、フリーメールドメイン問題を完全に回避しています：

1. **メールドメインによるテナントマッチングなし。** volta はメールドメインでユーザーを自動グループ化しません。
2. **招待ベースの参加。** 既存テナントに参加するには、有効な招待リンクが必要です。
3. **デフォルトで個人テナント。** 招待も既存メンバーシップもないユーザーには、その人専用の個人ワークスペースが作成されます。

```java
// resolveTenant() より:
if (inviteCode != null) {
    // 招待のテナントに参加（明示的、招待ベース）
    return store.findTenantById(invitation.tenantId()).orElseThrow();
}
if (tenants.isEmpty()) {
    // 個人テナントを作成（メールドメインで自動参加しない）
    return store.createPersonalTenant(user);
}
```

これにより、`alice@gmail.com` と `bob@gmail.com` はそれぞれ別の個人ワークスペースを持ちます。テナントを共有するには、一方が明示的にもう一方を招待する必要があります。これが最も安全なアプローチであり、設計によってフリーメールドメイン問題を排除しています。

---

## 関連項目

- [tenant-resolution.md](tenant-resolution.md) -- volta がユーザーの所属テナントを判定する方法
- [cross-tenant-access.md](cross-tenant-access.md) -- 誤ったグループ化が危険な理由
