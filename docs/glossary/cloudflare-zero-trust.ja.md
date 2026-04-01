# Cloudflare Zero Trust

[English version](cloudflare-zero-trust.md)

---

## これは何？

Cloudflare Zero Trust（旧Cloudflare Access）は、ユーザーとアプリケーションの間に位置し、アクセスを許可する前にすべてのリクエストを認証するクラウドベースのID認識プロキシサービスです。Cloudflareの広範なZero Trustプラットフォームの一部で、セキュアWebゲートウェイ、DNSフィルタリング、ブラウザ分離も含まれています。

建物の入口に配置されたクラウドホスティングの警備員のようなものです。鍵を持っている人なら誰でも開けられるドアの鍵の代わりに、警備員がすべての人のIDバッジをチェックし、アクセスリストに載っているか確認し、それからドアを開けます。警備員はあなたではなくCloudflareのために働いています -- Cloudflareに月額料金を支払い、Cloudflareがすべてを処理します。

「Zero Trust」の部分は：企業ネットワーク内から来たというだけでリクエストを信頼しない、ということです。すべてのユーザーの、すべてのデバイスからの、すべてのリクエストが認証・認可されなければなりません。これは、VPN内のすべてが信頼される従来の「城と堀」アプローチの正反対です。

---

## なぜ重要なのか？

Cloudflare Zero Trustがvolta-auth-proxyに関連するのは、類似の問題を解決するためです -- Webアプリケーションの前に認証を置く -- ただし根本的に異なるアーキテクチャで：

| 観点 | Cloudflare Zero Trust | volta-auth-proxy |
|------|----------------------|-----------------|
| 実行場所 | Cloudflareのエッジネットワーク（クラウド） | あなたのサーバー（セルフホスト） |
| データ主権 | トラフィックはCloudflareを通過 | トラフィックはあなたのインフラに留まる |
| 認証 | IdPに委任（Google、Oktaなど） | Googleと直接[OIDC](oidc.md) |
| 料金 | 無料（50ユーザー）〜$7+/ユーザー/月 | 無料（オープンソース） |
| カスタマイズ | ダッシュボード設定 | コードレベルの制御 |
| マルチテナント | アプリケーション単位のポリシー | テナント単位の分離 |

トラフィックをオンプレミスに保つ必要がある組織（医療、金融、政府、データ主権規制）にとって、Cloudflare Zero Trustは選択肢になりません -- すべてのトラフィックがCloudflareのサーバーを経由します。volta-auth-proxyは完全にあなたのインフラ上で動作します。

---

## どう動くのか？

### アーキテクチャ

```
  ユーザーのブラウザ
       │
       │  HTTPS
       ▼
  ┌──────────────────────────┐
  │  Cloudflare エッジネットワーク│
  │                           │
  │  1. リクエストを受信       │
  │  2. CFトークンをチェック   │
  │     （cookie/ヘッダー）    │
  │                           │
  │  トークンなし：            │
  │  3. IdPにリダイレクト      │──► Google / Okta / Azure AD
  │  4. ユーザーが認証         │◄── （OIDCコールバック）
  │  5. CFアクセストークン発行  │
  │                           │
  │  有効なトークンあり：       │
  │  6. アクセスポリシーチェック │
  │  7. オリジンに転送         │
  └──────────────────────────┘
       │
       │  Cloudflare Tunnel（またはDNS）
       ▼
  ┌──────────────┐
  │  あなたのサーバー│  （オリジン、直接公開されない）
  │  （アプリ）      │
  └──────────────┘
```

### 主要コンポーネント

| コンポーネント | 説明 |
|-------------|------|
| **Access** | 認証/認可レイヤー。リクエストを通す前にIDをチェック。 |
| **Tunnel** | サーバーからCloudflareへのセキュア接続。オリジンがインターネットに直接公開されない。 |
| **Gateway** | DNSとHTTPフィルタリング。悪意のあるサイトをブロックしブラウジングポリシーを適用。 |
| **WARP** | デバイスポスチャとセキュア接続のためのクライアント側エージェント。 |

### Cloudflare Tunnel

従来、Cloudflareの背後にWebアプリを置くにはパブリックIPの公開が必要でした。Cloudflare Tunnelは、サーバー上で小さなデーモン（`cloudflared`）を実行し、Cloudflareへのアウトバウンド専用接続を作成することでこれを解消します：

```
  インターネット ──► Cloudflare Edge ◄──── cloudflared ──── あなたのアプリ
                                       （アウトバウンドのみ）
```

サーバーにオープンなインバウンドポートはありません。攻撃者は直接到達できません。

### セルフホスト代替手段との比較

| 機能 | Cloudflare Zero Trust | volta + [Traefik](traefik.md) | [Keycloak](keycloak.md) + プロキシ |
|------|----------------------|-------------------------------|----------------------------------|
| デプロイ | クラウド（Cloudflareが管理） | セルフホスト（自分で管理） | セルフホスト（自分で管理） |
| DDoS保護 | 組み込み | 別途ソリューションが必要 | 別途ソリューションが必要 |
| データパス | Cloudflare経由 | 自分のインフラ経由 | 自分のインフラ経由 |
| カスタマイズ | ダッシュボードのみ | 完全なコード制御 | 管理コンソール + テーマ |
| 料金 | ユーザー/月単位 | 無料（オープンソース） | 無料（オープンソース） |
| オフライン動作 | 不可（Cloudflareに依存） | 可能 | 可能 |

---

## volta-auth-proxy ではどう使われている？

volta-auth-proxyはCloudflare Zero Trustを**使用しません**。voltaは、セルフホストのID認識プロキシが必要な組織がCloudflare Zero Trustを**置き換える**ために設計されています。

### なぜCloudflare Zero Trustを置き換えるのか？

1. **データ主権**：Cloudflareでは、認証トークン、リクエストボディ、ヘッダーを含むすべてのトラフィックがCloudflareのインフラを通過します。医療（HIPAA）、金融、または厳格なGDPR要件を持つEU組織にとって、これは許容できない場合があります。

2. **ベンダー依存**：Cloudflareに障害が発生すると、認証が機能しなくなります。voltaはあなたのインフラ上で動作するため、可用性を制御できます。

3. **スケール時のコスト**：Cloudflare Zero Trustは有料ティアで$7+/ユーザー/月。10,000ユーザーのSaaSでは、認証プロキシだけで年間$70,000です。voltaは無料です。

4. **マルチテナントSaaS**：Cloudflare Accessポリシーはアプリケーション単位で、テナント単位ではありません。voltaはテナントごとに異なる設定、ドメイン、アクセスルールを持つマルチテナントSaaS向けに構築されました。

5. **カスタマイズ**：Cloudflareのポリシーはダッシュボードで事前定義されたオプションから設定されます。voltaはすべての認証判断に対してコードレベルの制御を提供します。

### CloudflareからvoltaへのExperience移行パス

現在Cloudflare Accessを使用していてセルフホストに移行したいチーム向け：

1. volta-auth-proxyを[Traefik](traefik.md) ForwardAuthでセットアップ
2. voltaに同じ[OIDC](oidc.md)プロバイダー（Googleなど）を設定
3. DNSをCloudflareから自身のインフラに移行（またはDNS/CDNのみCloudflareを継続）
4. Cloudflare Accessポリシーを削除
5. トンネリングが必要ならCloudflare Tunnel代替（WireGuard、Tailscale）を使用

---

## よくある間違いと攻撃

### 間違い1：Cloudflareは何も見ないと思い込む

すべてのHTTPトラフィックはプレーンテキストでCloudflareを通過します（TLSを終端するため）。つまりCloudflareは技術的にリクエストボディ、cookie、トークンを見ることができます。機密データの場合、これは重大な信頼の決断です。

### 間違い2：オリジンを保護しない

オリジンサーバーが直接到達可能な場合（パブリックIPを持つ）、攻撃者はCloudflareを完全にバイパスして直接接続できます。Cloudflare Tunnelまたはファイアウォールルールを使用して、オリジンがCloudflareからの接続のみを受け入れるようにしてください。

### 間違い3：ポリシーが広すぎる

「すべての@company.comメールを許可」のようなポリシーは、請負業者や元従業員がGoogle Workspaceアクセスを保持している場合には広すぎる場合があります。グループベースのポリシーを使用し、メンバーシップを定期的に監査してください。

### 間違い4：デバイスポスチャを無視する

Cloudflare Zero Trustはデバイスポスチャ（OSバージョン、ディスク暗号化、スクリーンロック）をチェックできます。これを使わないと、有効な認証情報を持つ侵害された個人デバイスがフルアクセスを得ます。

### 攻撃：オリジンでのJWT混乱

Cloudflare Accessは独自のJWT（CF-Access-Jwt-Assertionヘッダー）を発行します。オリジンがこのJWTを適切に検証しない場合（発行者、オーディエンス、署名のチェック）、JWTフォーマットを発見した攻撃者が偽造できます。オリジンでは必ずCF JWTを検証してください。

---

## さらに学ぶ

- [Cloudflare Zero Trustドキュメント](https://developers.cloudflare.com/cloudflare-one/) -- 公式ドキュメント。
- [Cloudflare Access](https://developers.cloudflare.com/cloudflare-one/policies/access/) -- アクセスポリシーのドキュメント。
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) -- トンネルセットアップガイド。
- [forwardauth.md](forwardauth.md) -- Cloudflare Accessを置き換えるセルフホストパターン。
- [reverse-proxy.md](reverse-proxy.md) -- リバースプロキシの仕組み。
- [oidc.md](oidc.md) -- 認証に使われるプロトコル。
