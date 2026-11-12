"""SOXL ADMF Pivot in Python, for the phone alerts (alerts.py).

A line-for-line port of Ind.admf() (colors only) and Strat.admfPivot() in static/indicators.js and
static/strategies.js. It uses plain Python floats in the same order of operations as the JavaScript,
so both give identical colors and calls. `python alerts.py check` compares them bar by bar.

The admf() port is derived from "ADMF Divergence (High/Low Highlight + Alerts)" (c) stonksforlife,
published under the Mozilla Public License 2.0 (https://mozilla.org/MPL/2.0/), like indicators.js.
"""


def _nn(v):
    return v is not None and v == v  # not None and not NaN


def _rma(src, p):
    out, e, n, s = [None] * len(src), None, 0, 0.0
    for i, v in enumerate(src):
        if _nn(v):
            if e is None:
                s += v
                n += 1
                if n == p:
                    e = s / p
            else:
                e = (e * (p - 1) + v) / p
        out[i] = e
    return out


def _highest(src, p):
    out = []
    for i in range(len(src)):
        if i < p - 1:
            out.append(None)
            continue
        m = None
        for j in range(i - p + 1, i + 1):
            if _nn(src[j]) and (m is None or src[j] > m):
                m = src[j]
        out.append(m)
    return out


def _lowest(src, p):
    out = []
    for i in range(len(src)):
        if i < p - 1:
            out.append(None)
            continue
        m = None
        for j in range(i - p + 1, i + 1):
            if _nn(src[j]) and (m is None or src[j] < m):
                m = src[j]
        out.append(m)
    return out


def _valuewhen(cond, src, occ=1):
    hist, out = [], []
    for c, v in zip(cond, src):
        if c:
            hist.insert(0, v)
        out.append(hist[occ] if len(hist) > occ else None)
    return out


def admf_colors(bars, length=14, lookback=20):
    """bars: list of dicts with open/high/low/close/volume. Returns 'red'/'orange'/'lime'/'aqua'/'blue' per bar."""
    n = len(bars)
    x = [None] * n
    for i in range(1, n):
        b, c1 = bars[i], bars[i - 1]["close"]
        tr = max(b["high"] - b["low"], abs(b["high"] - c1), abs(b["low"] - c1))
        if tr == 0:
            continue
        r = (b["close"] - c1) / tr
        r = (1 - 0) * r + (1 if r > 0 else -1 if r < 0 else 0) * 0
        trl, trh = min(b["low"], c1), max(b["high"], c1)
        vol = b["volume"] * (c1 + b["close"] + trl + trh) / 4
        x[i] = vol * r
    hist = _rma(x, length)
    H = [b["high"] for b in bars]
    L = [b["low"] for b in bars]
    ph, pl, hh, hl = _highest(H, lookback), _lowest(L, lookback), _highest(hist, lookback), _lowest(hist, lookback)

    def prev(a):
        return [None] + a[:-1]

    ph_prev = _valuewhen([ph[i] is not None and H[i] == ph[i] for i in range(n)], prev(ph))
    pl_prev = _valuewhen([pl[i] is not None and L[i] == pl[i] for i in range(n)], prev(pl))
    hh_prev = _valuewhen([hh[i] is not None and hist[i] == hh[i] for i in range(n)], prev(hh))
    hl_prev = _valuewhen([hl[i] is not None and hist[i] == hl[i] for i in range(n)], prev(hl))

    def gt(a, b):
        return _nn(a) and _nn(b) and a > b

    def lt(a, b):
        return _nn(a) and _nn(b) and a < b

    colors = []
    for i in range(n):
        hi, lo, h = H[i], L[i], hist[i]
        reg_bear = gt(hi, ph_prev[i]) and lt(h, hh_prev[i])
        reg_bull = lt(lo, pl_prev[i]) and gt(h, hl_prev[i])
        hid_bear = lt(hi, ph_prev[i]) and gt(h, hh_prev[i])
        hid_bull = gt(lo, pl_prev[i]) and lt(h, hl_prev[i])
        colors.append("red" if reg_bear else "orange" if hid_bear else "lime" if reg_bull else "aqua" if hid_bull else "blue")
    return colors


def admf_pivot(soxl, soxs, cash_bars=4, warmup=60):
    """Same rule as Strat.admfPivot(). soxl/soxs: lists of bar dicts with a 'time' key.
    Returns one state dict per SOXL bar (None during warm-up)."""
    cl = admf_colors(soxl)
    cs_all = admf_colors(soxs)
    by_time = {b["time"]: cs_all[i] for i, b in enumerate(soxs)}
    cs = [by_time.get(b["time"]) for b in soxl]
    state, left, cause = [None] * len(soxl), 0, None
    for i in range(len(soxl)):
        bear = "SOXS red" if cs[i] == "red" else "SOXL red" if cl[i] == "red" else None
        bull = "SOXL aqua" if cl[i] == "aqua" else "SOXS orange" if cs[i] == "orange" else None
        ended = None
        if bear:
            left, cause = cash_bars, {"why": bear, "time": soxl[i]["time"]}
        elif bull and left > 0:
            left, ended = 0, bull
        if i >= warmup:
            state[i] = {"hold": left == 0, "left": left, "cause": cause if left else None, "bear": bear,
                        "bull": bull, "ended": ended, "soxlColor": cl[i], "soxsColor": cs[i]}
        if left > 0:
            left -= 1
    return state


def last_closed_1h(bars, now_wall):
    """Index of the last finished 1h bar. Times are New York wall-clock seconds stored as if UTC;
    the 15:30 bar ends at the 16:00 close."""
    for i in range(len(bars) - 1, -1, -1):
        t = bars[i]["time"]
        if min(t + 3600, t - t % 86400 + 16 * 3600) <= now_wall:
            return i
    return -1
