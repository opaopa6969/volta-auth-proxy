# DGE Session: Trusted Network Bypass 設計検討

**Date:** 2026-04-02
**Structure:** ⚔ 兵棋演習
**Theme:** ローカルネットワーク・VPN内からのリクエストに対する認証バイパス設計
**Result:** Rejected — decisions/002 参照

## 背景

- VPN内はすでに認証済みなので voltaの認証は二重になる
- 開発用途でもローカルネットワークから楽にアクセスしたい
- 候補実装: `TRUSTED_NETWORKS=10.8.0.0/24` のようなCIDR設定

## キャラ構成

- 😈 Red Team（攻撃視点）
- 🏥 ハウス（隠れた問題）
- 👤 今泉（前提を問う）
- ☕ ヤン（削る力）
- 🎩 千石（品質・責任）

## Gap一覧

| # | Gap | Severity |
|---|-----|----------|
| G1 | X-Forwarded-For spoofing対策がない。Traefik `trustedIPs` とセット必須 | High |
| G2 | VPN認証 ≠ アプリ認証。テナント・ロール情報はネットワーク層から得られない | Critical |
| G3 | VPNユースケースの正解はTraefikのルーティング分岐。voltaは変更不要 | High |
| G4 | bypass時にdownstream appが期待するヘッダが空になる | High |
| G5 | bypass経由のアクセスは監査ログに残らない | Medium |

## 結論

**VPN用途:** voltaに手を入れず、Traefikで別ルートを切る。voltaの責務ではない。
**開発用途:** 既存の `DEV_MODE + isLocalRequest()` で十分。`192.168.x.x` への拡張はリターンが薄くリスクが高い。
