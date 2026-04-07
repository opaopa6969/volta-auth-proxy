# DGE 査読劇: volta-auth-proxy 論文

- **日時**: 2026-04-07
- **テーマ**: volta-auth-proxy: ForwardAuth-Native Multi-Tenant Identity Gateway
- **評価者**: 🔒 セキュリティ審査官, 👤 今泉, 😈 Red Team, 🎩 千石
- **判定**: Major Revision（4人全員一致）→ v2 修正完了

## Gap 一覧

| # | Gap | Severity | Status |
|---|-----|----------|--------|
| G1 | ForwardAuth ヘッダ偽装の脅威モデル | 🔴 Critical | ✅ v2 反映（§2.2 + §3.1） |
| G2 | パフォーマンス評価 | 🔴 Critical | ✅ v2 反映（§4.2 Resource Footprint） |
| G3 | gap claim → trade-off に修正 | 🟠 High | ✅ v2 反映（§5.2 全面書き直し） |
| G4 | Keycloak + oauth2-proxy との比較 | 🟠 High | ✅ v2 反映（§5.2） |
| G5 | セッションセキュリティ詳細 | 🟠 High | ✅ v2 反映（§3.2 Security Properties） |
| G6 | PKCE / token validation の明記 | 🟠 High | ✅ v2 反映（§3.2） |
| G7 | 「zero auth code」→「zero authentication code」 | 🟠 High | ✅ v2 反映（Abstract, §1.2） |
| G8 | Keycloak TCO 出典 → 削除、具体的事実に置換 | 🟡 Medium | ✅ v2 反映（§1.1） |
| G9 | テナント分離メカニズムの明記 | 🟡 Medium | ✅ v2 反映（§3.4） |
| G10 | リソースフットプリント比較 | 🟡 Medium | ✅ v2 反映（§4.2 比較表） |
| G11 | 運用・コンプライアンスコスト | 🟡 Medium | ✅ v2 反映（§5.3） |
| G12 | スケーラビリティの限界 | 🟡 Medium | ✅ v2 反映（§5.4, §6.1） |
| G13 | 移行パスの記述 | 🟢 Low | ✅ v2 反映（§7 Future Work #5） |

## v2 での主な変更

1. **§2.2 新設**: ForwardAuth Trust Boundary — ヘッダ偽装の脅威モデル + 緩和策
2. **§1.2 修正**: 「zero auth code」→「zero authentication code」、認可は downstream の責務と明記
3. **§3.2 新設**: Security Properties — PKCE, session regeneration, cookie 属性, token validation
4. **§3.4 追記**: テナント分離メカニズム（FK + app-level、RLS 未実装を正直に記載）
5. **§4.2 新設**: Resource Footprint 比較表（volta vs Authelia vs Keycloak）
6. **§5.2 全面書き直し**: 「gap がある」→「Authentik が最も近い競合。trade-off が異なる」
7. **§5.3 新設**: Operational Considerations — self-hosted の運用コストを正直に記載
8. **§5.4 新設**: Threats to Validity — スケーラビリティ、OpenID 未認証を明記
9. **§1.1 修正**: Keycloak TCO 数字を削除、具体的な事実（production setup の複雑さ）に置換
10. **§3.1 追記**: SPOF の議論と緩和策
11. **§1.2 追記**: code-first の trade-off を明記（「visual editor なら Authentik」）
12. **§7 追加**: OpenID Conformance Testing, Migration tooling, PostgreSQL RLS

---

## Round 2 (v2 → v3)

- **評価者**: ☕ ヤン, 🧩 マンガー, 🔒 セキュリティ審査官（留任）
- **判定**: Minor Revision（ヤン）/ Minor Revision（マンガー）/ Accept with Minor Revision（審査官）

### Round 2 Gap 一覧

| # | Gap | Severity | Status |
|---|-----|----------|--------|
| G14 | tramli 説明の重複を削減 | 🟡 Medium | ✅ v3 反映（§3.3 縮小、companion paper 参照） |
| G15 | フロー図を OIDC + Invite に絞る | 🟢 Low | ✅ v3 反映 |
| G16 | 再デプロイコストを認める | 🟡 Medium | ✅ v3 反映（§6.1） |
| G17 | embedded vs standalone framing (SQLite analogy) | 🟡 Medium | ✅ v3 反映（§5.2） |
| G18 | 「Auth as Code」positioning | 🟡 Medium | ✅ v3 反映（§6.1 書き直し） |
| G19 | CSRF 対策の明記 | 🟡 Medium | ✅ v3 反映（§3.2 Security Properties） |
| G20 | Rate limiting 詳細 | 🟡 Medium | ✅ v3 反映（§3.2 Security Properties） |

### v3 での主な変更

1. **§3.2 追記**: CSRF 対策（FlowInstance UUID as implicit CSRF token）、Rate limiting 数値
2. **§3.3 縮小**: tramli の詳細を companion paper 参照に置き換え
3. **フロー図**: Passkey/MFA を省略、OIDC + Invite の 2 つに絞る
4. **§5.2 追記**: SQLite/PostgreSQL analogy
5. **§6.1 書き直し**: 「Auth as Code」framing。IaC との parallels。再デプロイコストを正直に記載
