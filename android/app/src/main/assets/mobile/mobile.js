// Phone additions on top of static/app.js (shared with the desktop app, loaded just before this file):
// tabs, the Alerts screen, touch-sized charts and dialogs, and the link to the phone's own notifications.
// app.js's top-level functions and variables (S, price, panes, chartOpts, rebuild, setSymbol, ...) are
// globals, so this file adjusts them instead of keeping a second copy of the app.
const Native = window.Android || null;   // the Android app's bridge (missing when opened in a browser)

// ------------------------------------------------------------------ charts sized for a phone
chartOpts.layout.fontSize = 10;
chartOpts.rightPriceScale.minimumWidth = 50;
chartOpts.timeScale.rightOffset = 3;
chartOpts.handleScale = { axisPressedMouseMove: true, mouseWheel: true, pinch: true };
chartOpts.kineticScroll = { touch: true, mouse: false };
const PHONE_BARS = 90;   // bars shown when a chart opens (the desktop shows 220)

const _rebuild = rebuild;
rebuild = function () {
  const fresh = !price;
  _rebuild();
  if (fresh && S.bars.length) {
    const n = S.bars.length, r = { from: Math.max(0, n - PHONE_BARS), to: n + 3 };
    panes.forEach(p => p.chart.timeScale().setVisibleLogicalRange(r));
  }
  updateHeader();
};

// ------------------------------------------------------------------ toast
let toastTimer = null;
function toast(msg, ms = 2600) {
  const t = $('toast'); t.textContent = msg; t.classList.add('on');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('on'), ms);
}

// ------------------------------------------------------------------ tabs
let tab = 'chart';
function showTab(name) {
  if (!document.getElementById('view-' + name)) name = 'chart';
  tab = name;
  document.querySelectorAll('.page').forEach(v => v.classList.toggle('on', v.id === 'view-' + name));
  document.querySelectorAll('#tabbar button').forEach(b => b.classList.toggle('on', b.dataset.tab === name));
  if (name === 'alerts') refreshAlerts();
  if (name === 'watch') loadQuotes();
  store.set('tab', name);
}
$('tabbar').addEventListener('click', e => { const b = e.target.closest('button'); if (b) showTab(b.dataset.tab); });
window.lcShowTab = showTab;

// Android back button: close a dialog, then go back to the chart, then leave the app
window.lcBack = () => {
  if (!$('modal').classList.contains('hidden')) { closeModal(); return true; }
  if (document.activeElement && document.activeElement.tagName === 'INPUT') { document.activeElement.blur(); return true; }
  if (tab !== 'chart') { showTab('chart'); return true; }
  return false;
};
// back in the app after a while: refresh right away instead of waiting up to 30 seconds
window.lcResume = () => { loadChart(false); loadStrategy(); loadQuotes(); if (tab === 'alerts') refreshAlerts(); };

// ------------------------------------------------------------------ chart header: price and change
function updateHeader() {
  const q = QUOTES[S.symbol], b = S.bars[S.bars.length - 1];
  const last = q ? q.last : b && b.close;
  if (last == null) { $('hdrQuote').innerHTML = ''; return; }
  $('hdrQuote').innerHTML = `<b>${fmt(last)}</b>` + (q ? `<span class="${cls(q.chgPct)}">${pct(q.chgPct, 2)}</span>` : '');
}
const _renderWatch = renderWatch;
renderWatch = function () { _renderWatch(); updateHeader(); };
const _setStatus = setStatus;
setStatus = function (t, err) {
  _setStatus(t, err);
  updateHeader();
  if (!err) $('btnRefresh').classList.remove('spin');
};
$('btnRefresh').addEventListener('click', async () => {
  $('btnRefresh').classList.add('spin');
  try { await Promise.all([loadChart(false), loadStrategy(), loadQuotes()]); }
  finally { $('btnRefresh').classList.remove('spin'); }
});
$('symInput').addEventListener('focus', e => setTimeout(() => e.target.select(), 0));

// ------------------------------------------------------------------ watchlist: tap a symbol to chart it
$('watch').addEventListener('click', e => {
  if (e.target.dataset.x) return;
  if (e.target.closest('tr')) showTab('chart');
});

// ------------------------------------------------------------------ strategy tab: call badge + jump to its chart
const _renderPivot = renderPivot;
renderPivot = function (...a) {
  const call = _renderPivot(...a), badge = $('tabBadge');
  badge.textContent = call === 'HOLD SOXL' ? 'HOLD' : call === 'CASH' ? 'CASH' : '';
  badge.className = 'badge ' + (call === 'HOLD SOXL' ? 'hold' : call === 'CASH' ? 'cash' : '');
  return call;
};
function chartFor(sym, tf) {
  $('tgSignals').checked = true;
  if (S.symbol !== sym) { S.symbol = sym; store.set('symbol', sym); $('symInput').value = sym; renderWatch(); }
  showTab('chart');
  setTf(tf);
}
document.addEventListener('click', e => {
  const g = e.target.dataset && e.target.dataset.goto;
  if (g === 'chart-piv') chartFor('SOXL', '1h');
  if (g === 'chart-red') chartFor('SOXL', '4h');
  if (g === 'chart-fade') chartFor('SOXS', '4h');
});

// ------------------------------------------------------------------ indicator settings: color swatches
// (a quicker way to pick a color with a finger than the color picker)
const SWATCHES = ['#2962ff', '#00bcd4', '#26a69a', '#00e676', '#ffeb3b', '#ff9800', '#ef5350', '#e91e63', '#9c27b0', '#b2b5be', '#ffffff'];
new MutationObserver(() => {
  $('modalBody').querySelectorAll('input[type=color]').forEach(inp => {
    if (inp.dataset.sw) return; inp.dataset.sw = '1';
    const row = document.createElement('div'); row.className = 'swatches';
    const mark = () => row.querySelectorAll('i').forEach(i => i.classList.toggle('on', i.dataset.c === inp.value.toLowerCase()));
    row.innerHTML = SWATCHES.map(c => `<i data-c="${c}" style="background:${c}"></i>`).join('');
    row.addEventListener('click', e => { if (e.target.dataset.c) { inp.value = e.target.dataset.c; mark(); } });
    inp.addEventListener('input', mark);
    inp.closest('.frow').after(row); mark();
  });
}).observe($('modalBody'), { childList: true, subtree: true });

// ------------------------------------------------------------------ phone alerts
const nativeCalls = {};
let nativeSeq = 0;
window.lcNative = (id, ok, text) => { const cb = nativeCalls[id]; delete nativeCalls[id]; if (cb) cb(ok, text); };
const runNative = action => new Promise(res => { const id = 'n' + (++nativeSeq); nativeCalls[id] = (ok, text) => res({ ok, text }); Native.run(action, id); });

function alertsInfo() { try { return JSON.parse(Native.alertsInfo()); } catch { return null; } }

function refreshAlerts() {
  if (!Native) {
    $('alertChecks').innerHTML = '<li class="no">Phone alerts work in the Android app.</li>';
    ['alertsOn', 'notify', 'btnTest', 'btnStatus', 'btnClear'].forEach(id => $(id).disabled = true);
    return;
  }
  const a = alertsInfo(); if (!a) return;
  $('alertsOn').checked = $('notify').checked = a.enabled;
  $('alertDot').classList.toggle('hidden', !a.enabled);
  const item = (label, val, st, btn) => `<li><span>${label}</span><span class="${st}">${val}${btn ? ` <button data-set="${btn}">Change</button>` : ''}</span></li>`;
  let h = item('Notifications', a.canNotify ? 'allowed' : 'blocked', a.canNotify ? 'ok' : 'bad', a.canNotify ? '' : 'notifications') +
    item('Exact timing', a.exact ? 'on' : 'off (may be late)', a.exact ? 'ok' : 'bad', a.exact ? '' : 'exact') +
    item('Battery use', a.battery ? 'unrestricted' : 'optimized (may be delayed)', a.battery ? 'ok' : 'no', a.battery ? '' : 'battery');
  if (a.enabled) {
    if (a.lastRun) h += item('Last check', `${a.lastRun.slice(5, 16)} ET`, 'no');
    if (a.lastResult) h += `<li class="small"><span class="muted">${esc(a.lastResult)}</span></li>`;
    if (a.nextCheck) h += item('Next check', `${a.nextCheck.slice(5, 16)} ET`, 'no');
  }
  $('alertChecks').innerHTML = h;
  $('alertLog').textContent = a.log.length ? a.log.join('\n') : 'Nothing yet.';
}
window.lcAlertsChanged = () => {
  refreshAlerts();
  const a = alertsInfo();
  if (a && a.enabled) toast('Alerts on: you\'ll get BUY / SELL notifications');
  else if (a && !a.canNotify) toast('Notifications are blocked for Local Charts');
  else toast('Alerts off');
  setTimeout(refreshAlerts, 4000);   // the first check runs in the background
};
function setAlerts(on) { if (Native) Native.setAlerts(on); }
$('alertsOn').addEventListener('change', e => setAlerts(e.target.checked));
$('notify').addEventListener('change', e => setAlerts(e.target.checked));
$('alertChecks').addEventListener('click', e => { if (e.target.dataset.set) Native.openSettings(e.target.dataset.set); });

async function busy(btn, msgEl, action, done) {
  btn.disabled = true; $(msgEl).textContent = 'Working…'; $(msgEl).className = 'small muted';
  const r = await runNative(action);
  btn.disabled = false;
  $(msgEl).textContent = r.ok ? done(r.text) : r.text;
  $(msgEl).className = 'small ' + (r.ok ? '' : 'down');
  refreshAlerts();
}
$('btnTest').addEventListener('click', e => busy(e.target, 'alertMsg', 'test', t => `Sent. Current call: ${t}`));
$('btnStatus').addEventListener('click', e => busy(e.target, 'alertMsg', 'status', t => t));
$('btnClear').addEventListener('click', e => {
  if (!confirm('Delete the saved price history? Everything is downloaded again the next time it is shown.')) return;
  busy(e.target, 'dataMsg', 'clearCache', t => { price = null; loadChart(true); loadStrategy(); loadQuotes(); return t + '. Downloading again…'; });
});

// ------------------------------------------------------------------ start
if (Native) {
  $('ver').textContent = 'v' + Native.version();
  const want = Native.takePendingTab();
  showTab(want || store.get('tab', 'chart'));
} else {
  showTab(store.get('tab', 'chart'));
}
refreshAlerts();
