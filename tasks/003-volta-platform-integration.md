# Task 003: volta-platform とのつなぎ込み

## 概要

volta-auth-proxy と volta-platform を接続する。
volta-platform のサービス群が volta-auth-proxy の ForwardAuth + Internal API を使って認証する。

## volta-platform の構造（確認が必要）

```
volta-platform/
  volta-console/     ← 管理画面？
  volta-agent/       ← エージェント？
  services/          ← マイクロサービス群？
  config/
  scripts/
```

## 決めること

### 1. どのサービスが ForwardAuth を使うか

```
volta-config.yaml に追加:
  apps:
    - id: volta-console
      subdomain: console
      upstream: http://volta-console:???
      allowed_roles: [ADMIN, OWNER]

    - id: volta-agent
      subdomain: agent
      upstream: http://volta-agent:???
      allowed_roles: [MEMBER, ADMIN, OWNER]

    - id: ???（他のサービス）
```

### 2. volta-console と volta-auth-proxy の admin UI の関係

```
選択肢:
  A. volta-console が管理画面を持つ → auth-proxy の admin API を叩く
  B. auth-proxy の admin UI (/admin/*) をそのまま使う
  C. ハイブリッド: console に埋め込み（iframe or API 経由）

→ DGE で決める
```

### 3. volta-agent と認証の関係

```
確認:
  - volta-agent はブラウザ経由？ API 経由？
  - ユーザーコンテキストあり？ なし（M2M）？
  - ForwardAuth か Service Token か
```

### 4. 共通 docker-compose

```
現状:
  volta-auth-proxy/docker-compose.yml: Postgres + auth-proxy
  volta-platform/??: 各サービス

目標:
  全サービスが 1 つの docker-compose で起動
  or docker-compose の extends/override パターン
```

### 5. テナントモデルの共有

```
確認:
  - volta-platform は独自のテナント概念を持っている？
  - auth-proxy のテナント = platform のテナント？
  - DB は共有？ 分離？
```

### 6. volta-sdk の導入

```
volta-platform の各サービスに:
  - volta-sdk (Java) で JWT 検証
  - or X-Volta-* ヘッダ読み取り
  - Internal API で user/tenant/member 操作
```

## 参照ドキュメント

volta-auth-proxy 側の仕様は全てここにある:

```
設計全体:     dge/specs/implementation-all-phases.md
DSL:          dsl/auth-machine.yaml (v3.2), protocol.yaml, policy.yaml, errors.yaml
DSL 概要:     docs/dsl-overview.md
UI フロー:    dge/specs/ui-flow.md
Protocol:     dge/sessions/2026-03-31-identity-gateway-protocol-auto.md
スキーマ:     dsl/volta-config.schema.yaml
サンプル設定: volta-config.example.yaml
```

## 進め方

```
1. volta-platform のディレクトリで新セッション開始
2. volta-platform の構造を読む
3. DGE で「どのサービスをどう繋ぐか」を回す
4. volta-config.yaml を拡張
5. 共通 docker-compose を作成
6. 各サービスに volta-sdk を導入
```
