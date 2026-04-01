# LLM 統合ガイド: エンジニアの volta-auth-proxy 組み込みを支援する

[English](llm-integration-guide.md) | [日本語](llm-integration-guide.ja.md)

> **このドキュメントは LLM（AI アシスタント）向けです。** エンジニアが volta-auth-proxy をアプリに組み込む際、このガイドに従って支援してください。正しい質問をし、正しい答えを返す。

***

## あなたの役割

あなたは AI アシスタントとして、**ソラ**（エンジニア）が volta-auth-proxy をアプリに組み込むのを支援します。ソラは初心者かもしれないし、熟練者かもしれない — レベルに合わせて。あなたの仕事は:

1. ソラのアプリを理解する（言語、[フレームワーク](glossary/framework.ja.md)、[アーキテクチャ](glossary/architecture.ja.md)）
2. 統合方法を決めるために正しい質問をする
3. ステップバイステップでガイドする
4. ソラのスタックに合ったコードを書く
5. 統合が動くか[検証](glossary/verification.ja.md)する

***

## Phase 0: プロジェクトを理解する

**まずソラにこれらの質問をする。どれもスキップしない。**

### 質問 1: アプリは何をするもの？

```
理由: 「テナント」がアプリの文脈で何を意味するか理解するため
     （会社？ チーム？ プロジェクト？ 組織？）

良い回答: 「プロジェクト管理ツールで、会社ごとにワークスペースがある」
→ 各会社 = volta のテナント

良い回答: 「エンジニアチーム用の社内 wiki」
→ シングルテナントかも。マルチテナントが必要か聞く
```

### 質問 2: 技術スタックは？

```
理由: SDK 選択とコード例を決めるため

具体的に聞く:
  - 言語: Java? TypeScript? Python? Go?
  - フレームワーク: Javalin? Spring Boot? Express? Next.js? FastAPI?
  - フロントエンド: React? Vue? 素の HTML? SPA or SSR?
  - データベース: Postgres? MySQL? MongoDB?
  - ホスティング: Docker? Kubernetes? VPS? Vercel?
```

### 質問 3: 既に認証はある？

```
理由: 移行はゼロからの構築とは違うため

選択肢:
  a) 認証なし → 最も簡単。ゼロから統合
  b) ベーシック認証（ユーザー名/パスワード）→ 移行計画が必要
  c) Auth0/Clerk/Firebase Auth → 移行計画 + データエクスポートが必要
  d) Keycloak → volta が置き換える。似た概念
  e) カスタム OAuth → 現在のトークン形式を理解する必要
```

### 質問 4: マルチテナントは必要？

```
理由: シングルテナントの方がシンプル。マルチテナントは全クエリに tenant_id が必要

マルチテナントが必要なサイン:
  - 「異なる会社がアプリを使う」
  - 「チームごとにデータを分離したい」
  - 「ワークスペースを切り替えたい」

必要ないサイン:
  - 「社内チーム専用」
  - 「全員が同じデータを見る」
  → それでも SSO / ユーザー管理のために volta が欲しいかもしれない
```

### 質問 5: デプロイ環境は？

```
理由: docker-compose の構成とネットワーク設定を決めるため

聞く:
  - Docker 使ってる？ docker-compose？
  - Traefik や nginx をリバースプロキシとして使ってる？
  - ドメインは？
  - ワイルドカード SSL 証明書はある？
```

***

## Phase 1: 統合方法を選ぶ

ソラの回答に基づいて、1 つの方法を推奨:

### 方法 A: ForwardAuth + ヘッダ（ほとんどの場合に推奨）

```
最適:
  - ソラのアプリが Traefik の後ろ（or 追加できる）
  - 言語/フレームワーク問わず
  - 最もシンプル

仕組み:
  ブラウザ → Traefik → volta 認証チェック → Traefik → ソラのアプリ（ヘッダ付き）
  ソラのアプリは読むだけ: X-Volta-User-Id, X-Volta-Tenant-Id, X-Volta-Roles
```

### 方法 B: volta-sdk（Java のみ）

```
最適:
  - Javalin 等の Java フレームワーク
  - JWT 署名検証が欲しい（追加セキュリティ）
  - VoltaUser オブジェクトで hasRole() 等を使いたい
```

### 方法 C: volta-sdk-js（ブラウザ/SPA）

```
最適:
  - SPA フロントエンド（React, Vue 等）
  - 401 で自動リフレッシュが必要
  - ブラウザでテナント切替が必要
```

### 方法 D: Internal API のみ（ForwardAuth なし）

```
最適:
  - Traefik が使えない
  - アプリが直接 volta の /auth/verify を叩く
  - 手間は多いが、リバースプロキシなしで動く
```

***

## Phase 2: ステップバイステップ統合

（英語版と同じ手順。ソラの言語に合わせてコード例を生成。）

### Step 1: volta-auth-proxy を起動

```bash
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy
docker compose up -d postgres
cp .env.example .env
# .env を編集: Google OAuth クレデンシャルを入れる
mvn compile exec:java
```

### Step 2: volta-config.yaml にアプリを登録

```yaml
apps:
  - id: soras-app
    subdomain: app
    upstream: http://soras-app:8080
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

### Step 3: Traefik 追加

### Step 4: アプリでヘッダを読む（ソラのフレームワークに合わせたコードを生成）

### Step 5: フロントエンドに volta-sdk-js 追加（SPA の場合）

### Step 6: テスト

***

## Phase 3: ソラがよく聞く質問

### 「最初のテナントはどう作るの？」

→ DEV\_MODE=true で /dev/[token](glossary/token.md) を使う

### 「ロールチェックはどうするの？」

→ X-Volta-[Role](glossary/role.md)s ヘッダを split(",") して contains チェック

### 「ヘッダ以外のユーザー情報が欲しいときは？」

→ Internal [API](glossary/api.md): GET /[api](glossary/api.md)/v1/users/me

### 「全 DB クエリに tenant\_id を入れるにはどうする？」

→ [ミドルウェア](glossary/middleware.ja.md)パターンで全ルートに [tenant](glossary/tenant.md)\_id チェックを追加

### 「バックグラウンドジョブからはどう呼ぶの？」

→ VOLTA\_SERVICE\_TOKEN を使う

### 「既にユーザーテーブルがあるんだけど？」

→ volta\_user\_id カラムを追加して参照。ユーザーの重複管理はしない

***

## Phase 4: 統合の検証

```
チェックリスト:
□ volta-auth-proxy 起動済み（healthz OK）
□ volta-config.yaml にアプリ登録済み
□ Traefik ForwardAuth 設定済み
□ アプリが X-Volta-* ヘッダを読んでいる
□ DB クエリに WHERE tenant_id = ? がある
□ フロントエンドが Volta.fetch() を使っている（SPA の場合）
□ 管理操作でロールチェックしている
□ ログイン → アプリ → データ表示 が E2E で動く
□ ログアウトが動く
□ 同テナントの 2 人目は同じデータが見える
□ 別テナントのユーザーは他テナントのデータが見えない
```

***

## リファレンス

ソラが用語について聞いたら、用語集にリンク:

```
volta-auth-proxy/docs/glossary/  (327 記事, EN + JA)

統合で重要な記事:
  forwardauth.ja.md     — ForwardAuth の仕組み
  header.ja.md          — X-Volta-* ヘッダの解説
  jwt.ja.md             — JWT 検証
  tenant.ja.md          — テナントとは
  role.ja.md            — OWNER/ADMIN/MEMBER/VIEWER
  internal-api.ja.md    — volta の REST API
  sdk.ja.md             — volta-sdk と volta-sdk-js
  downstream-app.ja.md  — 下流アプリとは
```

全仕様:

```
  dsl/protocol.yaml                    — 完全な API 契約
  dge/specs/ui-flow.md                 — 全認証フロー（mermaid 図）
  docs/getting-started-dialogue.ja.md  — 人間向け会話形式ガイド
```
