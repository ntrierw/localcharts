"""
Local Charts: a small TradingView-style charting app that runs on your own computer.

  python app.py            -> starts at http://127.0.0.1:8765 and opens your browser
  python app.py --demo     -> runs on made-up data (no internet needed), for testing

Market data comes from Yahoo Finance through the free `yfinance` package.
Intraday history is limited by Yahoo: about 60 days for 5m/15m and about 2 years for 1h/4h.

Closed bars (every bar from before today) are saved under ./cache, so after the first download each
refresh only asks Yahoo for the bars since the last saved one. Delete that folder to force a full
re-download.
"""
import argparse
import datetime as dt
import os
import re
import sys
import threading
import time
import webbrowser
from pathlib import Path

import numpy as np
import pandas as pd
from flask import Flask, jsonify, request, send_from_directory

ET = "America/New_York"

# timeframe -> (Yahoo interval, days of history or None for "max", resample to 4h?)
TIMEFRAMES = {
    "5m": ("5m", 59, False),
    "15m": ("15m", 59, False),
    "1h": ("1h", 729, False),
    "4h": ("1h", 729, True),
    "1D": ("1d", None, False),
    "1W": ("1wk", None, False),
}

LIVE_REFRESH_SEC = 20         # ask Yahoo for bars newer than the saved ones this often
CACHE_DIR = Path(__file__).resolve().parent / "cache"
SPLIT_TOLERANCE = 0.02        # saved vs fresh close differing by more than this => re-download all

# how long one bar lasts, used to tell whether a bar has closed
BAR_SPAN = {
    "5m": pd.Timedelta(minutes=5), "15m": pd.Timedelta(minutes=15), "1h": pd.Timedelta(hours=1),
    "1d": pd.Timedelta(days=1), "1wk": pd.Timedelta(days=7),
}

app = Flask(__name__, static_folder="static", static_url_path="/static")
_cache = {}        # (symbol, yint) -> {"final": closed bars, "df": closed + live bars, "live_at": time}
_key_locks = {}
_quote_cache = {}
_lock = threading.Lock()
DEMO = False


# ---------------------------------------------------------------- data

def _clean(df):
    if df is None or df.empty:
        # keep a time index even when empty, so date filtering on it still works
        return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"],
                            index=pd.DatetimeIndex([], tz=ET))
    df = df[["Open", "High", "Low", "Close", "Volume"]].copy()
    df = df.dropna(subset=["Open", "High", "Low", "Close"])
    if df.index.tz is None:
        df.index = df.index.tz_localize(ET)
    return df


def _yahoo(symbol, yint, days, start=None):
    """Download bars from Yahoo: from `start` (a date) if given, otherwise the whole available history."""
    import yfinance as yf

    t = yf.Ticker(symbol)
    kw = dict(interval=yint, auto_adjust=False, actions=False, prepost=False)
    if start is None and days is None:
        return _clean(t.history(period="max", **kw))
    if start is None:
        start = dt.date.today() - dt.timedelta(days=days)
    return _clean(t.history(start=start.isoformat(), **kw))


def _demo(symbol, yint, days, short):
    """Made-up but realistic-looking bars so the app can be tried offline."""
    freq = {"5m": "5min", "15m": "15min", "1h": "1h", "1d": "1D", "1wk": "7D"}[yint]
    end = pd.Timestamp.now(tz=ET).floor("D")
    start = end - pd.Timedelta(days=days or 3650)
    if yint in ("1d", "1wk"):
        idx = pd.date_range(start, end, freq="B" if yint == "1d" else "W-MON", tz=ET)
    else:
        days_idx = pd.bdate_range(start, end, tz=ET)
        step = pd.Timedelta(freq)
        parts = []
        for d in days_idx:
            t0 = d + pd.Timedelta(hours=9, minutes=30)
            parts.append(pd.date_range(t0, d + pd.Timedelta(hours=16) - pd.Timedelta(seconds=1), freq=step))
        idx = parts[0].append(parts[1:]) if parts else pd.DatetimeIndex([], tz=ET)
    rng = np.random.default_rng(abs(hash(symbol)) % 2**32)
    vol = 0.012 if yint in ("5m", "15m") else 0.03
    sign = -1 if symbol.upper().endswith("S") else 1
    base = rng.normal(0, vol, len(idx)).cumsum() * 0.6
    swing = np.sin(np.arange(len(idx)) / 90.0) * 0.6 * sign
    close = 50 * np.exp(base + swing)
    openp = np.r_[close[0], close[:-1]] * (1 + rng.normal(0, vol / 4, len(idx)))
    high = np.maximum(openp, close) * (1 + np.abs(rng.normal(0, vol / 2, len(idx))))
    low = np.minimum(openp, close) * (1 - np.abs(rng.normal(0, vol / 2, len(idx))))
    df = pd.DataFrame({"Open": openp, "High": high, "Low": low, "Close": close,
                       "Volume": rng.integers(1e5, 5e6, len(idx))}, index=idx)
    return df.iloc[-300:] if short else df


# ---------------------------------------------------------------- disk cache of closed bars

def _cache_path(symbol, yint):
    safe = re.sub(r"[^A-Za-z0-9.-]", lambda m: "%%%02X" % ord(m.group()), symbol)
    return CACHE_DIR / f"{safe}_{yint}.csv"


def _load_disk(symbol, yint):
    path = _cache_path(symbol, yint)
    if not path.exists():
        return _clean(None)
    try:
        df = pd.read_csv(path, index_col=0)
        df.index = pd.to_datetime(df.index, utc=True).tz_convert(ET)
        return _clean(df).sort_index()
    except Exception as e:  # a damaged file just means downloading again
        print(f"ignoring unreadable cache file {path}: {e}", file=sys.stderr)
        return _clean(None)


def _save_disk(symbol, yint, df):
    try:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        path = _cache_path(symbol, yint)
        tmp = path.with_suffix(".tmp")
        df.to_csv(tmp, index_label="Date")
        os.replace(tmp, path)  # never leave a half-written file behind
    except Exception as e:
        print(f"could not write cache for {symbol} {yint}: {e}", file=sys.stderr)


def _closed(df, yint):
    """Bars that ended before today (New York time). Those won't change, so they can be saved."""
    today = pd.Timestamp.now(tz=ET).normalize()
    return df[df.index + BAR_SPAN[yint] <= today]


def _merge(saved, fresh):
    """Add freshly downloaded bars to the saved ones (fresh wins where they overlap).

    Returns (ok, merged). ok is False when the overlapping closes don't match, which happens after a
    split (Yahoo rewrites past prices); then the saved bars can't be trusted and merged is just `fresh`.
    """
    if saved.empty:
        return True, fresh
    if fresh.empty:
        return True, saved
    common = saved.index.intersection(fresh.index)
    if len(common) == 0:
        return False, fresh
    ratio = (fresh.loc[common, "Close"] / saved.loc[common, "Close"]).median()
    if not abs(ratio - 1) <= SPLIT_TOLERANCE:
        return False, fresh
    df = pd.concat([saved, fresh])
    return True, df[~df.index.duplicated(keep="last")].sort_index()


def _key_lock(key):
    with _lock:
        return _key_locks.setdefault(key, threading.Lock())


def _base(symbol, yint, days):
    key = (symbol, yint)
    if DEMO:  # made-up data: build it once, keep it in memory only
        with _lock:
            if key not in _cache:
                _cache[key] = {"df": _demo(symbol, yint, days, short=False)}
            return _cache[key]["df"]

    with _key_lock(key):  # one download at a time per symbol/interval
        ent = _cache.get(key)
        if ent is not None and time.time() - ent["live_at"] < LIVE_REFRESH_SEC:
            return ent["df"]
        final = ent["final"] if ent is not None else _load_disk(symbol, yint)

        # Only ask for bars from the last saved one onward. Re-getting that one bar lets us spot splits.
        start = None
        if not final.empty:
            last = final.index[-1].date()
            if days is None or last >= dt.date.today() - dt.timedelta(days=days):
                start = last
        try:
            fresh = _yahoo(symbol, yint, days, start)
            ok, df = _merge(final, fresh)
            if not ok and start is not None:
                print(f"{symbol} {yint}: saved prices no longer match Yahoo (split?), "
                      "downloading full history again", file=sys.stderr)
                df = _yahoo(symbol, yint, days, None)
            reset = not ok
        except Exception as e:  # keep showing what we have if Yahoo is unreachable
            if ent is None and final.empty:
                raise
            print(f"refresh failed for {symbol} {yint}: {e}", file=sys.stderr)
            df, reset = (ent["df"] if ent is not None else final), False

        new_final = _closed(df, yint)
        if reset or not new_final.index.equals(final.index):
            _save_disk(symbol, yint, new_final)
        _cache[key] = {"final": new_final, "df": df, "live_at": time.time()}
        return df


def _to_4h(df):
    """Group 1h bars into TradingView-style 4h session bars: 9:30-13:30 and 13:30-16:00 ET."""
    if df.empty:
        return df
    idx = df.index.tz_convert(ET)
    mins = idx.hour * 60 + idx.minute
    offs = np.where(mins < 13 * 60 + 30, 9 * 60 + 30, 13 * 60 + 30)
    keys = idx.normalize() + pd.to_timedelta(offs, unit="m")
    g = df.groupby(keys)
    out = pd.DataFrame({
        "Open": g["Open"].first(), "High": g["High"].max(), "Low": g["Low"].min(),
        "Close": g["Close"].last(), "Volume": g["Volume"].sum(),
    })
    out.index = pd.DatetimeIndex(out.index)
    return out


def get_bars(symbol, tf):
    yint, days, resample4h = TIMEFRAMES[tf]
    df = _base(symbol, yint, days)
    if resample4h:
        df = _to_4h(df)
    return df


def _wall_clock_epoch(index):
    """Chart library shows times as UTC, so send New York wall-clock time as if it were UTC."""
    naive = index.tz_convert(ET).tz_localize(None)
    return ((naive - pd.Timestamp("1970-01-01")) // pd.Timedelta("1s")).astype("int64")


# ---------------------------------------------------------------- routes

@app.route("/")
def index():
    return send_from_directory("static", "index.html")


@app.route("/api/bars")
def api_bars():
    symbol = request.args.get("symbol", "SOXL").upper().strip()
    tf = request.args.get("tf", "4h")
    if tf not in TIMEFRAMES:
        return jsonify(error=f"unknown timeframe {tf}"), 400
    try:
        df = get_bars(symbol, tf)
    except Exception as e:
        return jsonify(error=f"could not load {symbol}: {e}"), 502
    if df.empty:
        return jsonify(error=f"no data for {symbol}"), 404
    t = _wall_clock_epoch(df.index)
    bars = [
        {"time": int(ti), "open": float(o), "high": float(h), "low": float(l),
         "close": float(c), "volume": float(v)}
        for ti, o, h, l, c, v in zip(t, df["Open"], df["High"], df["Low"], df["Close"], df["Volume"])
    ]
    return jsonify(symbol=symbol, tf=tf, bars=bars,
                   updated=dt.datetime.now().isoformat(timespec="seconds"),
                   source="Demo data" if DEMO else "Yahoo Finance")


@app.route("/api/quotes")
def api_quotes():
    symbols = [s.strip().upper() for s in request.args.get("symbols", "").split(",") if s.strip()]
    out = []
    for s in symbols[:40]:
        cached = _quote_cache.get(s)
        if cached and time.time() - cached[0] < LIVE_REFRESH_SEC:
            out.append(cached[1])
            continue
        try:
            d = get_bars(s, "1D")
            last, prev = float(d["Close"].iloc[-1]), float(d["Close"].iloc[-2])
            q = {"symbol": s, "last": last, "chg": last - prev, "chgPct": (last / prev - 1) * 100}
        except Exception as e:
            q = {"symbol": s, "error": str(e)}
        _quote_cache[s] = (time.time(), q)
        out.append(q)
    return jsonify(quotes=out)


def main():
    global DEMO
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--demo", action="store_true", help="use made-up data (no internet)")
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()
    DEMO = args.demo
    url = f"http://127.0.0.1:{args.port}"
    print(f"\n  Local Charts running at {url}  (Ctrl+C to stop)")
    if not DEMO:
        print(f"  Saved price history: {CACHE_DIR}")
        # alerts.py does `import app`: point that at this running copy so both share one bar cache
        sys.modules.setdefault("app", sys.modules[__name__])
        try:
            import alerts
            if alerts.start_in_background():
                print("  Phone alerts: on (log in alerts/alerts.log)")
        except Exception as e:
            print(f"  Phone alerts could not start: {e}")
    print()
    if not args.no_browser:
        threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    app.run(host="127.0.0.1", port=args.port, debug=False, threaded=True)


if __name__ == "__main__":
    main()
