# MCP 化 Phase 2 STATUS — volta-auth-proxy

> 更新: 2026-08-22 (最終確認完了)

## 進捗

| Phase | 項目 | 状態 |
|-------|------|------|
| Design | DESIGN.md 作成 | done |
| Design | vgw との役割分担整理 | done |
| Coord | issue-hub に協調 issue 登録 | done (#260, #261, #262) |
| Impl | MCP サーバ実装 (mcp/server.mjs) | done |
| Impl | e2e テスト (mcp/test/e2e.mjs) | done (ALL PASSED) |
| Impl | volta.service.json | done |
| Impl | deploy/volta-auth-proxy-mcp.service | done |
| Impl | run.sh | done |
| Impl | skill docs/skills/operate-auth-proxy/SKILL.md | done |
| Deploy | volta__svc_add dry-run | done (差分確認済み) |
| Deploy | volta__svc_add confirm:true | done (volta-auth-proxy-mcp 登録) |
| Deploy | gateway_routes_diff | done (1 件: auth-mcp.unlaxer.org) |
| Deploy | gateway_routes_apply confirm:true | done (SIGHUP 済み) |
| Deploy | prod で MCP サーバ起動 | done (systemctl --user enable --now) |
| Deploy | https://auth-mcp.unlaxer.org/healthz 200 | done (200, {"ok":true,"name":"auth-mcp","version":"0.1.0"}) |
| Deploy | catalog__backend_status ready | done (status=ready, tools=25, server=auth-mcp v0.1.0) |

## dry-run 記録

### svc_add dry-run
- 新規サービス `volta-auth-proxy-mcp`
- port 9211, host 192.168.1.8, namespace=auth
- 差分: services.json に新規エントリ追加（MCP 項あり）

### gateway_routes_diff
- 変更: `[新規] auth-mcp.unlaxer.org -> http://192.168.1.8:9211` (1 件)
- 温存: 2 件 (adoyose-admin, mahjong-mcp — 手動設定の残置)
- 自分の 1 件のみのため confirm して適用

### gateway_routes_apply (confirm:true)
- バックアップ: /home/opa/volta-gateway/volta-gateway.yaml.bak-generated-20260822-024548
- SIGHUP 済み（瞬断なし）

## issue-hub

| # | title | target | 状態 |
|---|-------|--------|------|
| 260 | auth → tramli: フロー定義 API 形式の確認 | tramli | open (暫定仕様で進行) |
| 261 | auth → volta-auth-console: 管理UI と MCP tool の役割分担 | volta-auth-console | open (暫定仕様で進行) |
| 262 | auth → vgw: 旧版(Java)と後継(Rust)の役割分担 | auth-test/volta-gateway | open (暫定仕様で進行) |

## 未決事項

1. **retired 確定判定**: volta-auth-proxy (Java) は catalog で operational_status=retired。後継 volta-auth-server (Rust) が active。本 MCP ラップは後継に未移行の機能の補完として位置づけ。retired 確定時に auth namespace を disable し vgw に完全移行するタイミングは持ち主が判断。
2. **認証コンテキスト**: M2M トークン発行→JWT 渡しの 2 段階を採用。エージェントがユーザー JWT を外部から注入する形は未対応（暫定）。
3. **tramli フロー定義形式**: /viz/flows の JSON 形式を暫定使用。tramli 側で形式確定時に #260 で合わせる。

## 割当表との整合

- 割当表 #14: namespace=`auth`, port=9211
- machine_ports で 9211 が空きなことを確認済み
- 既存サービス `volta-auth-proxy` (port 7070) とは別 id `volta-auth-proxy-mcp` で新規登録
