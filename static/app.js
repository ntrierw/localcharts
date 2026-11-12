// Local Charts front end: panes, indicators, watchlist, strategy panel
const LC = LightweightCharts;
const REFRESH_MS = 30000;

const store = {
  get(k, d) { try { const v = localStorage.getItem('lc.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('lc.' + k, JSON.stringify(v)); } catch {} },
};
const $ = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const fmt = (v, d = 2) => v == null || isNaN(v) ? '–' : Number(v).toFixed(d);
const fmtV = v => { if (v == null || isNaN(v)) return '–'; const a = Math.abs(v);
  return a >= 1e9 ? (v / 1e9).toFixed(2) + 'B' : a >= 1e6 ? (v / 1e6).toFixed(2) + 'M' : a >= 1e4 ? (v / 1e3).toFixed(1) + 'K' : v.toFixed(2); };
const pct = (v, d = 1) => v == null || isNaN(v) ? '–' : (v >= 0 ? '+' : '') + v.toFixed(d) + '%';
const cls = v => v > 0 ? 'up' : v < 0 ? 'down' : '';
const dstr = t => new Date(t * 1000).toISOString().slice(0, 16).replace('T', ' ');
// Bar times are New York wall-clock time stored as if it were UTC. This is "now" in the same form.
function nyNow() {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
    .formatToParts(new Date()).map(x => [x.type, x.value]));
  return Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second) / 1000;
}
// Index of the last 1h bar that has finished (the 15:30 bar ends at the 16:00 close).
function lastClosed1h(bars, now = nyNow()) {
  for (let i = bars.length - 1; i >= 0; i--) {
    const t = bars[i].time, end = Math.min(t + 3600, t - (t % 86400) + 16 * 3600);
    if (end <= now) return i;
  }
  return -1;
}
async function api(path) {
  const r = await fetch(path); const j = await r.json();
  if (!r.ok || j.error) throw new Error(j.error || r.statusText);
  return j;
}

// ------------------------------------------------------------------ state
let uidSeq = Date.now();
const newInst = (type, params = {}) => {
  const def = REGISTRY[type]; const p = {};
  def.params.forEach(x => p[x.key] = x.def);
  return { uid: 'i' + (uidSeq++), type, params: { ...p, ...params }, visible: true };
};
const loadInds = () => {
  const saved = store.get('inds', null);
  const list = (saved || DEFAULT_INDICATORS).filter(x => REGISTRY[x.type]);
  return list.map(x => { const i = newInst(x.type, x.params); if (x.visible === false) i.visible = false; return i; });
};
const S = {
  symbol: store.get('symbol', 'SOXL'), tf: store.get('tf', '4h'),
  watch: store.get('watch', ['SOXL', 'SOXS', 'SOXX', 'SMH', 'NVDA', 'SPY', 'QQQ']),
  inds: loadInds(), bars: [], lastCall: null,
};
const saveInds = () => store.set('inds', S.inds.map(i => ({ type: i.type, params: i.params, visible: i.visible })));

// ------------------------------------------------------------------ panes
const chartOpts = {
  autoSize: true,
  layout: { background: { type: 'solid', color: '#131722' }, textColor: '#d1d4dc', fontSize: 12 },
  grid: { vertLines: { color: '#1e222d' }, horzLines: { color: '#1e222d' } },
  rightPriceScale: { borderColor: '#2a2e39', minimumWidth: 78 },
  timeScale: { borderColor: '#2a2e39', timeVisible: true, secondsVisible: false, rightOffset: 6 },
  crosshair: { mode: LC.CrosshairMode.Normal },
};
let panes = [];          // { el, legend, chart, isPrice, inst, anchor }
let price = null;        // the price pane
let candles = null, bgSeries = null;
const idxByTime = new Map();
let syncing = false;

function makePane(isPrice, inst) {
  const el = document.createElement('div'); el.className = 'pane' + (isPrice ? ' price-pane' : '');
  const legend = document.createElement('div'); legend.className = 'legend';
  const box = document.createElement('div'); box.className = 'chart';
  el.append(legend, box); $('panes').append(el);
  const chart = LC.createChart(box, { ...chartOpts, layout: { ...chartOpts.layout, attributionLogo: isPrice } });
  const pane = { el, legend, chart, isPrice, inst, anchor: null };
  chart.timeScale().subscribeVisibleLogicalRangeChange(r => {
    if (syncing || !r) return; syncing = true;
    panes.forEach(p => p !== pane && p.chart.timeScale().setVisibleLogicalRange(r)); syncing = false;
  });
  chart.subscribeCrosshairMove(ev => onCrosshair(pane, ev));
  panes.push(pane);
  return pane;
}

function seriesFor(pane, inst, out) {
  const ser = { lines: {}, hists: {}, segs: [], hlines: [] };
  const fixed = out.range ? () => ({ priceRange: { minValue: out.range.min, maxValue: out.range.max } }) : undefined;
  const pf = out.volumeFormat ? { priceFormat: { type: 'volume' } } : {};
  for (const h of out.histograms || []) {
    const s = pane.chart.addHistogramSeries({ color: h.color || '#5d606b', priceLineVisible: false, lastValueVisible: !h.ownScale,
      priceScaleId: h.ownScale ? inst.uid + h.id : 'right', autoscaleInfoProvider: fixed, ...(h.ownScale ? { priceFormat: { type: 'volume' } } : pf) });
    if (h.ownScale) pane.chart.priceScale(inst.uid + h.id).applyOptions({ scaleMargins: { top: 0.84, bottom: 0 }, visible: false });
    ser.hists[h.id] = s;
  }
  for (const l of out.lines || []) {
    const s = pane.chart.addLineSeries({ color: l.color, lineWidth: l.width || 1, lineStyle: l.dashed ? 2 : 0,
      lineVisible: !l.dots, pointMarkersVisible: !!l.dots, pointMarkersRadius: 2.5,
      priceLineVisible: false, lastValueVisible: !l.dots, crosshairMarkerVisible: false, autoscaleInfoProvider: fixed, ...pf });
    ser.lines[l.id] = s;
  }
  const first = Object.values(ser.lines)[0] || Object.values(ser.hists)[0];
  for (const h of out.hlines || []) if (first) ser.hlines.push(first.createPriceLine({ price: h.price, color: h.color, lineWidth: 1, lineStyle: h.dashed ? 2 : 0, axisLabelVisible: false }));
  ser.first = first; ser.fixed = fixed;
  return ser;
}

function rebuild() {
  const range = price ? price.chart.timeScale().getVisibleLogicalRange() : null;
  panes.forEach(p => p.chart.remove()); panes = []; $('panes').innerHTML = '';
  price = makePane(true, null);
  bgSeries = price.chart.addHistogramSeries({ priceScaleId: 'bg', priceLineVisible: false, lastValueVisible: false });
  price.chart.priceScale('bg').applyOptions({ scaleMargins: { top: 0, bottom: 0 }, visible: false });
  candles = price.chart.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350', borderVisible: false, wickUpColor: '#26a69a', wickDownColor: '#ef5350' });
  price.anchor = candles;
  const bars = S.bars;
  for (const inst of S.inds) {
    inst.ser = null; inst.pane = null;
    if (!inst.visible || !bars.length) continue;
    const def = REGISTRY[inst.type];
    let out; try { out = def.compute(bars, inst.params); } catch (e) { console.error(def.name, e); continue; }
    const pane = def.pane === 'price' ? price : makePane(false, inst);
    inst.pane = pane; inst.ser = seriesFor(pane, inst, out);
    if (!pane.isPrice) pane.anchor = inst.ser.first;
  }
  panes.forEach((p, k) => p.chart.applyOptions({ timeScale: { visible: k === panes.length - 1, timeVisible: !['1D', '1W'].includes(S.tf) } }));
  draw();
  const n = bars.length;
  const r = range || { from: Math.max(0, n - 220), to: n + 6 };
  panes.forEach(p => p.chart.timeScale().setVisibleLogicalRange(r));
}

const toLine = (bars, vals, colors) => bars.map((b, i) => vals[i] == null || isNaN(vals[i]) ? { time: b.time } :
  (colors && colors[i] ? { time: b.time, value: vals[i], color: colors[i] } : { time: b.time, value: vals[i] }));

// Straight lines (divergences). One tiny 2-point series per segment: line series can't leave gaps.
function drawSegments(inst, segs) {
  const bars = S.bars, chart = inst.pane.chart;
  (inst.ser.segs || []).forEach(x => chart.removeSeries(x));
  inst.ser.segs = [];
  for (const s of segs) {
    if (s.i1 < 0 || s.i2 >= bars.length || s.i1 >= s.i2 || s.v1 == null || s.v2 == null) continue;
    const ser = chart.addLineSeries({ color: s.color, lineWidth: 2, lineStyle: s.dashed ? 2 : 0, priceLineVisible: false,
      lastValueVisible: false, crosshairMarkerVisible: false, autoscaleInfoProvider: inst.ser.fixed });
    ser.setData([{ time: bars[s.i1].time, value: s.v1 }, { time: bars[s.i2].time, value: s.v2 }]);
    inst.ser.segs.push(ser);
  }
}

let priceMarkers = [];
function draw() {
  const bars = S.bars; if (!candles) return;
  idxByTime.clear(); bars.forEach((b, i) => idxByTime.set(b.time, i));
  candles.setData(bars);
  priceMarkers = [];
  for (const inst of S.inds) {
    if (!inst.ser) continue;
    let out; try { out = REGISTRY[inst.type].compute(bars, inst.params); } catch (e) { console.error(e); continue; }
    inst.out = out;
    for (const l of out.lines || []) inst.ser.lines[l.id]?.setData(toLine(bars, l.values, l.colors));
    for (const h of out.histograms || []) inst.ser.hists[h.id]?.setData(toLine(bars, h.values, h.colors));
    if (out.segments) drawSegments(inst, out.segments);
    const mk = (out.markers || []).filter(m => bars[m.index]).map(m => ({ time: bars[m.index].time, position: m.position, shape: m.shape, color: m.color, text: m.text }));
    if (inst.pane.isPrice) priceMarkers.push(...mk);
    else if (inst.ser.first) inst.ser.first.setMarkers(mk.sort((a, b) => a.time - b.time));
  }
  applyOverlays();
  legendAt(bars.length - 1);
}

// ------------------------------------------------------------------ legends + crosshair
function legendAt(i) {
  const b = S.bars[i]; if (!b || !price) return;
  const prev = S.bars[i - 1], ch = prev ? (b.close / prev.close - 1) * 100 : 0, c = cls(b.close - b.open);
  const btns = inst => `<span class="lb" data-act="edit" data-uid="${inst.uid}" title="Settings">⚙</span>` +
    `<span class="lb" data-act="hide" data-uid="${inst.uid}" title="Hide">–</span><span class="lb" data-act="del" data-uid="${inst.uid}" title="Remove">✕</span>`;
  const vals = inst => (inst.out?.lines || []).filter(l => l.legend !== false).map(l =>
    `<span style="color:${(l.colors && l.colors[i]) || l.color}">${fmtV(l.values[i])}</span>`).join(' ');
  const row = inst => `<div class="lrow"><span class="lname">${esc(REGISTRY[inst.type].title ? REGISTRY[inst.type].title(inst.params) : REGISTRY[inst.type].name)}</span> ${vals(inst)} ${btns(inst)}</div>`;
  price.legend.innerHTML =
    `<div class="lrow"><b>${esc(S.symbol)}</b> <span class="muted">· ${S.tf}</span> ` +
    `<span class="k">O</span><span class="${c}">${fmt(b.open)}</span><span class="k">H</span><span class="${c}">${fmt(b.high)}</span>` +
    `<span class="k">L</span><span class="${c}">${fmt(b.low)}</span><span class="k">C</span><span class="${c}">${fmt(b.close)}</span>` +
    ` <span class="${cls(ch)}">${pct(ch, 2)}</span></div>` +
    S.inds.filter(x => x.pane === price && x.type !== 'volume').map(row).join('');
  panes.filter(p => !p.isPrice).forEach(p => { p.legend.innerHTML = row(p.inst); });
}
function anchorValue(pane, i) {
  if (pane.isPrice) return S.bars[i]?.close;
  const l = pane.inst.out?.lines?.[0] || pane.inst.out?.histograms?.[0];
  return l ? l.values[i] : null;
}
function onCrosshair(src, ev) {
  if (syncing) return;
  const i = ev.time != null ? idxByTime.get(ev.time) : null;
  legendAt(i == null ? S.bars.length - 1 : i);
  syncing = true;
  for (const p of panes) {
    if (p === src || !p.anchor) continue;
    const v = i == null ? null : anchorValue(p, i);
    if (v == null || isNaN(v)) p.chart.clearCrosshairPosition(); else p.chart.setCrosshairPosition(v, ev.time, p.anchor);
  }
  syncing = false;
}
$('panes').addEventListener('click', e => {
  const act = e.target.dataset.act, inst = S.inds.find(x => x.uid === e.target.dataset.uid);
  if (!act || !inst) return;
  if (act === 'edit') openSettings(inst);
  if (act === 'hide') { inst.visible = false; saveInds(); rebuild(); }
  if (act === 'del') { S.inds = S.inds.filter(x => x !== inst); saveInds(); rebuild(); }
});

// ------------------------------------------------------------------ strategy overlays on the price chart
let lastRed = null, lastFade = null, lastPiv = null;
function applyOverlays() {
  if (!candles) return;
  const sig = $('tgSignals').checked;
  let mk = [], bg = [];
  if (sig && S.tf === '1h' && S.symbol === 'SOXL' && lastPiv) {
    mk = lastPiv.markers;
    bg = S.bars.map(b => { const st = lastPiv.stateByTime.get(b.time);
      return st && st.hold ? { time: b.time, value: 1, color: 'rgba(38,166,154,.10)' } : { time: b.time }; });
  } else if (sig && S.tf === '4h' && S.symbol === 'SOXL' && lastRed) {
    mk = lastRed.markers;
    bg = S.bars.map(b => { const st = lastRed.stateByTime.get(b.time);
      return st && st.hold ? { time: b.time, value: 1, color: st.washout && !st.inTrend ? 'rgba(0,188,212,.10)' : 'rgba(38,166,154,.10)' } : { time: b.time }; });
  } else if (sig && S.tf === '4h' && S.symbol === 'SOXS' && lastFade) mk = lastFade.markers;
  const have = new Set(S.bars.map(b => b.time));
  candles.setMarkers([...priceMarkers, ...mk.filter(m => have.has(m.time))].sort((a, b) => a.time - b.time));
  bgSeries.setData(bg);
}

// ------------------------------------------------------------------ loading
let loadSeq = 0;
async function loadChart(full = true) {
  const seq = ++loadSeq;
  if (full) setStatus(`Loading ${S.symbol} ${S.tf}…`);
  try {
    const j = await api(`/api/bars?symbol=${encodeURIComponent(S.symbol)}&tf=${S.tf}`);
    if (seq !== loadSeq) return;
    S.bars = j.bars;
    if (full || !panes.length) { if (full) price = null; rebuild(); }   // price = null -> reset zoom to the latest bars
    else draw();
    setStatus(`${j.source} · updated ${j.updated.slice(11)} · ${j.bars.length} bars`);
  } catch (e) { if (seq === loadSeq) setStatus(e.message, true); }
}
function setStatus(t, err) { const el = $('status'); el.textContent = t; el.classList.toggle('err', !!err); }

// ------------------------------------------------------------------ strategy panel
const statRows = (st, bhLabel) => !st ? '<tr><td>Not enough data</td></tr>' :
  `<tr><td></td><td>Strategy</td><td>${bhLabel}</td></tr>` +
  `<tr><td>Total return</td><td class="${cls(st.totalPct)}">${pct(st.totalPct, 0)}</td><td class="${cls(st.bhPct)}">${pct(st.bhPct, 0)}</td></tr>` +
  `<tr><td>Max drawdown</td><td>${fmt(st.maxDDPct, 0)}%</td><td>${fmt(st.bhDDPct, 0)}%</td></tr>` +
  `<tr><td>Trades · win rate</td><td>${st.trades} · ${fmt(st.winPct, 0)}%</td><td></td></tr>` +
  `<tr><td>Avg trade</td><td class="${cls(st.avgPct)}">${pct(st.avgPct, 2)}</td><td></td></tr>` +
  `<tr><td colspan="3" class="muted small">${dstr(st.from).slice(0, 10)} → ${dstr(st.to).slice(0, 10)}</td></tr>`;
const tradeRows = trs => trs.slice(-10).reverse().map(t =>
  `<tr><td>${dstr(t.entryTime)}</td><td>${fmt(t.entry)} → ${fmt(t.exit)}${t.open ? ' <span class="muted">open</span>' : ''}</td><td class="${cls(t.ret)}">${pct(t.ret * 100)}</td></tr>`).join('') || '<tr><td class="muted">None yet</td></tr>';

const COLOR_NAME = { red: 'red', orange: 'orange', lime: 'lime', aqua: 'aqua', blue: 'blue (no divergence)' };
const hm = t => dstr(t).slice(5);   // "MM-DD HH:MM"

function renderPivot(piv, soxl1h, red) {
  const now = piv.now;
  if (!now) { $('pivCall').textContent = '–'; $('pivChecks').innerHTML = '<li class="no">Not enough 1h data yet</li>'; return '–'; }
  const call = now.hold ? 'HOLD SOXL' : 'CASH';
  const barT = soxl1h[piv.nowIdx].time;
  $('pivCall').innerHTML = `${call}<span class="sub">from the last finished 1h bar (${hm(barT)} ET)</span>`;
  $('pivCall').className = 'call ' + (now.hold ? 'hold' : 'cash');
  const item = (label, val, st) => `<li class="${st === 'warn' ? 'warn' : ''}"><span>${label}</span><span class="${st}">${val}</span></li>`;
  const colorSt = (c, bad, good) => c === bad ? 'bad' : good.includes(c) ? 'ok' : 'no';
  let html =
    item('SOXS ADMF', COLOR_NAME[now.soxsColor] || '–', colorSt(now.soxsColor, 'red', ['orange'])) +
    item('SOXL ADMF', COLOR_NAME[now.soxlColor] || '–', colorSt(now.soxlColor, 'red', ['aqua'])) +
    item('Cash timer', now.left ? `${now.left} more bar${now.left > 1 ? 's' : ''} · ${now.cause.why} at ${hm(now.cause.time)}` :
      now.ended ? `ended by ${now.ended}` : 'clear', now.left ? 'bad' : 'ok');
  const f = piv.forming;
  if (f && f.bear) html += item('Bar still forming', `${f.bear} so far: CASH if it holds at the close`, 'warn');
  else if (f && f.bull && !now.hold) html += item('Bar still forming', `${f.bull} so far: may end the cash period`, 'warn');
  $('pivChecks').innerHTML = html;
  $('statsPiv').innerHTML = statRows(piv.stats, 'Hold SOXL');
  const same = red && red.stats ? piv.statsFrom(red.stats.from) : null;
  $('pivNote').textContent = (same ? `Since ${dstr(red.stats.from).slice(0, 10)}: this rule ${pct(same.totalPct, 0)} (max drawdown ${fmt(same.maxDDPct, 0)}%), ` +
    `Red-Light Trend ${pct(red.stats.totalPct, 0)} (${fmt(red.stats.maxDDPct, 0)}%), hold SOXL ${pct(red.stats.bhPct, 0)} (${fmt(red.stats.bhDDPct, 0)}%). ` : '') +
    'Yahoo keeps about 2 years of 1h bars, so this is all the history there is. Acting more than an hour or two late gives up much of the edge.';
  $('tradesPiv').innerHTML = tradeRows(piv.trades);
  return call;
}

async function loadStrategy() {
  try {
    const [l, s, l1, s1] = await Promise.all([api('/api/bars?symbol=SOXL&tf=4h'), api('/api/bars?symbol=SOXS&tf=4h'),
      api('/api/bars?symbol=SOXL&tf=1h'), api('/api/bars?symbol=SOXS&tf=1h')]);
    const soxl = l.bars, soxs = s.bars;
    const red = Strat.redLightTrend(soxl, soxs, { manualRed: $('manualRed').checked });
    red.stateByTime = new Map(); soxl.forEach((b, i) => red.stateByTime.set(b.time, red.state[i]));
    const fade = Strat.spikeFade(soxs);
    const piv = Strat.admfPivot(l1.bars, s1.bars, { lastClosed: lastClosed1h(l1.bars) });
    piv.stateByTime = new Map(); l1.bars.forEach((b, i) => piv.stateByTime.set(b.time, piv.state[i]));
    lastRed = red; lastFade = fade; lastPiv = piv;
    const pivCall = renderPivot(piv, l1.bars, red);
    if (S.lastCall && pivCall !== S.lastCall && $('notify').checked && 'Notification' in window && Notification.permission === 'granted')
      new Notification('SOXL ADMF Pivot', { body: `Call changed: ${S.lastCall} → ${pivCall}` });
    S.lastCall = pivCall;
    const now = red.now, last = soxl[soxl.length - 1];
    const call = now ? (now.hold ? 'HOLD SOXL' : 'CASH') : '–';
    $('callBox').textContent = call;
    $('callBox').className = 'call ' + (now ? (now.hold ? 'hold' : 'cash') : '');
    $('manualRedBox').classList.toggle('hidden', red.haveAdmf);
    if (now) {
      const item = (label, val, st) => `<li><span>${label}</span><span class="${st}">${val}</span></li>`;
      $('checks').innerHTML =
        item('Red light (ADMF on SOXS, last 3 bars)', now.red ? 'RED · stay out' : 'clear', now.red ? 'bad' : 'ok') +
        item(`Trend: close ${fmt(last.close)} vs 200 SMA ${fmt(now.trend)}`, now.inTrend ? 'above ✓' : 'below', now.inTrend ? 'ok' : 'no') +
        item(`Washout: close vs buy band ${fmt(now.lower)}`, now.washout ? 'below ✓' : 'no', now.washout ? 'ok' : 'no');
    }
    $('statsRed').innerHTML = statRows(red.stats, 'Hold SOXL');
    $('redNote').textContent = 'Yahoo gives about 2 years of hourly data, so this backtest covers less history than the 10-year test.';
    $('tradesRed').innerHTML = tradeRows(red.trades);
    $('fadeNow').innerHTML = fade.signalNow ? '<span class="down">Fade signal on the latest SOXS bar</span>' : '<span class="muted">No fade signal on the latest SOXS bar</span>';
    $('statsFade').innerHTML = statRows(fade.stats, 'Hold SOXS');
    $('tradesFade').innerHTML = tradeRows(fade.trades);
    applyOverlays();
  } catch (e) {
    $('callBox').textContent = '–'; $('pivCall').textContent = '–';
    $('checks').innerHTML = `<li class="bad">${esc(e.message)}</li>`;
    $('pivChecks').innerHTML = `<li class="bad">${esc(e.message)}</li>`;
  }
}

// ------------------------------------------------------------------ indicator dialogs
function openModal(html) { $('modalBody').innerHTML = html; $('modal').classList.remove('hidden'); }
function closeModal() { $('modal').classList.add('hidden'); }
$('modal').addEventListener('click', e => { if (e.target.id === 'modal' || e.target.dataset.close != null) closeModal(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

function openIndicators() {
  const on = S.inds.map(i => `<div class="irow"><span>${esc(REGISTRY[i.type].title ? REGISTRY[i.type].title(i.params) : REGISTRY[i.type].name)}</span>
    <span><button data-ia="vis" data-uid="${i.uid}">${i.visible ? 'Hide' : 'Show'}</button><button data-ia="edit" data-uid="${i.uid}">Settings</button><button data-ia="del" data-uid="${i.uid}">Remove</button></span></div>`).join('') || '<div class="muted">None</div>';
  const cat = Object.entries(REGISTRY).map(([k, d]) => `<div class="irow"><span>${esc(d.name)}<br><span class="muted small">${esc(d.desc || '')}</span></span>
    <span><button data-ia="add" data-type="${k}">Add</button></span></div>`).join('');
  openModal(`<div class="mh">Indicators<span class="x" data-close>✕</span></div>
    <div class="msec">On this chart</div>${on}<div class="msec">Add</div>${cat}
    <div class="mfoot"><button data-ia="reset">Reset to defaults</button></div>`);
}
$('modalBody').addEventListener('click', e => {
  const a = e.target.dataset.ia; if (!a) return;
  const inst = S.inds.find(x => x.uid === e.target.dataset.uid);
  if (a === 'add') { const i = newInst(e.target.dataset.type); S.inds.push(i); saveInds(); rebuild(); openSettings(i); return; }
  if (a === 'vis' && inst) inst.visible = !inst.visible;
  if (a === 'del' && inst) S.inds = S.inds.filter(x => x !== inst);
  if (a === 'reset') S.inds = DEFAULT_INDICATORS.map(x => newInst(x.type, x.params));
  if (a === 'edit' && inst) { openSettings(inst); return; }
  saveInds(); rebuild(); openIndicators();
});

function openSettings(inst) {
  const def = REGISTRY[inst.type];
  const field = p => {
    const v = inst.params[p.key], id = 'f_' + p.key;
    if (p.type === 'bool') return `<label class="frow"><span>${esc(p.label)}</span><input type="checkbox" id="${id}" ${v ? 'checked' : ''}></label>`;
    if (p.type === 'select') return `<label class="frow"><span>${esc(p.label)}</span><select id="${id}">${p.options.map(o => `<option ${o === v ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select></label>`;
    if (p.type === 'color') return `<label class="frow"><span>${esc(p.label)}</span><input type="color" id="${id}" value="${esc(v)}"></label>`;
    return `<label class="frow"><span>${esc(p.label)}</span><input type="number" id="${id}" value="${esc(v)}" step="${p.type === 'int' ? 1 : 0.1}"></label>`;
  };
  openModal(`<div class="mh">${esc(def.name)}<span class="x" data-close>✕</span></div>
    ${def.params.map(field).join('') || '<div class="muted">No settings</div>'}
    <div class="mfoot"><button id="setDefaults">Defaults</button><button class="primary" id="setOk">OK</button></div>`);
  $('setDefaults').onclick = () => { def.params.forEach(p => inst.params[p.key] = p.def); saveInds(); rebuild(); openSettings(inst); };
  $('setOk').onclick = () => {
    for (const p of def.params) {
      const el = $('f_' + p.key);
      inst.params[p.key] = p.type === 'bool' ? el.checked : p.type === 'int' ? parseInt(el.value, 10) || p.def :
        p.type === 'float' ? (isNaN(parseFloat(el.value)) ? p.def : parseFloat(el.value)) : el.value;
    }
    saveInds(); closeModal(); rebuild();
  };
}
$('btnInd').addEventListener('click', openIndicators);

// ------------------------------------------------------------------ watchlist
let QUOTES = {};
function renderWatch() {
  $('watch').querySelector('tbody').innerHTML = S.watch.map(sym => {
    const q = QUOTES[sym] || {};
    return `<tr data-s="${esc(sym)}" class="${sym === S.symbol ? 'on' : ''}"><td>${esc(sym)}</td>` +
      `<td class="num">${fmt(q.last)}</td><td class="num ${cls(q.chg)}">${fmt(q.chg)}</td>` +
      `<td class="num ${cls(q.chgPct)}">${pct(q.chgPct, 2)}</td><td class="x" data-x="${esc(sym)}">×</td></tr>`;
  }).join('');
}
async function loadQuotes() {
  try { const j = await api('/api/quotes?symbols=' + S.watch.map(encodeURIComponent).join(','));
    QUOTES = {}; j.quotes.forEach(q => { if (!q.error) QUOTES[q.symbol] = q; }); } catch {}
  renderWatch();
}
$('watch').addEventListener('click', e => {
  const x = e.target.dataset.x;
  if (x) { S.watch = S.watch.filter(s => s !== x); store.set('watch', S.watch); renderWatch(); return; }
  const tr = e.target.closest('tr'); if (tr) setSymbol(tr.dataset.s);
});
$('addForm').addEventListener('submit', e => {
  e.preventDefault(); const s = $('addInput').value.trim().toUpperCase(); $('addInput').value = '';
  if (s && !S.watch.includes(s)) { S.watch.push(s); store.set('watch', S.watch); loadQuotes(); }
});

// ------------------------------------------------------------------ controls
function setSymbol(sym) { S.symbol = sym.toUpperCase(); store.set('symbol', S.symbol); $('symInput').value = S.symbol; renderWatch(); price = null; loadChart(true); }
function setTf(tf) {
  S.tf = tf; store.set('tf', tf);
  document.querySelectorAll('#tfs button').forEach(b => b.classList.toggle('on', b.dataset.tf === tf));
  price = null; loadChart(true);
}
$('symForm').addEventListener('submit', e => { e.preventDefault(); const v = $('symInput').value.trim(); if (v) setSymbol(v); $('symInput').blur(); });
$('tfs').addEventListener('click', e => { if (e.target.dataset.tf) setTf(e.target.dataset.tf); });
$('tgSignals').addEventListener('change', applyOverlays);
$('manualRed').addEventListener('change', loadStrategy);
$('notify').addEventListener('change', e => { if (e.target.checked && 'Notification' in window) Notification.requestPermission(); });

$('symInput').value = S.symbol;
document.querySelectorAll('#tfs button').forEach(b => b.classList.toggle('on', b.dataset.tf === S.tf));
renderWatch();
loadChart(true).then(loadStrategy);
loadQuotes();
setInterval(() => { loadChart(false); loadStrategy(); loadQuotes(); }, REFRESH_MS);
