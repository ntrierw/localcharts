// Strategies. Signals are read at a bar's close and filled at the NEXT bar's open,
// the same way TradingView's strategy tester and the earlier backtest worked.

const Strat = (() => {
  const COST = 0.0005; // 0.05% per side

  function stats(trades, curve, bars, startIdx) {
    let pk = 1, dd = 0;
    for (const e of curve) { pk = Math.max(pk, e); dd = Math.max(dd, 1 - e / pk); }
    const eq = curve.length ? curve[curve.length - 1] : 1;
    const years = (bars[bars.length - 1].time - bars[startIdx].time) / 31557600;
    const wins = trades.filter(t => t.ret > 0);
    const bh = bars[bars.length - 1].close / bars[startIdx].open;
    let bpk = bars[startIdx].open, bdd = 0;
    for (let i = startIdx; i < bars.length; i++) { bpk = Math.max(bpk, bars[i].close); bdd = Math.max(bdd, 1 - bars[i].close / bpk); }
    return {
      totalPct: (eq - 1) * 100,
      cagrPct: years > 0.2 ? (Math.pow(eq, 1 / years) - 1) * 100 : null,
      maxDDPct: dd * 100,
      trades: trades.length,
      winPct: trades.length ? wins.length / trades.length * 100 : null,
      avgPct: trades.length ? trades.reduce((a, t) => a + t.ret, 0) / trades.length * 100 : null,
      bhPct: (bh - 1) * 100, bhDDPct: bdd * 100,
      from: bars[startIdx].time, to: bars[bars.length - 1].time,
    };
  }

  // ------------------------------------------------------------------ SOXL Red-Light Trend
  // Hold SOXL when:  no red ATR+ADMF bar (on SOXS) in the last `redWait`+1 bars
  //             AND (SOXL close > SMA(trendLen)  OR  SOXL close < lower band  [washout])
  function redLightTrend(soxl, soxs, opts = {}) {
    const { trendLen = 200, redWait = 2, manualRed = false } = opts;
    const n = soxl.length;
    const b = Ind.bands(soxl);
    const trend = Ind.sma(soxl.map(x => x.close), trendLen);

    const colors = soxs ? Ind.admfColors(soxs) : null;
    const haveAdmf = Array.isArray(colors);
    const redByTime = new Map();
    if (haveAdmf) soxs.forEach((x, i) => redByTime.set(x.time, colors[i] === 'red'));
    const isRed = i => haveAdmf ? !!redByTime.get(soxl[i].time) : false;

    const state = new Array(n).fill(null);
    for (let i = 0; i < n; i++) {
      if (trend[i] == null || b.lower[i] == null) continue;
      let red = false;
      for (let j = Math.max(0, i - redWait); j <= i; j++) if (isRed(j)) red = true;
      if (!haveAdmf && i === n - 1) red = manualRed;      // manual switch only affects "now"
      const inTrend = soxl[i].close > trend[i];
      const washout = soxl[i].close < b.lower[i];
      state[i] = { hold: !red && (inTrend || washout), red, inTrend, washout,
                   trend: trend[i], lower: b.lower[i], ma: b.ma[i] };
    }

    // backtest: decision at close i -> position during open[i+1] .. open[i+2]
    const startIdx = state.findIndex(s => s);
    const trades = [], curve = [], markers = [];
    let eq = 1, pos = 0, cur = null;
    for (let i = startIdx; i < n - 1; i++) {
      const want = state[i].hold ? 1 : 0;
      if (want !== pos) {
        eq *= 1 - COST;
        if (want) { cur = { entryTime: soxl[i + 1].time, entry: soxl[i + 1].open };
          markers.push({ time: soxl[i + 1].time, position: 'belowBar', color: '#26a69a', shape: 'arrowUp', text: 'BUY' }); }
        else { cur.exitTime = soxl[i + 1].time; cur.exit = soxl[i + 1].open;
          cur.ret = cur.exit / cur.entry * (1 - COST) ** 2 - 1; trades.push(cur); cur = null;
          markers.push({ time: soxl[i + 1].time, position: 'aboveBar', color: '#ef5350', shape: 'arrowDown', text: 'SELL' }); }
        pos = want;
      }
      const nxt = i + 2 < n ? soxl[i + 2].open : soxl[n - 1].close;
      if (pos) eq *= nxt / soxl[i + 1].open;
      curve.push(eq);
    }
    if (cur) { cur.exit = soxl[n - 1].close; cur.ret = cur.exit / cur.entry * (1 - COST) - 1; cur.open = true; trades.push(cur); }

    return { state, trades, markers, haveAdmf, stats: startIdx >= 0 ? stats(trades, curve, soxl, startIdx) : null,
             now: state[n - 1], bandsLower: b.lower, trendLine: trend };
  }

  // ------------------------------------------------------------------ SOXS Spike Fade (short SOXS)
  // Entry: close > money line AND Willy EMA turns down.  Exit: close < lower band, or
  // close < money line with %R(21) < -80, or after 40 bars.  Shown for information: needs shorting.
  function spikeFade(soxs, opts = {}) {
    const { maxBars = 40 } = opts;
    const n = soxs.length, b = Ind.bands(soxs), w = Ind.willy(soxs);
    const entry = i => i >= 2 && b.ma[i] != null && w.ema[i - 2] != null &&
      soxs[i].close > b.ma[i] && w.ema[i] < w.ema[i - 1] && w.ema[i - 1] >= w.ema[i - 2];
    const exit = j => soxs[j].close < b.lower[j] || (soxs[j].close < b.ma[j] && w.wr[j] < -80);
    const trades = [], markers = [], curve = [];
    let eq = 1, i = 30;
    const startIdx = 30;
    while (i < n - 1) {
      if (!entry(i)) { i++; continue; }
      const e = i + 1, px = soxs[e].open; let j = e, xp = null;
      for (; j < n - 1; j++) { if (j - e + 1 >= maxBars || exit(j)) { xp = soxs[j + 1].open; j++; break; } }
      const open = xp == null; if (open) { xp = soxs[n - 1].close; j = n - 1; }
      const ret = (1 - xp / px) - 2 * COST;
      trades.push({ entryTime: soxs[e].time, entry: px, exitTime: soxs[j].time, exit: xp, ret, open });
      markers.push({ time: soxs[e].time, position: 'aboveBar', color: '#ff9800', shape: 'arrowDown', text: 'FADE' });
      if (!open) markers.push({ time: soxs[j].time, position: 'belowBar', color: '#9e9e9e', shape: 'circle', text: 'cover' });
      eq *= 1 + ret; curve.push(eq); i = j;
    }
    return { trades, markers, stats: n > startIdx ? stats(trades, curve, soxs, startIdx) : null,
             signalNow: entry(n - 1) };
  }

  return { redLightTrend, spikeFade };
})();
