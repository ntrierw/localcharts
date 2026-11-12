// Indicator catalog. Each entry describes its settings and how to turn bars into things to draw.
//
// compute(bars, params) returns any of:
//   lines:      [{ id, name, values[], color, width, colors[] (per-bar color), dots: true, dashed: true, legend: false }]
//   histograms: [{ id, name, values[], color, colors[], ownScale: true }]
//   hlines:     [{ price, color, dashed }]
//   markers:    [{ index, position: 'aboveBar'|'belowBar'|'inBar', shape, color, text }]
//   segments:   [{ i1, v1, i2, v2, kind, color, dashed }]   (straight lines, e.g. divergences)
//   range:      { min, max }                                    (fixed scale for the pane)
//
// To add your own indicator, add an entry here. Param types: int, float, bool, color, select.

const COLORS = { blue: '#2962ff', red: '#ef5350', green: '#26a69a', orange: '#ff9800', purple: '#9c27b0',
  aqua: '#00bcd4', lime: '#00e676', gray: '#787b86', text: '#b2b5be' };

const SOURCES = ['close', 'open', 'high', 'low', 'hl2', 'hlc3', 'ohlc4'];
const src = (bars, s) => bars.map(b => s === 'hl2' ? (b.high + b.low) / 2 : s === 'hlc3' ? (b.high + b.low + b.close) / 3 :
  s === 'ohlc4' ? (b.open + b.high + b.low + b.close) / 4 : b[s] ?? b.close);

const REGISTRY = {
  volume: {
    name: 'Volume', pane: 'price', desc: 'Volume bars along the bottom of the price chart',
    params: [],
    compute: bars => ({ histograms: [{ id: 'vol', name: 'Vol', ownScale: true, values: bars.map(b => b.volume),
      colors: bars.map(b => b.close >= b.open ? 'rgba(38,166,154,.35)' : 'rgba(239,83,80,.35)') }] }),
  },

  sma: {
    name: 'Moving Average (SMA)', pane: 'price', desc: 'Simple moving average',
    params: [{ key: 'length', label: 'Length', type: 'int', def: 20 },
      { key: 'source', label: 'Source', type: 'select', options: SOURCES, def: 'close' },
      { key: 'color', label: 'Color', type: 'color', def: COLORS.orange }, { key: 'width', label: 'Width', type: 'int', def: 2 }],
    title: p => `SMA ${p.length}`,
    compute: (bars, p) => ({ lines: [{ id: 'sma', name: `SMA ${p.length}`, values: Ind.sma(src(bars, p.source), p.length), color: p.color, width: p.width }] }),
  },

  ema: {
    name: 'Moving Average (EMA)', pane: 'price', desc: 'Exponential moving average',
    params: [{ key: 'length', label: 'Length', type: 'int', def: 21 },
      { key: 'source', label: 'Source', type: 'select', options: SOURCES, def: 'close' },
      { key: 'color', label: 'Color', type: 'color', def: COLORS.purple }, { key: 'width', label: 'Width', type: 'int', def: 2 }],
    title: p => `EMA ${p.length}`,
    compute: (bars, p) => ({ lines: [{ id: 'ema', name: `EMA ${p.length}`, values: Ind.ema(src(bars, p.source), p.length), color: p.color, width: p.width }] }),
  },

  bb: {
    name: 'Bollinger Bands', pane: 'price', desc: 'SMA with standard-deviation bands',
    params: [{ key: 'length', label: 'Length', type: 'int', def: 20 }, { key: 'mult', label: 'StdDev', type: 'float', def: 2 },
      { key: 'color', label: 'Color', type: 'color', def: '#5d9cec' }],
    title: p => `BB ${p.length} ${p.mult}`,
    compute: (bars, p) => {
      const c = bars.map(b => b.close), m = Ind.sma(c, p.length), sd = Ind.stdev(c, p.length);
      return { lines: [
        { id: 'mid', name: 'Basis', values: m, color: p.color },
        { id: 'up', name: 'Upper', values: m.map((v, i) => v == null ? null : v + p.mult * sd[i]), color: p.color },
        { id: 'lo', name: 'Lower', values: m.map((v, i) => v == null ? null : v - p.mult * sd[i]), color: p.color }] };
    },
  },

  atrBands: {
    name: 'ATR Bands + %R flags (ATR+ADMF part 1)', pane: 'price',
    desc: 'Your ATR/WPR script: money line, buy/sell ATR bands and the buy/sell flags',
    params: [{ key: 'maLength', label: 'MA length', type: 'int', def: 10 }, { key: 'wprLength', label: 'William %R length', type: 'int', def: 10 },
      { key: 'buyMul', label: 'Buy ATR multiplier', type: 'float', def: 1.8 }, { key: 'sellMul', label: 'Sell ATR multiplier', type: 'float', def: 1.8 },
      { key: 'flags', label: 'Show buy/sell flags', type: 'bool', def: true }],
    title: p => `ATR Bands ${p.maLength} ${p.buyMul}`,
    compute: (bars, p) => {
      const r = Ind.atrBands(bars, p), markers = [];
      if (p.flags) bars.forEach((_, i) => {
        if (r.buy[i]) markers.push({ index: i, position: 'belowBar', shape: 'arrowUp', color: COLORS.green, text: '' });
        if (r.sell[i]) markers.push({ index: i, position: 'aboveBar', shape: 'arrowDown', color: COLORS.red, text: '' });
      });
      return { lines: [{ id: 'ma', name: 'MA', values: r.ma, color: COLORS.blue },
        { id: 'buy', name: 'Buy band', values: r.lower, color: COLORS.green },
        { id: 'sell', name: 'Sell band', values: r.upper, color: COLORS.red }], markers };
    },
  },

  admf: {
    name: 'ADMF Divergence (ATR+ADMF part 2)', pane: 'new',
    desc: 'Your ADMF oscillator: HIST colored by divergence (red = regular bearish), EMA 27 / EMA 7, BUY/SELL',
    params: [{ key: 'len', label: 'Length', type: 'int', def: 14 }, { key: 'lookback', label: 'Lookback for high/low', type: 'int', def: 20 },
      { key: 'priceEnable', label: 'Factor price (money flow)', type: 'bool', def: true },
      { key: 'adWeight', label: 'A/D weight (0–1)', type: 'float', def: 0 },
      { key: 'signals', label: 'Show BUY/SELL', type: 'bool', def: false }],
    title: p => `ADMF ${p.len} ${p.lookback}`,
    compute: (bars, p) => {
      const r = Ind.admf(bars, p), markers = [];
      if (p.signals) bars.forEach((_, i) => {
        if (r.buy[i]) markers.push({ index: i, position: 'belowBar', shape: 'arrowUp', color: COLORS.lime, text: 'BUY' });
        if (r.sell[i]) markers.push({ index: i, position: 'aboveBar', shape: 'arrowDown', color: COLORS.red, text: 'SELL' });
      });
      return {
        lines: [
          { id: 'hist', name: 'HIST', values: r.hist, width: 2, colors: r.color.map(c => COLORS[c]), color: COLORS.blue },
          { id: 'e27', name: 'EMA27', values: r.hist2, color: COLORS.gray },
          { id: 'e7', name: 'EMA7', values: r.hist3, color: COLORS.text,
            colors: r.hist3.map((v, i) => v == null || r.hist2[i] == null ? null : v > r.hist2[i] ? 'rgba(76,175,80,.9)' : 'rgba(255,82,82,.9)') }],
        hlines: [{ price: 0, color: COLORS.gray, dashed: true }], markers, volumeFormat: true,
      };
    },
  },

  admfDots: {
    name: 'ADMF Structure Dots (HH/HL/LH/LL)', pane: 'price', desc: 'The structure dots from your ATR+ADMF script, under the bars',
    params: [{ key: 'len', label: 'ADMF length', type: 'int', def: 14 }, { key: 'lookback', label: 'Lookback', type: 'int', def: 20 }],
    title: () => 'ADMF Dots',
    compute: (bars, p) => {
      const r = Ind.admf(bars, p);
      const mk = (k, color) => ({ id: k, name: k, values: r.dots.map(d => d[k]), color, dots: true, legend: false });
      return { lines: [mk('HH', COLORS.blue), mk('HL', COLORS.aqua), mk('LH', COLORS.orange), mk('LL', COLORS.purple)] };
    },
  },

  willy: {
    name: 'Willy %R Divergences', pane: 'new', desc: 'Your Willy_Div script: multi-length Williams %R, EMA and pivot divergences',
    params: [
      { key: 'len1On', label: 'Length on', type: 'bool', def: true }, { key: 'len1', label: 'Length', type: 'int', def: 21 },
      { key: 'len2On', label: 'Length2 on', type: 'bool', def: true }, { key: 'len2', label: 'Length2', type: 'int', def: 6 },
      { key: 'len3On', label: 'Length3 on', type: 'bool', def: false }, { key: 'len3', label: 'Length3', type: 'int', def: 50 },
      { key: 'len4On', label: 'Length4 on', type: 'bool', def: false }, { key: 'len4', label: 'Length4', type: 'int', def: 100 },
      { key: 'emaLen', label: 'EMA length', type: 'int', def: 7 },
      { key: 'emaSource', label: 'EMA smooths', type: 'select', options: ['Length', 'Length2', 'Length3', 'Length4'], def: 'Length' },
      { key: 'divOn', label: 'Show divergences', type: 'bool', def: true },
      { key: 'divSource', label: 'Divergence source', type: 'select', options: ['Length', 'Length2', 'Length3', 'Length4'], def: 'Length' },
      { key: 'lbL', label: 'Pivot lookback left', type: 'int', def: 5 }, { key: 'lbR', label: 'Pivot lookback right', type: 'int', def: 5 },
      { key: 'rangeMin', label: 'Pivot range min', type: 'int', def: 5 }, { key: 'rangeMax', label: 'Pivot range max', type: 'int', def: 60 },
      { key: 'showBull', label: 'Regular bullish', type: 'bool', def: true }, { key: 'showBear', label: 'Regular bearish', type: 'bool', def: true },
      { key: 'showHBull', label: 'Hidden bullish', type: 'bool', def: false }, { key: 'showHBear', label: 'Hidden bearish', type: 'bool', def: false },
      { key: 'ob', label: 'Overbought', type: 'int', def: -20 }, { key: 'os', label: 'Oversold', type: 'int', def: -80 }],
    title: p => `Willy ${p.len1} ${p.len2} ${p.emaLen}`,
    compute: (bars, p) => {
      const idx = s => ['Length', 'Length2', 'Length3', 'Length4'].indexOf(s) + 1;
      const r = Ind.willyDiv(bars, { ...p, emaSource: idx(p.emaSource), divSource: idx(p.divSource) });
      const lines = [];
      if (p.len1On) lines.push({ id: 'w1', name: `%R${p.len1}`, values: r.wr1, color: COLORS.text, width: 2 });
      if (p.len2On) lines.push({ id: 'w2', name: `%R${p.len2}`, values: r.wr2, color: '#1e88e5' });
      if (p.len3On) lines.push({ id: 'w3', name: `%R${p.len3}`, values: r.wr3, color: '#e65100' });
      if (p.len4On) lines.push({ id: 'w4', name: `%R${p.len4}`, values: r.wr4, color: '#8e24aa' });
      lines.push({ id: 'ema', name: `EMA${p.emaLen}`, values: r.ema, color: '#4caf50', width: 3 });
      const show = { bull: p.showBull, bear: p.showBear, hbull: p.showHBull, hbear: p.showHBear };
      const col = { bull: '#1e88e5', hbull: 'rgba(30,136,229,.6)', bear: '#e53935', hbear: 'rgba(229,57,53,.6)' };
      const label = { bull: 'Bull', hbull: 'H Bull', bear: 'Bear', hbear: 'H Bear' };
      const segments = [], markers = [];
      if (p.divOn) for (const d of r.divs) {
        if (!show[d.type] || d.fromBar == null) continue;
        segments.push({ i1: d.fromBar, v1: d.fromVal, i2: d.toBar, v2: d.toVal, kind: d.type, color: col[d.type], dashed: d.type[0] === 'h' });
        const bull = d.type.includes('bull');
        markers.push({ index: d.toBar, position: bull ? 'belowBar' : 'aboveBar', shape: bull ? 'arrowUp' : 'arrowDown', color: col[d.type], text: label[d.type] });
      }
      return { lines, segments, markers, range: { min: -100, max: 0 },
        hlines: [{ price: p.ob, color: 'rgba(204,0,0,.6)', dashed: true }, { price: p.os, color: 'rgba(46,125,50,.8)', dashed: true }] };
    },
  },

  rsi: {
    name: 'RSI', pane: 'new', desc: 'Relative Strength Index',
    params: [{ key: 'length', label: 'Length', type: 'int', def: 14 }, { key: 'ob', label: 'Overbought', type: 'int', def: 70 }, { key: 'os', label: 'Oversold', type: 'int', def: 30 }],
    title: p => `RSI ${p.length}`,
    compute: (bars, p) => ({ lines: [{ id: 'rsi', name: 'RSI', values: Ind.rsi(bars.map(b => b.close), p.length), color: '#7e57c2', width: 2 }],
      hlines: [{ price: p.ob, color: COLORS.gray, dashed: true }, { price: p.os, color: COLORS.gray, dashed: true }], range: { min: 0, max: 100 } }),
  },

  macd: {
    name: 'MACD', pane: 'new', desc: 'Moving Average Convergence Divergence',
    params: [{ key: 'fast', label: 'Fast', type: 'int', def: 12 }, { key: 'slow', label: 'Slow', type: 'int', def: 26 }, { key: 'signal', label: 'Signal', type: 'int', def: 9 }],
    title: p => `MACD ${p.fast} ${p.slow} ${p.signal}`,
    compute: (bars, p) => {
      const c = bars.map(b => b.close), f = Ind.ema(c, p.fast), s = Ind.ema(c, p.slow);
      const m = f.map((v, i) => v == null || s[i] == null ? null : v - s[i]), sig = Ind.ema(m, p.signal);
      const h = m.map((v, i) => v == null || sig[i] == null ? null : v - sig[i]);
      return { histograms: [{ id: 'h', name: 'Hist', values: h, colors: h.map((v, i) => v == null ? null : v >= 0 ? (i && v < h[i - 1] ? '#b2dfdb' : '#26a69a') : (i && v > h[i - 1] ? '#ffcdd2' : '#ef5350')) }],
        lines: [{ id: 'm', name: 'MACD', values: m, color: COLORS.blue }, { id: 's', name: 'Signal', values: sig, color: COLORS.orange }],
        hlines: [{ price: 0, color: COLORS.gray, dashed: true }] };
    },
  },
};

const DEFAULT_INDICATORS = [
  { type: 'volume' },
  { type: 'atrBands' },
  { type: 'sma', params: { length: 200, color: '#ff9800', width: 2 } },
  { type: 'admfDots' },
  { type: 'admf' },
  { type: 'willy', params: { divOn: true } },
];
