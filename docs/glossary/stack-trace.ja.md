# スタックトレース（Stack Trace）

[English version](stack-trace.md)

---

## これは何？

スタックトレースとは、何かがうまくいかなかったときにプログラムが実行中だったすべての関数呼び出しのリストです。コードがどのようにしてクラッシュした場所まで到達したかを示す「パンくずの道」のようなものです。

道に迷った後に自分の足跡をたどるようなものです。家を出て、メイン通りを左に曲がり、公園を通り抜け、パン屋の裏の路地に入り、今は見知らぬ場所にいます。スタックトレースは、あなたが曲がった場所のリストです。これがなければ、「どこかで迷った」だけで、どうやってここに来たのか、どうやって戻るのかわかりません。

---

## 読み方

Javaアプリケーションの簡略化されたスタックトレースです：

```
java.lang.NullPointerException: Session not found
    at com.volta.auth.SessionService.validateSession(SessionService.java:47)
    at com.volta.auth.ForwardAuthHandler.verify(ForwardAuthHandler.java:23)
    at com.volta.auth.Router.handle(Router.java:15)
    at io.javalin.Javalin.serve(Javalin.java:102)
```

上から下に読みます：

1. **エラー：** `NullPointerException: Session not found` -- 何が起きたか。
2. **発生場所：** `SessionService.java`、47行目 -- 正確な場所。
3. **呼び出し元：** `ForwardAuthHandler.java`、23行目 -- 呼び出した側。
4. **さらにその呼び出し元：** `Router.java`、15行目 -- 呼び出し元の呼び出し元。
5. **起点：** `Javalin.java`、102行目 -- チェーン全体が始まった場所。

各行はスタックの「フレーム」です。一番上がエラーが発生した場所。一番下がリクエストがシステムに入った場所。その間にあるのは、コードがたどったパスです。

---

## 読みやすいスタックトレースが重要な理由

すべてのスタックトレースが同じように役立つわけではありません。助けになるものもあれば、意味不明なノイズの壁のようなものもあります。

**良いスタックトレース（シングルプロセス、自分のコード）：**

```
NullPointerException: Session not found
    at SessionService.validateSession(SessionService.java:47)
    at ForwardAuthHandler.verify(ForwardAuthHandler.java:23)
```

エラーが見え、ファイルが見え、行番号が見えます。ファイルを開いて47行目を見れば、バグが見つかります。これは数秒で済みます。

**悪いスタックトレース（複数サービス、フレームワークの魔法）：**

```
ERROR: upstream connect error or disconnect/reset before headers
    ... Envoyプロキシ内部の47行 ...
    ... サービスメッシュ設定の23行 ...
    ... 自分のコードへの参照は一切なし ...
```

エラーはネットワーク境界のどこかで発生しました。スタックトレースはプロキシの内部を表示しますが、あなたのコードは表示しません。どのサービスが失敗したか、コードのどの行がトリガーしたか、実際のエラーが何だったかすらわかりません。これは何時間もかかります。

---

## voltaの哲学：「スタックトレースが読める地獄を選べ」

volta-auth-proxyは単一のJavaプロセスとして動作します。何かがうまくいかないとき、スタックトレースはあなたのコードの中で、行番号付きで、正確に何が起きたかを表示します。「エラーはサービスメッシュのどこかにある」という謎はありません。

これは意図的な設計上の選択です。voltaはマイクロサービスとして構築することもできました（セッション用、JWT用、OIDC用にそれぞれ別のサービス）。各サービスは独自のスタックトレースを持ちますが、サービス境界を越えるエラーは「connection refused」や「timeout after 5000ms」のような役に立たないトレースを生成します。

すべてを1つのプロセスに保つことで、voltaはすべてのエラーが問題のあるコードの正確な行を指す読みやすいスタックトレースを生成することを保証します。午前3時に認証の問題をデバッグしているとき、これは5分の修正と5時間の調査の違いになります。

---

## volta-auth-proxyでは

voltaは単一のJavaプロセスとして動作します。OIDCコールバックの失敗からセッション検証のバグまで、すべてのエラーが問題の発生したコード行を直接指す完全で読みやすいスタックトレースを生成するためです。

---

## さらに学ぶために

- [debugging.ja.md](debugging.ja.md) -- 一箇所でデバッグすることが重要な理由。
- [single-process.ja.md](single-process.ja.md) -- voltaがシングルプロセスである理由。
- [microservice.ja.md](microservice.ja.md) -- voltaが採用しなかった代替アーキテクチャ。
- [java.ja.md](java.ja.md) -- voltaのスタックトレースを生成する言語。
