# ルーティング

[English version](routing.md)

---

## 一言で言うと？

ルーティングとは、受信リクエストの URL を見て、どの[サーバー](server.ja.md)やサービスがそれを処理すべきか決めるプロセスです。

---

## 空港の到着ロビー

空港の到着ロビーに複数の出口があると想像してください：

| 出口の案内板 | 行き先 | ウェブでの同等物 |
|---|---|---|
| 「タクシー」 | タクシー乗り場 | `api.example.com` → API サーバー |
| 「ホテルシャトル」 | シャトル乗り場 | `app.example.com` → ウェブアプリ |
| 「レンタカー」 | レンタカー受付 | `auth.example.com` → volta |
| 「国内線乗り継ぎ」 | 国内線ターミナル | `admin.example.com` → 管理パネル |

**ルーター**は案内板を読んであなたを誘導するシステムです。ウェブでは、[リバースプロキシ](reverse-proxy.ja.md)が各リクエストの[ドメイン](domain.ja.md)/[サブドメイン](subdomain.ja.md)/パスを読み取り、適切なバックエンドサービスに送ります。

---

## なぜ必要なの？

ルーティングがなければ：

- すべてのサービスに独自のパブリック IP と[ポート](port.ja.md)が必要
- ユーザーが各サービスのポートを覚える必要がある（`example.com:8080` がアプリ、`example.com:8081` が認証）
- 1つの[ドメイン](domain.ja.md)の背後で複数のサービスを動かせない
- [ForwardAuth](forwardauth.ja.md) が動作しない -- プロキシがどのリクエストに認証が必要か分からない
- ロードバランシング（トラフィックの複数インスタンスへの分散）が不可能

ルーティングがあるから、`app.example.com` のようなきれいな URL の背後で何十ものサービスを動かせます。

---

## volta-auth-proxy でのルーティング

volta のデプロイには複数のルーティング判断が関わります：

```
  リクエスト: https://app.acme.example.com/dashboard
                    │
  ┌─────────────────▼──────────────────┐
  │  リバースプロキシ（Traefik/Nginx）    │
  │                                    │
  │  ルーティングルール:                  │
  │  ┌──────────────────────────────┐  │
  │  │ auth.example.com → volta     │  │
  │  │ *.app.example.com → app      │  │
  │  │ api.example.com → api-server │  │
  │  └──────────────────────────────┘  │
  │                                    │
  │  マッチ: *.app.example.com          │
  │  ただしまず → ForwardAuth で volta   │
  └─────────────────┬──────────────────┘
                    │
        ┌───────────▼───────────┐
        │ volta（ForwardAuth）    │
        │ セッション/Cookie 確認  │
        │ X-Volta-* ヘッダー注入  │
        └───────────┬───────────┘
                    │
        ┌───────────▼───────────┐
        │ アプリ（:3000）         │
        │ リクエスト +            │
        │ 認証ヘッダーを受信       │
        └───────────────────────┘
```

volta デプロイでのルーティングの種類：

| ルーティング種別 | 例 | 実行者 |
|---|---|---|
| **ホストベース** | `auth.example.com` vs `app.example.com` | リバースプロキシ |
| **サブドメインベース** | `acme.app.example.com` → テナント「acme」 | volta（テナント解決） |
| **パスベース** | `/auth/login` vs `/api/v1/users/me` | volta の Javalin ルート |
| **メソッドベース** | `GET /auth/verify` vs `POST /auth/logout` | volta の Javalin ルート |

volta の内部ルート：

| パス | メソッド | 目的 |
|---|---|---|
| `/auth/login` | GET | [ログイン](login.ja.md)フロー開始 |
| `/auth/callback` | GET | Google からの [OIDC](oidc.ja.md) コールバック |
| `/auth/logout` | POST | [ログアウト](logout.ja.md) |
| `/auth/verify` | GET | [ForwardAuth](forwardauth.ja.md) 検証 |
| `/auth/refresh` | POST | [セッション](session.ja.md)から [JWT](jwt.ja.md) をリフレッシュ |
| `/api/v1/users/me` | GET | 現在のユーザー情報取得 |
| `/.well-known/jwks.json` | GET | JWT 検証用公開鍵 |

---

## 具体的な例

volta デプロイで Traefik のルーティングを設定する：

```yaml
# Traefik 動的設定（簡略化）
http:
  routers:
    volta-auth:
      rule: "Host(`auth.example.com`)"
      service: volta
      tls:
        certResolver: letsencrypt

    app:
      rule: "HostRegexp(`{subdomain:[a-z]+}.app.example.com`)"
      service: app
      middlewares:
        - volta-forwardauth    # ← アプリに転送する前に認証チェック
      tls:
        certResolver: letsencrypt

  middlewares:
    volta-forwardauth:
      forwardAuth:
        address: "http://volta:8080/auth/verify"
        authResponseHeaders:
          - "X-Volta-User-Id"
          - "X-Volta-Tenant-Id"
          - "X-Volta-Roles"
          - "X-Volta-JWT"
```

ステップバイステップの流れ：

1. `https://acme.app.example.com/dashboard` へのリクエストが到着
2. Traefik が `*.app.example.com` の `HostRegexp` ルールにマッチ
3. アプリに転送する前に、Traefik が `volta-forwardauth` ミドルウェアを実行
4. Traefik がリクエストを `http://volta:8080/auth/verify` に送信
5. volta が[セッション](session.ja.md) [Cookie](cookie.ja.md) を確認し、[サブドメイン](subdomain.ja.md)からテナントを解決
6. volta が 200 + [X-Volta-* ヘッダー](header.ja.md)で応答
7. Traefik がそれらの[ヘッダー](header.ja.md)をコピーし、リクエストを `http://app:3000` に転送
8. アプリがヘッダーを読み取り、ダッシュボードを提供

---

## さらに学ぶために

- [リバースプロキシ](reverse-proxy.ja.md) -- ルーティングを実行するコンポーネント
- [ForwardAuth](forwardauth.ja.md) -- ルーティングパイプラインの認証ミドルウェア
- [サブドメイン](subdomain.ja.md) -- リクエストがどのテナントに属するかルーティングが決定する方法
- [ドメイン](domain.ja.md) -- ルーティング判断の基となるアドレス
- [ヘッダー](header.ja.md) -- ルーティング過程で volta が注入するデータ
- [ポート](port.ja.md) -- ルーティングがマッピングする内部サービスアドレス
