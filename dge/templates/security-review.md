# DGE Template: セキュリティレビュー

## 概要
API やシステムのセキュリティを会話劇で検証する。

## 推奨キャラクター
千石 (品質) + Red Team (攻撃) + ハウス (隠れた問題)

## Scene 構成

### Scene 1: 認証・認可
```
千石: 「認証トークンの保存場所は？ localStorage なら却下。」
Red Team: 「JWT の secret が漏洩した場合の影響範囲は？」
ハウス: 「admin 権限の付与フローは？ 昇格攻撃は防げるか？」

Gap: token 保存、secret 管理、権限昇格、session 管理
```

### Scene 2: 入力検証
```
千石: 「全入力に sanitize が入っているか確認。」
Red Team: 「SQL injection: ' OR 1=1 -- を試す。XSS: <script> を試す。」
ハウス: 「file upload があるなら path traversal は？ SSRF は？」

Gap: SQL injection, XSS, CSRF, path traversal, SSRF
```

### Scene 3: データ保護
```
千石: 「PII は暗号化されているか。ログに PII が出力されていないか。」
Red Team: 「DB dump が漏洩した場合、password は復号できるか？」
ハウス: 「バックアップは暗号化されているか？ 誰がアクセスできる？」

Gap: 暗号化 (at-rest, in-transit)、PII 管理、ログの redaction、バックアップ
```

### Scene 4: インフラ
```
千石: 「HTTPS は強制か。CORS の設定は最小限か。」
Red Team: 「rate limiting がなければ brute force で突破できる。」
ハウス: 「依存ライブラリの CVE は定期的にチェックしているか？」

Gap: HTTPS, CORS, rate limiting, dependency audit, DDoS 対策
```
