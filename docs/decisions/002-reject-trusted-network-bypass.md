# Decision 002: TRUSTED_NETWORKS による認証バイパスを実装しない

**Status:** Rejected
**Date:** 2026-04-02
**DGE Session:** [dge/sessions/2026-04-02-trusted-network-bypass.md](../../dge/sessions/2026-04-02-trusted-network-bypass.md)

## 何を検討したか

`TRUSTED_NETWORKS=10.8.0.0/24` のようなCIDR設定を追加し、
VPN内やローカルネットワークからのリクエストに対して volta の認証をバイパスできるようにすること。

ユースケースとして想定したのは2つ：
1. **VPN内** — すでにVPN認証済みなのでvoltaの認証は二重になる
2. **開発用途** — ローカルネットワークから手軽にアクセスしたい

## なぜやらないか

**VPN認証とアプリ認証は別物。**

VPNはネットワークへのアクセスを許可するだけで、
「このユーザーがどのテナントに属し、どのロールを持つか」は何も言っていない。
バイパスすると `X-Volta-Tenant-Id` や `X-Volta-Roles` が空になり、downstream appが壊れる。
voltaが本来解いている問題がそもそも解けない。

**実装リスクが高い。**

- `X-Forwarded-For` はTraefikの `trustedIPs` 設定がないと偽装できる
- `192.168.x.x` を信頼すると、カフェのWifiや来客PCも対象になる
- bypass経由のアクセスは監査ログに残らない

**VPNユースケースの正しい解はvolta側にない。**

Traefikのルーティング設定で、VPN経由のトラフィックを
voltaをスキップする別ルートに乗せれば済む話。voltaは何も変更しなくてよい。

**開発用途は既存の仕組みで十分。**

`DEV_MODE=true` + `isLocalRequest()`（127.0.0.1限定）が存在する。
`192.168.x.x` まで広げてもリターンは薄く、
開発者はVPNかSSHポートフォワードで対応できる。

## 正しい解決場所

| ユースケース | 解決場所 |
|-------------|---------|
| VPN内からの認証スキップ | Traefikのルーティング分岐（voltaをbypassするルートを定義） |
| ローカル開発での手軽なアクセス | `DEV_MODE=true` + localhost限定（実装済み） |

## 教訓

「めんどくさいことはvoltaに」という思想は魅力的だが、
voltaに吸収させていい「めんどくさい」と、させてはいけない「めんどくさい」がある。
ネットワーク層とアプリ層の認証を混同させることは、後者。
