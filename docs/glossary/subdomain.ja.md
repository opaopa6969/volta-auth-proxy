# サブドメイン

[English version](subdomain.md)

---

## 一言で言うと？

サブドメインとは、[ドメイン](domain.ja.md)の前に追加されるセクション（`app.example.com` など）で、1つのメインアドレスの下にさまざまなサービスやテナントを整理するために使います。

---

## 同じビルの中のアパート

[ドメイン](domain.ja.md)をビル、サブドメインをその中のアパートだと考えてください：

| ビル（ドメイン） | アパート（サブドメイン） | 何があるか |
|---|---|---|
| `example.com` | `www.example.com` | マーケティングサイト |
| `example.com` | `app.example.com` | ウェブアプリ |
| `example.com` | `auth.example.com` | 認証サービス |
| `example.com` | `acme.app.example.com` | テナント「ACME」のインスタンス |

- ビルのオーナー（ドメイン所有者）がアパートの割り当てを決める
- 各アパートに独自の鍵（[SSL/TLS](ssl-tls.ja.md) 証明書）を持てる
- 1つのアパート宛の郵便（[Cookie](cookie.ja.md)）は他のアパートには届かない
- アパートの中にアパートも可能：`acme.app.example.com` は `app.example.com` のサブドメイン

---

## なぜ必要なの？

サブドメインがなければ：

- すべてのサービスに別々のドメインが必要（高コスト、管理が大変）
- マルチテナントアプリではテナント識別に別の方法が必要（クエリパラメータやパスなど -- どれも不格好）
- 同一ドメイン上のサービス間で [Cookie](cookie.ja.md) を分離できない
- 1枚の[ワイルドカード証明書](wildcard-certificate.ja.md)で全部をカバーできない

サブドメインは、1つのドメインの下に無制限の整理構造を提供し、ブラウザのセキュリティ境界も備えています。

---

## volta-auth-proxy でのサブドメイン

サブドメインは volta のマルチテナント設計の中核です：

```
  example.com                    （ベースドメイン）
  ├── auth.example.com           （volta-auth-proxy）
  ├── app.example.com            （SaaS アプリ）
  │   ├── acme.app.example.com   （テナント: ACME Corp）
  │   ├── globex.app.example.com （テナント: Globex Inc）
  │   └── initech.app.example.com（テナント: Initech）
  └── api.example.com            （API サーバー）
```

**サブドメインによるテナント解決：**

`acme.app.example.com` にリクエストが来ると、volta は最初のセグメント（`acme`）を抽出し、テナントテーブルの `slug` として検索します。これにより、どのテナントのデータと [RBAC](authentication-vs-authorization.ja.md) ルールが適用されるか決まります。

**Cookie の分離：**

セッション [Cookie](cookie.ja.md) は認証サブドメイン（`auth.example.com`）に設定され、`.example.com` には設定されません。つまり Cookie は `app.example.com` や `evil.example.com` には送信されません。[リバースプロキシ](reverse-proxy.ja.md) / [ForwardAuth](forwardauth.ja.md) パターンが、サブドメイン間で Cookie を共有せずに認証を処理します。

---

## 具体的な例

volta デプロイでテナントサブドメインを設定する流れ：

1. `myproduct.com` を所有
2. DNS: `*.app.myproduct.com` を[サーバー](server.ja.md)に向ける（ワイルドカード DNS レコード）
3. [SSL/TLS](ssl-tls.ja.md): `*.app.myproduct.com` の[ワイルドカード証明書](wildcard-certificate.ja.md)を取得
4. volta 設定: `VOLTA_BASE_DOMAIN=myproduct.com`
5. 新テナント「Acme」がサインアップし、slug `acme` を選択
6. volta がテナントテーブルに `slug: "acme"` を保存
7. ユーザーが `https://acme.app.myproduct.com` にアクセス
8. [リバースプロキシ](reverse-proxy.ja.md)がリクエストを受け、[ForwardAuth](forwardauth.ja.md) で volta に問い合わせ
9. volta が `Host` [ヘッダー](header.ja.md)を読み取る：`acme.app.myproduct.com`
10. volta が `acme` を抽出し、テナントを見つけ、[セッション](session.ja.md)を確認し、`X-Volta-Tenant-Slug: acme` を注入

---

## さらに学ぶために

- [ドメイン](domain.ja.md) -- サブドメインが拡張する親アドレス
- [ワイルドカード証明書](wildcard-certificate.ja.md) -- 全サブドメインをカバーする1枚の SSL 証明書
- [ForwardAuth](forwardauth.ja.md) -- サブドメイン間で volta がリクエストを認証する方法
- [Cookie](cookie.ja.md) -- Cookie が特定のサブドメインにスコープされる仕組み
- [ルーティング](routing.ja.md) -- サブドメインに基づいてリクエストが適切なサービスに届く仕組み
