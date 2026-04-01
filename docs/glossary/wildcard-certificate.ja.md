# ワイルドカード証明書

[English version](wildcard-certificate.md)

---

## 一言で言うと？

ワイルドカード証明書とは、1枚の [SSL/TLS](ssl-tls.ja.md) 証明書で[ドメイン](domain.ja.md)とその1階層下の全[サブドメイン](subdomain.ja.md)をカバーするものです。`*.example.com` なら `app.example.com` や `auth.example.com` など `.example.com` で終わるものすべてをカバーします。

---

## ビル全室のマスターキー

[サブドメイン](subdomain.ja.md)の例えで[ドメイン](domain.ja.md)がビル、サブドメインがアパートでしたね？ ワイルドカード証明書はマスターキーです：

| 個別の証明書 | ワイルドカード証明書 |
|---|---|
| 各部屋に専用の鍵 | 全部屋を開けるマスターキー1本 |
| 新しい部屋を追加？新しい鍵を作る | 新しい部屋を追加？マスターキーがすでに使える |
| 50部屋に50本の鍵を管理 | 全部屋に1本の鍵を管理 |
| 各鍵が別々に期限切れ | 追跡する期限は1つ |
| より安全（1本盗まれても1部屋だけ） | リスクあり（1本盗まれると全部屋） |

`*.example.com` の `*` は「ここに任意の1単語」という意味：

| マッチする | マッチしない |
|---|---|
| `app.example.com` | `example.com`（サブドメインなし） |
| `auth.example.com` | `deep.app.example.com`（2階層） |
| `anything.example.com` | `other-domain.com`（別ドメイン） |

---

## なぜ必要なの？

ワイルドカード証明書がなければ：

- すべての[サブドメイン](subdomain.ja.md)に個別の証明書が必要 -- マルチテナントならテナントごとに1枚
- 新しいテナントを追加するたびに証明書の取得と設定が必要
- 大規模な証明書更新が運用上の悪夢に
- 一部の認証局は証明書ごとに課金、コスト増

ワイルドカード証明書があれば、新しいテナントサブドメインの追加に証明書の変更がゼロ。

---

## volta-auth-proxy でのワイルドカード証明書

volta のマルチテナントアーキテクチャはワイルドカード証明書に大きく依存します：

```
  証明書: *.app.example.com
  ┌────────────────────────────────────────┐
  │  カバー範囲:                             │
  │  ✓ acme.app.example.com               │
  │  ✓ globex.app.example.com             │
  │  ✓ initech.app.example.com            │
  │  ✓ any-new-tenant.app.example.com     │
  │                                        │
  │  カバーしない:                           │
  │  ✗ app.example.com（ワイルドカード部なし）│
  │  ✗ auth.example.com（別の枝）           │
  │  ✗ deep.sub.app.example.com           │
  └────────────────────────────────────────┘
```

実際の volta デプロイでは通常以下が必要：

| 証明書 | カバー範囲 | 用途 |
|---|---|---|
| `*.example.com` | `auth.example.com`, `app.example.com` | トップレベルサービス |
| `*.app.example.com` | `acme.app.example.com`, `globex.app.example.com` 等 | テナントサブドメイン |

**証明書の配置場所：**

ワイルドカード証明書は[リバースプロキシ](reverse-proxy.ja.md)（Traefik/Nginx）に設定します。volta 自体には設定しません。volta は内部 [Docker ネットワーク](network-isolation.ja.md)上で [HTTP](http.ja.md) トラフィックを処理し、TLS は不要です。

**Let's Encrypt での自動更新：**

Let's Encrypt のワイルドカード証明書には DNS-01 チャレンジ（DNS レコードでドメインの所有を証明）が必要です。通常の証明書で使える HTTP-01 チャレンジとは異なります。Traefik がこれを自動処理できます。

---

## 具体的な例

volta デプロイ用のワイルドカード証明書を設定する：

1. `myproduct.com` を所有し、DNS に Cloudflare を使用
2. Traefik を Let's Encrypt + DNS-01 チャレンジで設定：
   ```yaml
   # traefik.yml（簡略化）
   certificatesResolvers:
     letsencrypt:
       acme:
         email: admin@myproduct.com
         storage: /data/acme.json
         dnsChallenge:
           provider: cloudflare
   ```
3. Traefik が自動的に Let's Encrypt から `*.app.myproduct.com` を要求
4. Let's Encrypt が「`app.myproduct.com` の所有を証明して」と要求
5. Traefik が Cloudflare API 経由で DNS TXT レコードを作成：`_acme-challenge.app.myproduct.com`
6. Let's Encrypt がレコードを確認し、ワイルドカード証明書を発行
7. これで任意のテナントサブドメインが即座に動作：
   - `acme.app.myproduct.com` -- カバー済み
   - `globex.app.myproduct.com` -- カバー済み
   - `brand-new-tenant.app.myproduct.com` -- カバー済み（追加作業不要！）
8. Traefik が期限切れ前に証明書を自動更新（約60日ごと）

ワイルドカードがなければ、ステップ7で新しいテナントごとに新しい証明書を要求する必要があり、テナントオンボーディングに遅延と複雑さが加わります。

---

## さらに学ぶために

- [SSL/TLS](ssl-tls.ja.md) -- 証明書が有効にする暗号化技術
- [サブドメイン](subdomain.ja.md) -- ワイルドカード証明書がカバーするもの
- [ドメイン](domain.ja.md) -- 証明書が紐づくベースアドレス
- [リバースプロキシ](reverse-proxy.ja.md) -- 証明書が設定される場所
- [ネットワーク分離](network-isolation.ja.md) -- 内部サービスに証明書が不要な理由
