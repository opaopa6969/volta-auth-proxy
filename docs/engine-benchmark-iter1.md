# Engine Benchmark — Iteration 1 Report

Date: 2026-08-16
Profile: engine-benchmark
Iteration: 1 of 3

## 観測事実

### アーキテクチャ
- Java 21.0.9 (Oracle LTS) + Maven 3.9.9 + Javalin 6.7.0 (Jetty-based)
- tramli 3.7.1 状態機関エンジン (OIDC login / MFA verify のみ使用)
- Postgres 16 + HikariCP (pool=10, minIdle=2)
- `/auth/verify` (ForwardAuth, Traefik が全リクエストで呼ぶ) は**手続き型** (tramli 不使用)
- ホットパス:
  1. `AuthService.authenticate(ctx)` — セッション Cookie → findSession + findUserById + findTenantById + findMembership + touchSession (4〜5 DBクエリ)
  2. `JwtService.issueToken(principal)` — **RS256署名を毎リクエスト実行** (JWT TTL=300s=5分)

### Baseline測定（再現可能）

**マイクロベンチ: RS256 JWT署名単体** (`src/test/.../JwtSignBenchmark.java`, `-Dbenchmark=true`)
- 1署名あたり: **1057.61 μs (約1.06 ms)**
- シングルスレッドスループット: **946 ops/s**
- 測定条件: 2048bit RSA, nimbus-jose-jwt 10.5, best of 5 rounds × 20,000 iterations, warmup 2,000

**エンドツーエンド: `/auth/verify`（サービストークン経路、直列300req）**
- 同一環境: 別ポート 7071, Docker Postgres 54329 (volta/volta), サービストークン認証 (DB不要)
- Baseline（元コード）:
  - Mean: **8.456 ms** / p50: 7.591 ms / p90: 13.435 ms / p99: 23.492 ms
  - 直列スループット: **118 req/s**

## 仮説

`/auth/verify` は毎リクエスト RS256 署名（~1ms）を実行する。同一プリンシパルの JWT は TTL 窓内（5分）でクレームが同一なので、署名済みトークンをキャッシュして再利用すれば、署名コストを削減できる。ログアウト伝播は JWT exp（最大5分）で設計されているため、キャッシュ最大5分は許容範囲。

## 実施内容

`JwtService` に JWT 発行キャッシュを追加:
- `ConcurrentHashMap<JwtCacheKey, CachedJwt>` を追加
- キー: `(AuthPrincipal, audience, extraClaims, cacheGeneration)` — レコードで値等価
- 値: `(token, expiresAt)`
- ヒット条件: 残存寿命 > 30秒 かつ 鍵ローテーション世代が同一
- `rotateKey()` で `cacheGeneration++` + `jwtCache.clear()` で無効化
- 上限 50,000 エントリで全クリア（異常時のメモリ保護）
- ファイル: `src/main/java/org/unlaxer/infra/volta/JwtService.java`

## 検証結果

**エンドツーエンド: `/auth/verify`（同一環境、改善後、直列300req）**

| 指標 | Baseline | Improved | 改善 |
|---|---|---|---|
| Mean | 8.456 ms | **7.083 ms** | **-16.2%** |
| p50 | 7.591 ms | **5.996 ms** | **-21.0%** |
| p90 | 13.435 ms | 12.198 ms | -9.2% |
| p99 | 23.492 ms | 20.185 ms | -14.1% |
| 直列スループット | 118 req/s | **141 req/s** | **+19.5%** |

改善幅 ~1.4ms は RS256署名の ~1.06ms 削減と整合的（残りはキャッシュ lookup/put オーバーヘッド + 計測ノイズ）。

**並列（10ワーカー、300req、同一環境）**

| 指標 | Baseline | Improved | 改善 |
|---|---|---|---|
| Mean | 10.091 ms | **4.504 ms** | **-55.4%** |
| p50 | 9.484 ms | **3.457 ms** | **-63.6%** |
| p90 | 18.013 ms | **8.050 ms** | **-55.3%** |
| p99 | 34.097 ms | **19.789 ms** | **-42.0%** |

並列時の改善が直列より遥かに大きい理由: baseline は毎リクエクト RS256 署名（CPU集約的、~1ms）が並列で CPU を競合しレイテンシが膨らむ。改善後はキャッシュヒットで署名をスキップし CPU 競合が起きないため、並列恩恵がフルに現れる。

テスト: `JwtKeySelectionTest` 4件すべて通過。

## 次の判断

改善あり。Iter2 へ進む。

## 外部データ出典

- RS256署名コストの公表値: 自前マイクロベンチで実測（本環境: 1.06ms/op）。外部公表値との直接比較は環境依存が大きく、TechEmpower Benchmarks の Javalin エントリは存在するが具体的な RS256 署名オーバーヘッドの公表値は得られず。nimbus-jose-jwt 10.5 の `JOSEObjectType.JWT` 定数は javap で存在確認済み。
  - TechEmpower Framework Benchmarks: https://www.techempower.com/benchmarks/ （取得日 2026-08-16, CC BY 4.0 相当の公開ベンチマーク）
  - nimbus-jose-jwt 10.5: Apache License 2.0, Maven Central

## 再現手順

```bash
# 1. Docker Postgres 起動済み (port 54329, volta/volta)
# 2. baseline jar ビルド（元コード）
mvn -o package -DskipTests
cp target/volta-auth-proxy-0.3.0-SNAPSHOT.jar /tmp/volta-baseline.jar

# 3. baseline サーバ起動
PORT=7071 DB_HOST=127.0.0.1 DB_PORT=54329 DB_NAME=volta_auth DB_USER=volta DB_PASSWORD=volta \
  JWT_KEY_ENCRYPTION_SECRET=K1-KN-vFgqulrtK_YEmdpF4kXJNXoIL1KlIm9C8Uxhc VOLTA_SERVICE_TOKEN=bench-token \
  java -jar /tmp/volta-baseline.jar &

# 4. baseline ベンチマーク
bash tasks/bench-verify.sh 300  # .env の VOLTA_SERVICE_TOKEN を使う版（要調整）

# 5. 改修後 jar ビルド＆起動＆ベンチ（同一手順）
```

マイクロベンチ:
```bash
mvn -o test -Dtest=JwtSignBenchmark -Dbenchmark=true
```
