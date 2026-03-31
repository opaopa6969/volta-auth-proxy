# Bearer スキーム

[English version](bearer-scheme.md)

---

## これは何？

Bearer スキームは、HTTP リクエストで認証トークンを送る方法です。`Authorization` ヘッダーにトークンを入れます：

```
Authorization: Bearer eyJhbGciOiJSUzI1NiJ9...
```

「Bearer」は「このトークンを持っている（bear する）人にアクセスを許可する」という意味です。コンサートチケットのようなもので、誰が買ったかに関係なく、持っている人が入場できます。サーバーはあなたが誰かではなく、トークンが有効かを確認します。

---

## なぜ重要？

Bearer トークンは、API がリクエストを認証する標準的な方法です。シンプルでステートレスで、あらゆる HTTP クライアント（モバイルアプリ、CLI ツール、他のサーバー、JavaScript フロントエンド）で動作します。

デメリットは「持っている人なら誰でも」という部分です。Bearer トークンが漏洩すると（ログ、URL、エラーメッセージなどで）、見つけた人は誰でも使えます。そのため Bearer トークンは：
- 短命であるべき（volta: 5分）
- HTTPS 経由のみで送信
- URL やログに絶対に表示しない

---

## 簡単な例

```
# 認証済み API 呼び出し
curl -H "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9..." \
     https://auth.example.com/api/v1/users/me

# サーバーの処理:
# 1. "Bearer " の後のすべてを抽出
# 2. JWT の署名を検証
# 3. クレームを読む（sub, volta_tid, volta_roles）
# 4. レスポンスを返す（無効なら 401）
```

### なぜトークンだけではなく "Bearer" が必要？

`Authorization` ヘッダーは複数の**スキーム**をサポートしています：
- `Bearer <token>` -- トークンベース（API で最も一般的）
- `Basic <base64>` -- username:password を base64 エンコード
- `Digest <params>` -- チャレンジ・レスポンス認証

スキームのプレフィックスにより、サーバーはどの認証方法を使うか判断できます。これがないと、サーバーはヘッダー値の解釈方法がわかりません。

---

## volta-auth-proxy では

volta はすべての API 認証に Bearer トークンを使用します。`AuthService` はセッション Cookie にフォールバックする前に、まず `Authorization` ヘッダーをチェックします：

`/api/v1/*` へのリクエスト到着時：
1. `Authorization: Bearer <token>` ヘッダーを確認
2. 存在すれば JWT を検証（署名、有効期限、発行者、対象者）
3. 存在しなければセッション Cookie（`volta_session`）を確認
4. どちらもなければ `401 AUTHENTICATION_REQUIRED` を返す

このデュアルアプローチにより、ブラウザは Cookie ベースのセッション（ブラウザにはより安全）を使い、API は Bearer トークン（プログラマティックアクセスにはより便利）を使えます。

volta は `VOLTA_SERVICE_TOKEN` もサポートしています。これは完全な Client Credentials フローが実装されるまでの Phase 1 でのマシン間通信用の静的 Bearer トークンです。

---

## 関連項目

- [jwt-vs-session.md](jwt-vs-session.md) -- Bearer トークンとセッションの使い分け
- [jwt-payload.md](jwt-payload.md) -- Bearer トークンの中身
