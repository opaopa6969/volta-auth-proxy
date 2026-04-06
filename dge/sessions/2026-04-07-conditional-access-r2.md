# DGE Session: 条件付きアクセス — Round 2 (Critical 深掘り)

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / 🎩千石 / 😈Red Team / 🏥ハウス / 🎨深澤
> Theme: G1/G3/G8 の Critical Gap を解決する
> Round: 2
> New Gaps: 8 (G12-G19)

## Scene 1: G1 スコープ確定

スコープを「新デバイスからのログイン検知 + 対応」に限定。
除外: Cookie窃取対策（HttpOnly/SameSite/Secureで十分）、地理制限（GeoIP精度問題+VPN無力化）、時間帯制限。
除外理由をADRとして記録すべき。

→ G1 解決案確定
→ Gap G12: スコープ外のADR記録 [Medium]

## Scene 2: G3 評価タイミング確定

2層:
1. ログイン時 — 新デバイス判定 → 通知 or step-up
2. ForwardAuth — User-Agent変更のみ軽量チェック（warning flag、即ブロックなし）

→ G3 解決案確定
→ Gap G13: UA変更時アクション未定義 [Medium]
→ Gap G14: ForwardAuth UA比較処理 [Low]

## Scene 3: G8 デバイス識別確定

Persistent Cookie方式:
  名前: __volta_device_trust
  値: UUID (デバイスID)
  Max-Age: 90日, HttpOnly, SameSite=Lax, Secure, Path=/login
  DB: trusted_devices テーブル (max 10台, LRU削除)

Fingerprinting は GDPR リスク + 精度低下で除外。
User-Agent + IP の組み合わせも不十分（IP変動、UA共通）で除外。

「このデバイスを記憶する」はMFA通過後に表示。

→ G8 解決案確定
→ Gap G15: trusted_devices テーブル設計 [High]
→ Gap G16: 「記憶する」UIタイミング [Medium]
→ Gap G17: Cookie削除/プライベートブラウジング時の頻度制限 [Low]

## Scene 4: 統合アーキテクチャ

```
ログイン成功:
  __volta_device_trust cookie 確認
  → 既知: last_seen_at更新、通常ログイン
  → 未知: メール通知 + (テナント設定で) step-up
  → step-up通過後: 「記憶しますか？」→ cookie発行 + DB INSERT

ForwardAuth:
  → UA変更検知 → warning flag (audit_log記録のみ)

JWT amr:
  → ["oidc:google"], ["passkey"], ["oidc:google", "otp"]
  → step-up後にJWT再発行でamr更新
```

SM統合: SessionIssueProcessor後にデバイスチェック（state追加不要、Processor内ロジック）。

→ Gap G18: 通知送信先と頻度制御 [Medium]
→ Gap G19: SM統合ポイント [Medium]

## Gap Summary (Cumulative: Round 1 + 2)

| # | Gap | Severity | Status |
|---|-----|----------|--------|
| G1 | スコープ | Critical | **RESOLVED** — 新デバイス検知に限定 |
| G2 | 誤検知UX | High | Open |
| G3 | 評価タイミング | Critical | **RESOLVED** — 2層(ログイン時+ForwardAuth UA) |
| G4 | 条件の主体 | High | Open |
| G5 | IP変更の扱い | High | Partially — ForwardAuthはUA比較のみ |
| G6 | amr更新タイミング | Medium | Open |
| G7 | 認証状態3表現の関係 | Medium | Open |
| G8 | デバイス識別 | Critical | **RESOLVED** — Persistent Cookie + trusted_devices |
| G9 | 検知/対応レイヤー分離 | High | Open |
| G10 | 通知メッセージトーン | Medium | Open |
| G11 | amr値セット | Low | Open |
| G12 | ADR記録の仕組み | Medium | New |
| G13 | UA変更時アクション | Medium | New |
| G14 | ForwardAuth UA比較 | Low | New |
| G15 | trusted_devicesテーブル設計 | High | New |
| G16 | 「記憶する」UIタイミング | Medium | New |
| G17 | Cookie削除時の頻度制限 | Low | New |
| G18 | 通知送信先と頻度 | Medium | New |
| G19 | SM統合ポイント | Medium | New |

Critical: 3 (all resolved) / High: 5 / Medium: 8 / Low: 3 — Total: 19
