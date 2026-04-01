# ドメイン

[English version](domain.md)

---

## 一言で言うと？

ドメインとは、ウェブサイトの人間が読めるアドレス（`example.com` など）のことで、`93.184.216.34` のような数字を覚えなくて済むようにしたものです。

---

## インターネット上の住所

インターネットが巨大な都市だと想像してください。すべての建物（サーバー）には番地（`93.184.216.34` のような IP アドレス）がありますが、誰も数字は覚えられません。だから建物に名前をつけます：

| 現実世界 | インターネット |
|---|---|
| 「ヒルトンホテル」 | `example.com` |
| 住所: 東京都千代田区1-1 | IP アドレス: `93.184.216.34` |
| タクシーに名前を伝える | [ブラウザ](browser.ja.md)がドメインを IP に変換 |
| 電話帳が名前と住所を紐づける | DNS がドメインと IP を紐づける |

- `google.com` はドメイン
- `volta.example.com` もドメイン（正確には[サブドメイン](subdomain.ja.md)）
- `93.184.216.34` はドメインではない -- IP アドレスです

---

## なぜ必要なの？

ドメインがなければ：

- すべてのサイトの IP アドレスを暗記する必要がある（`google.com` の代わりに `142.250.80.46`）
- サーバーの IP が変わったら、全員のブックマークが壊れる
- 同じサーバーで複数のサイトをホストできない
- [SSL/TLS](ssl-tls.ja.md) 証明書がサイトを確実に識別できない
- [Cookie](cookie.ja.md) の適用範囲を適切に設定できない

ドメインはウェブのアイデンティティの基盤です。[Cookie](cookie.ja.md) の送信先、[CORS](cors.ja.md) ルールの適用、[SSL/TLS](ssl-tls.ja.md) 証明書の検証を決定します。

---

## volta-auth-proxy でのドメイン

volta はマルチテナントアーキテクチャでドメインを多用します：

| 概念 | 例 | 用途 |
|---|---|---|
| ベースドメイン | `example.com` | 組織のルートドメイン |
| 認証ドメイン | `volta.example.com` | volta-auth-proxy の所在地 |
| アプリ[サブドメイン](subdomain.ja.md) | `app.acme.example.com` | テナント固有のアプリ URL |
| [ワイルドカード証明書](wildcard-certificate.ja.md) | `*.example.com` | 1枚の証明書で全サブドメインをカバー |

ドメインに関する主な動作：

- **テナント解決** -- volta はドメイン/サブドメインを見て、リクエストがどのテナントに属するか判断
- **Cookie スコープ** -- `__volta_session` [Cookie](cookie.ja.md) は volta ドメインにスコープされ、漏洩を防止
- **[リダイレクト URI](redirect-uri.ja.md) 検証** -- volta は [OAuth2](oauth2.ja.md) のリダイレクト URI が許可ドメインと一致するか検証し、[オープンリダイレクト](open-redirect.ja.md)攻撃を防止
- **[CORS](cors.ja.md) オリジン** -- volta は既知のドメインからの[クロスオリジン](cross-origin.ja.md)リクエストのみ許可

---

## 具体的な例

典型的な volta デプロイでのドメインの仕組み：

1. あなたの会社が `acme.com` を所有
2. volta を `auth.acme.com` にセットアップ
3. SaaS アプリは `app.acme.com` に配置
4. テナント「Globex」は `globex.app.acme.com` でアプリにアクセス
5. Globex のユーザーがその URL にアクセスすると：
   - [ブラウザ](browser.ja.md)が DNS で `globex.app.acme.com` を IP に解決
   - [リバースプロキシ](reverse-proxy.ja.md)がリクエストを受信
   - volta が[サブドメイン](subdomain.ja.md)（`globex`）を読み取ってテナントを識別
   - [ワイルドカード証明書](wildcard-certificate.ja.md)（`*.app.acme.com`）が接続を暗号化

---

## さらに学ぶために

- [サブドメイン](subdomain.ja.md) -- ドメインの下のサブアドレス
- [SSL/TLS](ssl-tls.ja.md) -- ドメインが暗号化接続を得る仕組み
- [ワイルドカード証明書](wildcard-certificate.ja.md) -- 全サブドメイン用の1枚の証明書
- [Cookie](cookie.ja.md) -- Cookie がドメインにスコープされる仕組み
- [クロスオリジン](cross-origin.ja.md) -- 異なるドメイン間のセキュリティルール
- [リダイレクト URI](redirect-uri.ja.md) -- OAuth でドメイン検証が重要な理由
