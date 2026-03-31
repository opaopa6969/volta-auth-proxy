# セッション保存戦略

[English version](session-storage-strategies.md)

---

## これは何？

ユーザーがログインしたら、サーバーは「この人はログイン済み」と覚えておく必要があります。その記憶をどこに保存するかが「セッション保存戦略」です。主に3つのアプローチがあります：

1. **Cookie のみ（クライアント側）：** セッション情報を暗号化して Cookie 自体に格納。サーバーはステートレス。
2. **サーバー側 + インメモリストア（Redis/Memcached）：** Cookie にはセッション ID だけ。実データは高速キャッシュに。
3. **サーバー側 + データベース（Postgres/MySQL）：** Cookie にはセッション ID だけ。実データは RDB に。

---

## なぜ重要？

この選択は、セキュリティ、スケーラビリティ、セッション管理の自由度に影響します：

| 方式 | メリット | デメリット |
|------|---------|----------|
| **Cookie のみ** | サーバー状態なし、スケール容易、DB 不要 | 容量制限（約4KB）、個別セッション無効化不可、暗号化の注意が必要 |
| **Redis** | 高速読取り、TTL 自動期限切れ、スケール容易 | 追加インフラ、再起動でデータ消失（永続化しない場合）、複雑なクエリ不可 |
| **Postgres** | 豊富なクエリ（一覧・監査・無効化）、耐久性、既に使っていれば追加インフラ不要 | Redis より読取りが遅い、期限切れ行のクリーンアップが必要 |

### 重要な問い：セッションを無効化できるか？

Cookie のみの場合、デバイスを盗まれたと報告されても、そのセッションだけを無効化できません。暗号鍵を変更する必要があり、全員がログアウトされます。サーバー側保存なら、その行を削除するだけです。

---

## 簡単な例

**Cookie のみ：**
```
Cookie: session=暗号化{user_id:123, tenant_id:456, expires:2025-01-01T17:00}
サーバー: （何も知らない。リクエストごとに Cookie を復号）
```

**サーバー側（Postgres）：**
```
Cookie: session=a1b2c3d4-uuid-here

データベース:
| id         | user_id | tenant_id | expires_at          | invalidated_at |
|------------|---------|-----------|---------------------|----------------|
| a1b2c3d4.. | 123     | 456       | 2025-01-01 17:00:00 | NULL           |
```

---

## volta-auth-proxy では

volta は **Postgres でのサーバー側セッション保存**を採用しています。セッション Cookie には UUID（セッション ID）のみが含まれます。すべてのセッションデータは `sessions` テーブルに保存されます：

```sql
sessions(id, user_id, tenant_id, return_to, created_at, last_active_at,
         expires_at, invalidated_at, ip_address, user_agent, csrf_token)
```

### なぜ Redis ではなく Postgres？

1. **volta は既に Postgres を使用：** ユーザー、テナント、メンバーシップの管理に。追加インフラ不要。
2. **セッションにはリッチなクエリが必要：**「このユーザーの全セッション一覧」「このテナントの全セッション無効化」「IP アドレス付きセッション一覧（監査用）」。これらは SQL の自然な用途。
3. **耐久性が重要：** サーバーが再起動してもセッションは残る。デフォルトの Redis では残らない場合がある。
4. **性能は十分：** セッション検索は UUID の主キー検索で、Postgres はマイクロ秒で処理できる。volta の規模では Redis は不要。

Cookie は `HttpOnly; SameSite=Lax` で設定され、HTTPS の場合は `Secure` も追加されるため、JavaScript からセッション ID を読むことはできません。

---

## 関連項目

- [sliding-window-expiry.md](sliding-window-expiry.md) -- セッション有効期限の仕組み
- [session-hijacking.md](session-hijacking.md) -- 保存方式に関わらないセッションへの脅威
