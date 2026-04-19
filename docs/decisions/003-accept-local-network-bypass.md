# Decision 003: ForwardAuth にローカルネットワークバイパスを導入する

**Status:** Accepted
**Date:** 2026-04-12
**Supersedes:** [002-reject-trusted-network-bypass](002-reject-trusted-network-bypass.md)

## 何を決めたか

クライアント IP が設定済み CIDR に該当し、かつセッションが存在しない場合に限り、
ForwardAuth (`/auth/verify`) を 200 で通過させるローカルネットワークバイパスを導入した。

- 環境変数 `LOCAL_BYPASS_CIDRS` で CIDR リストを指定（カンマ区切り）
- デフォルト: `192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10,127.0.0.1/32`（RFC1918 + Tailscale CGNAT + loopback）
- 空文字を設定すると完全に無効化できる
- バイパス時は `X-Volta-Auth-Source: local-bypass` ヘッダを付与

## 002 からなぜ方針転換したか

002 は「VPN内だからvoltaの認証を飛ばす」という汎用バイパスを却下した。
その判断は正しかったが、002 が想定しなかったユースケースが出てきた：

**自宅 LAN / Tailscale メッシュからのセルフホスト利用。**

この文脈では「VPN認証済みだから二重認証をスキップ」ではなく、
「そもそもパブリックに公開していない LAN サービスに、認証なしでアクセスしたい」が動機。
Grafana や HomeLab ダッシュボードのように、LAN 内に閉じたサービスに毎回 MFA を求めるのは過剰。

## 002 の懸念にどう対処したか

| 002 の懸念 | 003 での対処 |
|-----------|------------|
| バイパスすると `X-Volta-Tenant-Id` 等が空になり downstream が壊れる | セッションが存在する場合は通常認証を実行する。バイパスはセッションなし時のみ発火（`4006ee7`） |
| `X-Forwarded-For` 偽装リスク | Traefik の `trustedIPs` + `proxyProtocol` で送信元を保証。volta 側では `HttpSupport.clientIp()` で取得 |
| bypass 経由のアクセスが監査ログに残らない | `X-Volta-Auth-Source: local-bypass` ヘッダにより downstream で識別・ログ可能 |
| `192.168.x.x` を信頼するとカフェ WiFi も対象 | デフォルト CIDR は自宅 LAN / Tailscale 前提。本番では `LOCAL_BYPASS_CIDRS=""` で無効化を推奨 |

## セッションありの場合の挙動（4006ee7 での修正）

初回実装（`5f23f88`）ではバイパスがセッション認証より先に実行されたため、
LAN 内のログイン済みユーザーもユーザーヘッダなしで 200 が返り、MFA ループが発生した。

`4006ee7` でバイパスの実行位置をセッション認証の後に移動：

1. セッションあり → 通常認証（MFA チェック、ユーザーヘッダ付与）
2. セッションなし + ローカル IP → 200 anonymous（`X-Volta-Auth-Source: local-bypass`）
3. セッションなし + 外部 IP → `/login` へリダイレクト

## 実装

- `LocalNetworkBypass.java` — CIDR パース・マッチングロジック
- `AuthFlowHandler.java` — `/auth/verify` 内でセッション認証後にバイパス判定
- `LocalNetworkBypassTest.java` — CIDR マッチングのユニットテスト
