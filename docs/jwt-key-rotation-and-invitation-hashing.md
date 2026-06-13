# JWT 署名鍵ローテーション ＆ 招待トークンのハッシュ化

このドキュメントは、2つのセキュリティ強化を説明する。

1. JWT 署名鍵のローテーション（複数鍵を同時に JWKS へ公開）
2. 招待トークン（invitation code）のハッシュ化保存

---

## 1. JWT 署名鍵ローテーション

### 鍵の保管モデル

署名鍵は PEM ファイルではなく **データベース (`signing_keys` テーブル)** で管理される。
各鍵は `JWT_KEY_ENCRYPTION_SECRET` で暗号化された状態で保存され、`status` カラムで状態を持つ。

| status   | 意味                                                         | 署名 | 検証 | JWKS 公開 |
|----------|--------------------------------------------------------------|:---:|:---:|:--------:|
| `active` | 現在の署名鍵。新規発行 JWT はこの鍵で署名される              | ○   | ○   | ○        |
| `rotated`| ローテーション猶予中の旧鍵（retiring）。既発行 JWT 検証用    | ×   | ○   | ○        |
| `revoked`| 失効済み。検証にも JWKS にも使われない                       | ×   | ×   | ×        |

`JwtService` は起動時に `active` 鍵を署名用にロードし、加えて `active`＋`rotated`
の全鍵を **検証鍵セット** (`verificationKeys`、kid をキーとする Map) としてロードする。

- **署名**: 常に `active` 鍵で行う。各 JWT のヘッダに `kid` を付与する。
- **検証**: JWT ヘッダの `kid` で検証鍵セットから鍵を選択する
  (`JwtService.selectKey`)。`kid` が無い／未知の場合は `active` 鍵にフォールバック
  （単一鍵構成・旧トークンとの後方互換）。
  未知の `kid` の場合は、別ノードがローテーションした可能性を考慮し検証鍵を
  1度だけ DB から再ロードしてリトライする。
- **JWKS** (`/.well-known/jwks.json`): 検証鍵セット（`active`＋`rotated`）を
  **全て** `keys` 配列に載せる。active が先頭。これにより、旧鍵で署名された
  既発行 JWT も猶予期間中は relying party が検証できる。

### 設定方法

鍵そのものは自動生成されるため PEM を直接渡す必要はない。関連する環境変数:

| 環境変数                    | 既定値                     | 説明                                       |
|-----------------------------|----------------------------|--------------------------------------------|
| `JWT_KEY_ENCRYPTION_SECRET` | `dev-only-secret-change-me`| `signing_keys` の秘密鍵を暗号化する鍵。**本番では必ず変更** |
| `JWT_ISSUER`                | `volta-auth`               | `iss` クレーム                             |
| `JWT_AUDIENCE`              | `volta-apps`               | `aud` クレーム                             |
| `JWT_TTL_SECONDS`           | `300`                      | アクセストークンの TTL                     |

初回起動時、`active` 鍵が無ければ自動生成される（後方互換: 単一鍵として従来通り動く）。

> **複数 PEM を持ち込みたい場合**: 現状は DB 管理が唯一の経路。外部生成の鍵を
> 載せたい場合は、暗号化済み public/private を `signing_keys` に
> `status='active'`（旧鍵は `status='rotated'`）として直接 INSERT する。
> kid は鍵ごとに一意であること。

### ローテーション手順

鍵交換は **新鍵を active 昇格 → 旧鍵を rotated（retiring）に降格 → grace 経過後に revoke** の順で行う。
これは Owner ロールの管理 API で実行できる。

1. **新鍵を発行し active に昇格（旧鍵は自動で rotated に降格）**

   ```
   POST /api/v1/admin/keys/rotate     (Owner 権限)
   → { "ok": true, "kid": "key-..." }
   ```

   この時点で:
   - 新鍵 = `active`（以降の JWT 署名に使用）
   - 旧鍵 = `rotated`（既発行 JWT の検証用に JWKS へ載り続ける）
   - JWKS には両鍵が並ぶ。

2. **猶予期間（grace）を待つ**

   旧鍵で署名された既発行 JWT が全て失効するまで待つ。
   最低でも `JWT_TTL_SECONDS`（既定 300 秒）以上。relying party の JWKS キャッシュ
   (`Cache-Control: max-age=60`) や時計ずれを考慮し、実運用では十分なマージン
   （例: 24時間）を取ることを推奨。

3. **旧鍵を revoke（grace 経過後）**

   ```
   POST /api/v1/admin/keys/{kid}/revoke     (Owner 権限)
   → { "ok": true, "kid": "key-..." }
   ```

   revoke した鍵は検証にも JWKS にも使われなくなる。

   現在の鍵一覧は `GET /api/v1/admin/keys` で確認できる。

> **注意**: grace を待たずに旧鍵を revoke すると、その鍵で署名された既発行 JWT が
> 即座に検証不能になる。必ず手順 2 を経ること。

---

## 2. 招待トークンのハッシュ化保存

### 変更点

従来 `invitations.code` に **平文** で保存していた招待コードを、**SHA-256 ハッシュ**
(`invitations.code_hash`) に変更した。

- 発行時のみ、生コードをメール／API レスポンス（`POST /api/v1/tenants/{id}/invitations`
  の `code`・`link`）で返す。**DB には生コードを保存しない。**
- 検証（招待リンクのアクセス）は `SecurityUtils.sha256Hex(code)` でハッシュ化して
  `code_hash` と比較する（`SqlStore.findInvitationByCode`）。
- ハッシュは小文字 hex 64 桁で、pgcrypto の `encode(digest(code,'sha256'),'hex')` と一致する。

これにより DB breach 時でも、保存されているハッシュから生コードを復元できず、
招待リンクの盗用ができない。

### マイグレーション方針（Flyway）

このリポジトリの流儀に従い Flyway で対応する。

- **public スキーマ**: `V27__invitation_code_hash.sql`
- **テナントスキーマ**: `V2__invitation_code_hash.sql`（schema-isolated テナント用）

両マイグレーションの内容:

1. `code_hash` カラムを追加。
2. **既存行をバックフィル**: `code_hash = encode(digest(code,'sha256'),'hex')`。
   これにより **発行済みの招待リンクはデプロイ後も有効** のまま（アプリ側の
   ハッシュ計算と一致するため）。
3. 万一バックフィルできない行（`code` が NULL 等）は `expires_at = now()` で
   **期限切れ扱い** にし、リンクを無効化する。
4. UNIQUE 制約を `code` から `code_hash` へ移す。
5. **平文 `code` カラムを DROP** し、DB から生コードを完全に除去する。

> 既存の発行済みリンクを失効させたい運用方針なら、手順2のバックフィルを行わず
> 全行 `expires_at = now()` とする選択肢もある（その場合、既存リンクは全て無効化される）。
> 本実装は「既存リンクを生かす」現実的な方針を採用している。
