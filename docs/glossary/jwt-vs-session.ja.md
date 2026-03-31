# JWT vs セッション

[English version](jwt-vs-session.md)

---

## これは何？

リクエスト間でユーザーが誰かを覚えておくための、2つの異なるアプローチです：

- **セッション：** サーバーがユーザー状態を保存（データベースやキャッシュに）し、ブラウザには検索用の短い ID（Cookie）を渡す。セッション ID 自体には意味がなく、ただのポインタ。
- **JWT：** トークン自体にすべてのユーザー情報が含まれ、暗号署名されている。サーバーは何も検索する必要がなく、署名を検証してクレームを読むだけ。

図書館カードと紹介状の違いです。図書館カード（セッション ID）は図書館のデータベースなしでは無意味です。署名された紹介状（JWT）は自己完結型で、署名者を信頼する人なら誰でも読めます。

---

## なぜ重要？

| 観点 | セッション | JWT |
|------|----------|-----|
| **状態** | ステートフル -- サーバーがデータを保存 | ステートレス -- トークンがデータを運ぶ |
| **無効化** | 簡単 -- 行を削除するだけ | 難しい -- 期限切れまで有効 |
| **スケーラビリティ** | サーバー間で共有ストレージが必要 | どのサーバーでも独立して検証可能 |
| **サイズ** | Cookie は極小（UUID） | トークンは大きくなりうる（1KB 以上） |
| **向いている用途** | ブラウザ（Cookie 転送） | API、モバイルアプリ、サービス間通信 |

どちらが普遍的に優れているわけではなく、利用者によって正しい選択が変わります。

---

## 簡単な例

**セッションフロー（ブラウザ）：**
```
ブラウザ -> GET /dashboard
            Cookie: volta_session=abc-123
サーバー -> Postgres で abc-123 を検索
            見つかった: user_id=42, tenant_id=7, role=ADMIN
            ダッシュボードを返す
```

**JWT フロー（API クライアント）：**
```
アプリ   -> GET /api/v1/users/me
            Authorization: Bearer eyJhbGci...
サーバー -> 署名を検証、クレームを読む
            sub=42, volta_tid=7, volta_roles=[ADMIN]
            ユーザーデータを返す（DB 検索不要）
```

---

## volta-auth-proxy では

volta は**両方**を使います。ブラウザにはセッション、それ以外には JWT：

**ブラウザユーザー**にはセッション Cookie（`volta_session`）が発行されます。この Cookie は `HttpOnly`（JavaScript で読めない）かつ `SameSite=Lax`（CSRF 対策）です。セッションは Postgres に保存され、即座に無効化できます。

**API クライアントと下流アプリ**は `Authorization: Bearer` ヘッダーで JWT を使います。JWT は短命（5分、`JWT_TTL_SECONDS=300`）で、無効化の問題を最小化します。JWT を「無効化」する必要がある場合は、5分で期限切れになるのを待つだけです。

**橋渡し：** ブラウザが API 呼び出しを行う場合（volta-sdk-js 経由）、SDK はまずセッション Cookie で `/auth/token` を呼んで新しい JWT を取得し、その JWT で実際の API 呼び出しを行います。これにより、ブラウザにはセッションのセキュリティ（無効化可能、HttpOnly）、API には JWT の利便性（ステートレス、自己完結型）を提供します。

```
セッション Cookie を持つブラウザ
    |
    v
/auth/token（セッションを短命 JWT に交換）
    |
    v
Authorization: Bearer <JWT> で API 呼び出し
```

この二重アプローチは、現代の認証ゲートウェイで一般的なベスト・オブ・ボス・ワールドの設計です。

---

## 関連項目

- [session-storage-strategies.md](session-storage-strategies.md) -- volta のセッション保存方法
- [jwt-payload.md](jwt-payload.md) -- volta の JWT の中身
- [bearer-scheme.md](bearer-scheme.md) -- HTTP ヘッダーでの JWT の送信方法
