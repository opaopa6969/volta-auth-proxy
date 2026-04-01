# volta-auth-proxy — ターゲット層とマーケットポジション

[English](target-audience.md) | [日本語](target-audience.ja.md)

***

## 誰のための volta-auth-proxy？

### 主要ターゲット（今すぐ使える）

**1. 個人開発者 / インディーハッカー**

1-3 人で [SaaS](../docs/glossary/saas.ja.md) を作っている。[Auth0](../docs/glossary/auth0.ja.md) の月額 $2,400 が痛い。[Keycloak](../docs/glossary/keycloak.ja.md) の[設定地獄](../docs/glossary/config-hell.ja.md)は悪夢。スタックの全てを理解したい。volta はそんなあなたのため。

**2. アーリーステージの[スタートアップ](glossary/startup.ja.md)（~10 人）**

[MVP](glossary/mvp.md) を爆速で出したい。認証に時間をかけたくない。でも[ベンダーロックイン](../docs/glossary/vendor-lock-in.ja.md)は避けたい。チームに [Java](../docs/glossary/java.ja.md)/[JVM](../docs/glossary/jvm.ja.md) エンジニアが 1 人いれば十分。App 追加 = [YAML](../docs/glossary/yaml.ja.md) に 4 行。

**3. 社内ツールビルダー**

社内向け [SaaS](glossary/saas.ja.md)（wiki、チャット、管理画面）を量産している。[Google Workspace](glossary/google-workspace.md) / Gmail [ドメイン](glossary/domain.ja.md)認証で十分。[Cloudflare Zero Trust](../docs/glossary/zero-trust.ja.md) の代替を探している。

### 条件付きターゲット

**4. 成長中の[スタートアップ](glossary/startup.ja.md)（10-50 人）**

条件: [Java](glossary/java.md) エンジニアがいる。[セキュリティの責任](../docs/glossary/security-responsibility.ja.md)を負える覚悟がある。[Auth0](glossary/auth0.md)/Clerk の月額が痛い。Phase 2-3（複数 [IdP](../docs/glossary/idp.ja.md)、[MFA](../docs/glossary/mfa.ja.md)、[SAML](../docs/glossary/sso.ja.md)）でカバー。

**5. B2B [SaaS](glossary/saas.ja.md)（中小企業向け）**

条件: [マルチテナント](../docs/glossary/multi-tenant.ja.md)が核心要件。テナント別 [SSO](../docs/glossary/sso.ja.md)（SAML）が必要。でも [Keycloak](glossary/keycloak.md) は重すぎる。Phase 3 の SAML + テナント別 [IdP](glossary/idp.ja.md) 設定で対応。

**6. エンジニア教育 / ブートキャンプ**

プロダクトとしてではなく、**学習プラットフォーム**として。283 記事の[用語集](../docs/glossary/)（専門家 → おばあちゃんレベル）。認証の全体像を可視化した [DSL](dsl-overview.ja.md)。「分かったフリが一番危険」哲学。

### 合わない層（現時点）

**7. エンタープライズ（500+ 人）**

SOC2/ISO27001 の監査で「自前認証」は実績なしでは通らない。セキュリティ監査で「[Keycloak](glossary/keycloak.md)/[Okta](glossary/okta.md) を使え」と言われる。[SLA](glossary/sla.md) 保証なし。コミュニティなし。まだ。

**8. 非 [Java](glossary/java.md) チーム**

Python/Node/Go チームは [Java](glossary/java.md) のプロキシを保守したくない。[ForwardAuth](../docs/glossary/forwardauth.ja.md) で App 側は言語非依存だが、プロキシ自体の保守には [Java](glossary/java.ja.md) 知識が必要。

**9. 「設定だけで動かしたい」チーム**

[Auth0](glossary/auth0.md)/Clerk は GUI [ダッシュボード](glossary/dashboard.ja.md)。volta は [Java](glossary/java.md) コードの理解が必要。「制御しやすいは正義」に共感しない人には向かない。

***

## マーケットポジション

```
              フル機能 →
        ┌────────────────────────────┐
        │ Keycloak   Auth0   WorkOS  │
セルフ   │ ZITADEL    Clerk           │ クラウド
ホスト   │                            │ のみ
        │ ★ volta-auth-proxy         │
        │                            │
        └────────────────────────────┘
              最小構成 →
```

volta は**セルフホスト × [最小構成](glossary/minimum-viable-architecture.ja.md)**。最も軽く、最も制御しやすい選択肢。[トレードオフ](../docs/glossary/tradeoff.ja.md): [セキュリティの責任](glossary/security-responsibility.ja.md)は自分で負う。

### 競合の隙間

```
Auth0 が高い        → volta（MAU 関係なく $0）
Keycloak が重い     → volta（30MB、起動 200ms）
自前が怖い          → volta（DSL + 283 記事 + DGE 設計）
ZITADEL は Go だけど → volta（Java で完結）
```

***

## 収益機会

### 1. オープンソース + サポートモデル

```
無料:  volta-auth-proxy（MIT ライセンス）
有料:  優先サポート、セキュリティアドバイザリー、カスタム統合
       スタートアップ向け $500-2,000/月
       成長企業向け $5,000-10,000/月
```

### 2. マネージドホスティング

```
"volta Cloud" — 運用はこちらで。
ZITADEL Cloud や SuperTokens managed と同じモデル。
$29/月 基本 + 従量。
「運用の責任」という反対理由を解消。
```

### 3. エンタープライズ向けアドオン

```
volta Enterprise:
  - SOC2 コンプライアンスドキュメント
  - ペネトレーションテスト結果
  - SLA 保証（99.9%）
  - 専任サポートエンジニア
  - カスタム IdP 連携
  $1,000-5,000/月
```

### 4. 教育 / 認定

```
"volta Auth Academy"
  - 用語集を体系的なコースとして（283 記事、3 レベル）
  - DSL をカリキュラムとして（状態マシン → プロトコル → ポリシー）
  - DGE 設計手法の認定
  - DDE ドキュメント手法の認定
  $199/人（認定料）
```

### 5. DGE + DDE ツールキットライセンス

```
@unlaxer/dge-toolkit — 無料（MIT）
@unlaxer/dde-toolkit — 無料（MIT）
エンタープライズ機能:
  - DGE カスタムキャラクターパック
  - DDE 業界別用語集テンプレート
  - CI/CD 統合サポート
  $99-499/月
```

***

## エンタープライズへの道

今の volta ではエンタープライズは取れない。必要なもの:

| 要件 | 現状 | 対応策 |
|------|------|--------|
| セキュリティ監査実績 | Phase 1 の audit\_logs | [ペネトレーションテスト](glossary/penetration-test.ja.md) + CVE 対応実績 |
| [高可用性](glossary/high-availability.ja.md) | 単一インスタンス | Phase 2: [Redis](glossary/redis.md) セッション + 水平スケール |
| [コンプライアンス](glossary/compliance.ja.md)文書 | なし | SOC2、GDPR ドキュメント作成 |
| コミュニティ | なし | OSS コミュニティ構築 |
| 非 [Java](glossary/java.md) 対応 | [Java](glossary/java.ja.md) のみ | [Docker](glossary/docker.md) イメージ配布（Java を隠す）or Go 書き直し |
| [SLA](glossary/sla.md) | なし | マネージドホスティング |

### 設計力 + AI の優位性

```
従来のエンタープライズ認証への道:
  セキュリティエンジニア 5 人採用 → 2 年 → たぶん完成

volta の道:
  1 人の設計者（人間: アーキテクチャ、DSL、哲学）
  + AI（実装、テスト、ドキュメント、用語集）
  = より速く出荷、より速く改善、より良いドキュメント

283 記事の用語集だけで、
ほとんどのエンタープライズ認証製品より
ドキュメントが充実している。

設計力（人間）+ 実装速度（AI）
= インディーハッカーの速度でエンタープライズ品質。
```

過信ではない。新しいモデル。
