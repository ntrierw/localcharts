// Indicator math. Functions take bars ({time, open, high, low, close, volume}) or number arrays
// and return arrays of the same length (null where Pine would give na).
// Built-ins follow TradingView Pine v5 (ta.sma, ta.ema, ta.rma, ta.atr, ta.wpr, ta.pivotlow, ...).
//
// atrBands(), admf() and willyDiv() are JavaScript ports of Pine scripts published under the
// Mozilla Public License 2.0 (https://mozilla.org/MPL/2.0/):
//   "ATR with William %R Signal" (c) bobbystenly
//   "ADMF Divergence (High/Low Highlight + Alerts)" (c) stonksforlife
//   "Williams %R w/ Divergences (Multi-Length)" (Willy_Div)
// Those ported functions remain under MPL 2.0. admf() was checked bar-for-bar against
// TradingView's own output on SOXS 4h (HIST, EMAs, colors, dots and BUY/SELL all match).

const Ind = (() => {
  const nn = v => v != null && !Number.isNaN(v);

  const sma = (src, p) => src.map((_, i) => {
    if (i < p - 1) return null;
    let s = 0;
    for (let j = i - p + 1; j <= i; j++) { if (!nn(src[j])) return null; s += src[j]; }
    return s / p;
  });

  // ta.ema / ta.rma skip na inputs (they hold the last value)
  // seeded with an SMA of the first p values, like Pine (checked against TradingView's output)
  const ema = (src, p) => {
    const k = 2 / (p + 1); let e = null, n = 0, s = 0;
    return src.map(v => {
      if (!nn(v)) return e;
      if (e == null) { s += v; n++; if (n === p) e = s / p; return e; }
      return (e = e + k * (v - e));
    });
  };
  const rma = (src, p) => {
    const out = new Array(src.length).fill(null); let e = null, n = 0, s = 0;
    for (let i = 0; i < src.length; i++) {
      const v = src[i];
      if (nn(v)) {
        if (e == null) { s += v; n++; if (n === p) e = s / p; }
        else e = (e * (p - 1) + v) / p;
      }
      out[i] = e;
    }
    return out;
  };

  const stdev = (src, p) => { const m = sma(src, p); return src.map((_, i) => {
    if (m[i] == null) return null; let s = 0;
    for (let j = i - p + 1; j <= i; j++) s += (src[j] - m[i]) ** 2;
    return Math.sqrt(s / p);
  }); };

  const highest = (src, p) => src.map((_, i) => {
    if (i < p - 1) return null; let m = -Infinity;
    for (let j = i - p + 1; j <= i; j++) if (nn(src[j]) && src[j] > m) m = src[j];
    return m === -Infinity ? null : m;
  });
  const lowest = (src, p) => src.map((_, i) => {
    if (i < p - 1) return null; let m = Infinity;
    for (let j = i - p + 1; j <= i; j++) if (nn(src[j]) && src[j] < m) m = src[j];
    return m === Infinity ? null : m;
  });

  // ta.valuewhen(cond, src, occurrence)
  const valuewhen = (cond, src, occ = 0) => {
    const hist = [];
    return cond.map((c, i) => { if (c) hist.unshift(src[i]); return hist.length > occ ? hist[occ] : null; });
  };

  const trueRange = bars => bars.map((b, i) => i === 0 ? b.high - b.low :
    Math.max(b.high - b.low, Math.abs(b.high - bars[i - 1].close), Math.abs(b.low - bars[i - 1].close)));
  const atr = (bars, p) => rma(trueRange(bars), p);

  // Williams %R (0 = top of range, -100 = bottom). Same formula as ta.wpr and your Willy_Div script.
  const wpr = (bars, p, src) => bars.map((b, i) => {
    if (i < p - 1) return null;
    let hh = -Infinity, ll = Infinity;
    for (let j = i - p + 1; j <= i; j++) { hh = Math.max(hh, bars[j].high); ll = Math.min(ll, bars[j].low); }
    return hh === ll ? null : 100 * ((src ? src[i] : b.close) - hh) / (hh - ll);
  });

  const rsi = (src, p) => {
    const up = src.map((v, i) => i ? Math.max(v - src[i - 1], 0) : null);
    const dn = src.map((v, i) => i ? Math.max(src[i - 1] - v, 0) : null);
    const u = rma(up, p), d = rma(dn, p);
    return u.map((x, i) => x == null || d[i] == null ? null : d[i] === 0 ? 100 : x === 0 ? 0 : 100 - 100 / (1 + x / d[i]));
  };

  // ta.pivotlow / ta.pivothigh: value of the pivot, reported lbR bars after it (on the confirming bar)
  const pivot = (src, lbL, lbR, low) => src.map((_, i) => {
    const c = i - lbR; if (c - lbL < 0 || !nn(src[c])) return null;
    for (let j = c - lbL; j <= i; j++) {
      if (j === c) continue; if (!nn(src[j])) return null;
      if (j < c ? (low ? src[j] <= src[c] : src[j] >= src[c]) : (low ? src[j] < src[c] : src[j] > src[c])) return null;
    }
    return src[c];
  });

  const barssince = cond => { let last = null; return cond.map((c, i) => { if (c) last = i; return last == null ? null : i - last; }); };

  // ---------------------------------------------------------------- ports of your scripts

  // ATR/WPR section of "ATR+ADMF" (bands on the price chart + buy/sell flags)
  function atrBands(bars, p = {}) {
    const { maLength = 10, wprLength = 10, buyMul = 1.8, sellMul = 1.8 } = p;
    const ma = sma(bars.map(b => b.close), maLength), a = atr(bars, maLength), w = wpr(bars, wprLength);
    const lower = ma.map((m, i) => m == null || a[i] == null ? null : m - buyMul * a[i]);
    const upper = ma.map((m, i) => m == null || a[i] == null ? null : m + sellMul * a[i]);
    const buy = bars.map((b, i) => i > 0 && lower[i] != null && lower[i] > b.open && w[i] < -80 && w[i - 1] < -95 && b.close > b.open);
    const sell = bars.map((b, i) => i > 0 && upper[i] != null && upper[i] < b.close && w[i] > -20 && w[i - 1] > -5);
    return { ma, lower, upper, wpr: w, buy, sell };
  }

  // ADMF Divergence section of "ATR+ADMF"
  function admf(bars, p = {}) {
    const { len = 14, lookback = 20, priceEnable = true, adWeight = 0 } = p;
    const n = bars.length, x = new Array(n).fill(null);
    for (let i = 1; i < n; i++) {
      const b = bars[i], c1 = bars[i - 1].close;
      const tr = Math.max(b.high - b.low, Math.abs(b.high - c1), Math.abs(b.low - c1));
      if (tr === 0) continue;                               // change / 0 is na in Pine
      let r = (b.close - c1) / tr;
      r = (1 - adWeight) * r + Math.sign(r) * adWeight;
      const trl = Math.min(b.low, c1), trh = Math.max(b.high, c1);
      const vol = priceEnable ? b.volume * (c1 + b.close + trl + trh) / 4 : b.volume;
      x[i] = vol * r;
    }
    const hist = rma(x, len), hist2 = ema(hist, 27), hist3 = ema(hist, 7);
    const H = bars.map(b => b.high), L = bars.map(b => b.low);
    const ph = highest(H, lookback), pl = lowest(L, lookback), hh = highest(hist, lookback), hl = lowest(hist, lookback);
    const prev = a => a.map((_, i) => i ? a[i - 1] : null);
    const phPrev = valuewhen(H.map((v, i) => ph[i] != null && v === ph[i]), prev(ph), 1);
    const plPrev = valuewhen(L.map((v, i) => pl[i] != null && v === pl[i]), prev(pl), 1);
    const hhPrev = valuewhen(hist.map((v, i) => hh[i] != null && v === hh[i]), prev(hh), 1);
    const hlPrev = valuewhen(hist.map((v, i) => hl[i] != null && v === hl[i]), prev(hl), 1);
    const gt = (a, b) => nn(a) && nn(b) && a > b, lt = (a, b) => nn(a) && nn(b) && a < b;

    const color = [], dots = [], buy = [], sell = [];
    for (let i = 0; i < n; i++) {
      const hi = H[i], lo = L[i], h = hist[i];
      const regBear = gt(hi, phPrev[i]) && lt(h, hhPrev[i]), regBull = lt(lo, plPrev[i]) && gt(h, hlPrev[i]);
      const hidBear = lt(hi, phPrev[i]) && gt(h, hhPrev[i]), hidBull = gt(lo, plPrev[i]) && lt(h, hlPrev[i]);
      color.push(regBear ? 'red' : hidBear ? 'orange' : regBull ? 'lime' : hidBull ? 'aqua' : 'blue');
      const r = hi - lo, base = lo - r * 0.05;
      dots.push({
        HH: gt(hi, phPrev[i]) && gt(h, hhPrev[i]) ? base : null,
        HL: gt(lo, plPrev[i]) && gt(h, hlPrev[i]) ? base - r * 0.05 : null,
        LH: lt(hi, phPrev[i]) && lt(h, hhPrev[i]) ? base - r * 0.10 : null,
        LL: lt(lo, plPrev[i]) && lt(h, hlPrev[i]) ? base - r * 0.15 : null,
      });
      const cross = (a, b) => i > 0 && gt(a[i], b[i]) && nn(a[i - 1]) && nn(b[i - 1]) && a[i - 1] <= b[i - 1];
      buy.push(cross(hist, hist3)); sell.push(cross(hist3, hist2));
    }
    return { hist, hist2, hist3, color, dots, buy, sell };
  }

  // Your "Willy_Div" script: up to four %R lengths, an EMA, and pivot divergences
  function willyDiv(bars, p = {}) {
    const { len1 = 21, len2 = 6, len3 = 50, len4 = 100, len1On = true, len2On = true, len3On = false, len4On = false,
      emaLen = 7, emaSource = 1, divSource = 1, lbL = 5, lbR = 5, rangeMin = 5, rangeMax = 60 } = p;
    const none = bars.map(() => null);
    const wr = [null, len1On ? wpr(bars, len1) : none, len2On ? wpr(bars, len2) : none,
      len3On ? wpr(bars, len3) : none, len4On ? wpr(bars, len4) : none];
    const emaV = ema(wr[emaSource], emaLen).map((v, i) => nn(wr[emaSource][i]) ? v : null);
    const ds = wr[divSource];
    const pl = pivot(ds, lbL, lbR, true).map(nn), ph = pivot(ds, lbL, lbR, false).map(nn);
    const shift = (a, k) => a.map((_, i) => i - k >= 0 ? a[i - k] : null);
    const dsR = shift(ds, lbR), lowR = shift(bars.map(b => b.low), lbR), highR = shift(bars.map(b => b.high), lbR);
    const inRange = c => barssince(shift(c, 1).map(Boolean)).map(b => b != null && rangeMin <= b && b <= rangeMax);
    const plIn = inRange(pl), phIn = inRange(ph);
    const plOsc = valuewhen(pl, dsR, 1), plLow = valuewhen(pl, lowR, 1), phOsc = valuewhen(ph, dsR, 1), phHigh = valuewhen(ph, highR, 1);
    const plBar = valuewhen(pl, bars.map((_, i) => i - lbR), 1), phBar = valuewhen(ph, bars.map((_, i) => i - lbR), 1);
    const divs = [];
    for (let i = 0; i < bars.length; i++) {
      const add = (type, fromBar, fromVal) => divs.push({ type, confirm: i, fromBar, fromVal, toBar: i - lbR, toVal: dsR[i] });
      if (pl[i] && plIn[i]) {
        if (dsR[i] > plOsc[i] && lowR[i] < plLow[i]) add('bull', plBar[i], plOsc[i]);
        if (dsR[i] < plOsc[i] && lowR[i] > plLow[i]) add('hbull', plBar[i], plOsc[i]);
      }
      if (ph[i] && phIn[i]) {
        if (dsR[i] < phOsc[i] && highR[i] > phHigh[i]) add('bear', phBar[i], phOsc[i]);
        if (dsR[i] > phOsc[i] && highR[i] < phHigh[i]) add('hbear', phBar[i], phOsc[i]);
      }
    }
    return { wr1: wr[1], wr2: wr[2], wr3: wr[3], wr4: wr[4], ema: emaV, divs };
  }

  // Shortcuts the strategies use
  const bands = (bars, len = 10, mult = 1.8) => {
    const r = atrBands(bars, { maLength: len, buyMul: mult, sellMul: mult });
    return { ma: r.ma, upper: r.upper, lower: r.lower };
  };
  const willy = bars => { const w = willyDiv(bars); return { wr: w.wr1, wrFast: w.wr2, ema: w.ema }; };
  const admfColors = bars => admf(bars).color;

  return { sma, ema, rma, stdev, highest, lowest, valuewhen, atr, wpr, rsi, pivot, barssince,
           atrBands, admf, willyDiv, bands, willy, admfColors };
})();
