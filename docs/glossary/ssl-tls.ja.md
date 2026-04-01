# SSL/TLS

[English version](ssl-tls.md)

---

## 一言で言うと？

SSL/TLS は、[ブラウザ](browser.ja.md)と[サーバー](server.ja.md)の間を流れるデータを保護する暗号化技術です。アドレスバーに鍵アイコンが表示される理由がこれです。

---

## 封をした封筒

SSL/TLS なしでインターネット上にデータを送るのは、ハガキを送るようなもの -- 途中で誰でも読めます。SSL/TLS はそのハガキを封をした封筒に変えます：

| ハガキ（HTTP） | 封をした封筒（HTTPS） |
|---|---|
| 配達途中で誰でも読める | 送信者と受信者だけが読める |
| 誰でもメッセージを改ざんできる | 改ざんが検出される |
| 宛先が本物か分からない | 封筒が受取人の身元を証明 |
| 無料で簡単 | 少し手間がかかる（でも Let's Encrypt なら無料） |

**SSL と TLS -- 何が違う？**

- **SSL**（Secure Sockets Layer） -- 元祖、今は非推奨。SSL 3.0 が最終バージョン（1996年）。
- **TLS**（Transport Layer Security） -- 現代の後継。TLS 1.2 と 1.3 が現行。
- 習慣で皆「SSL」と言いますが、実際に使われているのは TLS です。「写メ」と言いながらスマホで撮るようなもの -- 古い名前が定着したのです。

---

## なぜ必要なの？

SSL/TLS がなければ：

- **パスワードが平文で送信** -- 同じ[ネットワーク](network.ja.md)上の誰でも（カフェの Wi-Fi など）[認証情報](credentials.ja.md)をキャプチャできる
- **[セッション](session.ja.md) [Cookie](cookie.ja.md) が盗まれる** -- ネットワーク上の攻撃者がセッション Cookie を奪い、なりすましできる
- **データ改ざん** -- ISP や中間者がウェブページを改変できる（広告注入、内容変更）
- **身元確認なし** -- `mybank.com` が本当に銀行なのか攻撃者のサーバーなのか判別できない
- **[Cookie](cookie.ja.md) の `Secure` フラグが無意味** -- `Secure` 属性は HTTPS が利用可能であることに依存

---

## volta-auth-proxy での SSL/TLS

volta は本番環境ですべての外部通信に HTTPS を要求します：

```
  ブラウザ ──HTTPS──> リバースプロキシ ──HTTP──> volta ──HTTP──> PostgreSQL
  ▲                  ▲                        ▲
  暗号化              暗号化なしだが             プライベートネットワーク、
  （パブリック         分離ネットワーク           インターネットアクセスなし
   インターネット）
```

SSL/TLS が使われる場所：

| 接続 | 暗号化? | 理由 |
|---|---|---|
| ブラウザ → リバースプロキシ | **はい（HTTPS）** | パブリックインターネットを通る |
| リバースプロキシ → volta | いいえ（HTTP） | プライベート [Docker ネットワーク](network-isolation.ja.md)、露出なし |
| volta → Google（OIDC） | **はい（HTTPS）** | パブリックインターネットを通る |
| volta → PostgreSQL | いいえ | プライベートネットワーク、同じ Docker ホスト |

volta の SSL/TLS 関連セキュリティ対策：

- **`Secure` Cookie フラグ** -- `__volta_session` は HTTPS 接続でのみ送信
- **[HSTS](header.ja.md)** -- ブラウザにこの[ドメイン](domain.ja.md)では常に HTTPS を使うよう指示
- **[ワイルドカード証明書](wildcard-certificate.ja.md)** -- 1枚の証明書で `*.example.com` の全テナント[サブドメイン](subdomain.ja.md)をカバー
- **証明書はリバースプロキシで管理** -- volta 自体は証明書を扱わない；[リバースプロキシ](reverse-proxy.ja.md)（Traefik/Nginx）が TLS を終端

---

## 具体的な例

volta で保護されたサイトにアクセスする際の TLS ハンドシェイクの流れ：

1. [ブラウザ](browser.ja.md)が `https://app.acme.example.com` の[ポート](port.ja.md) 443 に接続
2. **Client Hello** -- ブラウザが「TLS 1.3 と TLS 1.2 に対応、サポートする暗号スイートはこれ」と送信
3. **Server Hello** -- [リバースプロキシ](reverse-proxy.ja.md)が「TLS 1.3 でこの暗号スイートを使おう」と返答
4. **Certificate** -- サーバーが `*.example.com` の[ワイルドカード証明書](wildcard-certificate.ja.md)を送信
5. **検証** -- ブラウザが確認：証明書は有効か？信頼された認証局が発行したか？`*.example.com` は `app.acme.example.com` に一致するか？期限切れでないか？
6. **鍵交換** -- ブラウザとサーバーが非対称暗号で共有秘密を合意
7. **暗号化接続確立** -- 以降のデータはすべて共有秘密で暗号化
8. ブラウザに鍵アイコンが表示
9. これで[セッション Cookie](cookie.ja.md)、[ログイン](login.ja.md)フロー、すべてのデータが盗聴者から保護される

ハンドシェイク全体は約50〜100ミリ秒。その後はすべてのバイトが暗号化されます。

証明書が無効（自己署名、期限切れ、[ドメイン](domain.ja.md)不一致）なら、ブラウザが警告ページを表示します。これは中間者攻撃からあなたを守るブラウザの保護機能です。

---

## さらに学ぶために

- [ワイルドカード証明書](wildcard-certificate.ja.md) -- 全サブドメイン用の1枚の証明書
- [ドメイン](domain.ja.md) -- SSL/TLS 証明書が紐づく対象
- [Cookie](cookie.ja.md) -- HTTPS を要求する `Secure` フラグ
- [プロトコル](protocol.ja.md) -- SSL/TLS は多くのインターネットプロトコルの1つ
- [ネットワーク](network.ja.md) -- 暗号化がデータを保護する場所
- [認証情報](credentials.ja.md) -- SSL/TLS が傍受から守るもの
