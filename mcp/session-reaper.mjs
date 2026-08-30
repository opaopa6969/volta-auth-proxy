// ホストセッションの回収。
//
// **なぜ要るか**: Streamable HTTP は要求ごとの往復で、クライアントが黙って
// 居なくなっても気づけない。プロセスが落ちた・ネットワークが切れた・agent が
// 再起動した場合、DELETE も close も来ないので `server.onclose` は永遠に来ない。
//
// 1 セッションはバックエンドごとに接続を 1 本ずつ持つ（v2 の双方向対応のため）。
// つまり放置セッションのコストは **バックエンド数 × 放置数** の接続と FD になる。
//
// 実際に起きたこと（2026-08-30）:
//   1.8 時間で FD 41,929 / ESTABLISHED 75,000 まで育ち、/proc/net/tcp が 7 万行に。
//   それを毎秒読む監視ツールが 290MB/分 で膨らみ、箱がメモリ枯渇。
//   OOM が 26 回発動して systemd や sshd まで巻き添えになり、SSH も HTTP も届かなくなった。
//
// 判断を純粋な関数に切り出してあるのは、**時計もタイマーも無しで試験するため**。

/**
 * 閉じるべきセッションを選ぶ。**選ぶだけで閉じない**（呼ぶ側が閉じる）。
 *
 * @param {Map<string,{lastSeen:number,open?:number}>} sessions sid -> 最終アクセス時刻と開いている接続数
 * @param {{now:number, idleMs:number, maxSessions:number}} opt
 * @returns {string[]} 閉じるべき sid。**古い順**
 */
export function pickExpired(sessions, { now, idleMs, maxSessions }) {
  const entries = [...sessions.entries()].map(([sid, s]) => [sid, Number(s?.lastSeen) || 0, Number(s?.open) || 0]);
  const doomed = new Set();

  // 1) 放置されたもの。idleMs が 0 以下なら無効（時間では切らない）
  //
  // ★ **開いたままの接続(SSE)を持つセッションは切らない。**
  //   GET /mcp は接続を張りっぱなしにするので、その間は新しい要求が来ず
  //   lastSeen が古いままになる。それを「放置」と読むと、**繋がっている
  //   クライアントを切って** 404 を返し、再接続 → 新セッションを作らせる。
  //   実測(2026-08-30): 20 分で 292 セッションを回収し、その直後に GET /mcp の
  //   404 が続いていた。積み上がりの一部は回収側が作っていた。
  if (idleMs > 0) {
    for (const [sid, last, open] of entries) if (open === 0 && now - last > idleMs) doomed.add(sid);
  }

  // 2) 数の上限。**受け付けを止めるのではなく、古いものから閉じる**。
  //    新しい接続を拒むと「使えない」だけで原因が見えないが、
  //    古いものを閉じれば動き続けたうえで積み上がりも止まる。
  if (maxSessions > 0 && entries.length - doomed.size > maxSessions) {
    // 上限は**守らないと箱が落ちる**ので、開いていても切る。
    // ただし**開いていないものから先に**切る(使っている人を最後まで残す)。
    const alive = entries.filter(([sid]) => !doomed.has(sid))
      .sort((a, b) => (a[2] === 0 ? 0 : 1) - (b[2] === 0 ? 0 : 1) || a[1] - b[1]);
    for (const [sid] of alive.slice(0, alive.length - maxSessions)) doomed.add(sid);
  }

  // 古い順に返す（ログが読みやすい）
  return entries.filter(([sid]) => doomed.has(sid)).sort((a, b) => a[1] - b[1]).map(([sid]) => sid);
}

/**
 * セッション数の上限を、**接続数から決める**。
 *
 * 1 セッションは backend ごとに接続を 1 本ずつ持つので、
 * 本当のコストは「セッション数 × backend 数」。セッション数で上限を置くと、
 * backend が増えたときに黙って壊れる(実際に backend 79 件で 15,800 接続になった)。
 *
 * @param {number} explicit 明示された maxSessions(0 なら未指定)
 * @param {number} maxConns 許す接続数
 * @param {number} backends いま繋いでいる backend の数
 * @returns {number} 0 なら上限なし
 */
export function sessionCap(explicit, maxConns, backends) {
  if (explicit > 0) return explicit;
  if (!(maxConns > 0)) return 0;
  // backend が 0 でも 1 本は使う勘定にする(0 除算を作らない)
  const per = Math.max(1, Number(backends) || 1);
  // 最低 4 は残す。**上限のせいで誰も使えない状態を作らない**
  return Math.max(4, Math.floor(maxConns / per));
}

/**
 * 定期的に回収する。返り値を呼ぶと止まる。
 * @param {object} o
 * @param {Map<string,{lastSeen:number}>} o.sessions
 * @param {(sid:string)=>void} o.close  1 件閉じる
 * @param {number} o.idleMs
 * @param {number} o.sweepMs
 * @param {number} o.maxSessions
 * @param {(n:number, sids:string[])=>void} [o.onReap] 掃除したときに呼ばれる（ログ用）
 */
export function startSessionReaper({ sessions, close, idleMs, sweepMs, maxSessions, onReap }) {
  if (sweepMs <= 0) return () => {};
  const timer = setInterval(() => {
    // 関数で渡されたら毎回評価する(backend 数は動く)
    const cap = typeof maxSessions === 'function' ? maxSessions() : maxSessions;
    const sids = pickExpired(sessions, { now: Date.now(), idleMs, maxSessions: cap });
    if (!sids.length) return;
    for (const sid of sids) {
      try { close(sid); } catch { /* 1 件の失敗で掃除を止めない */ }
    }
    // **黙って閉じない。** 使っていた人には「消えた」ように見えるので、理由を残す
    onReap?.(sids.length, sids);
  }, sweepMs);
  timer.unref?.();
  return () => clearInterval(timer);
}
