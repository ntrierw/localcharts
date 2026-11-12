"""Phone alerts for the SOXL ADMF Pivot: a push notification whenever the call changes (BUY or SELL).

    python alerts.py setup     make your private alert channel (once)
    python alerts.py test      send a test notification
    python alerts.py status    the current call, from the last finished 1h bar
    python alerts.py check     compare the Python copy of the strategy with the app's JavaScript (needs Node)

Notifications go through ntfy (https://ntfy.sh), a free push service: install the ntfy app on your phone and
subscribe to the channel name `setup` prints. Once set up, the alerts run by themselves whenever the chart
app (app.py) is running. Nothing here places orders. Not financial advice.
"""
import datetime as dt
import json
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from pathlib import Path
from zoneinfo import ZoneInfo

import app
import pivot

HERE = Path(__file__).resolve().parent
DIR = HERE / "alerts"
CONFIG_FILE = DIR / "config.json"
STATE_FILE = DIR / "state.json"
LOG_FILE = DIR / "alerts.log"
NY = ZoneInfo("America/New_York")
GRACE_SEC = 120          # wait this long after a bar ends before trusting its data
STALE_SEC = 75 * 60      # the newest 1h bar must have started within this long (also skips holidays)
CHECK_EVERY_SEC = 30
_started = False


# ---------------------------------------------------------------- helpers

def ny_now():
    return dt.datetime.now(NY)


def wall(t):
    """A New York datetime as wall-clock seconds stored as if UTC (how the app stores bar times)."""
    return int(dt.datetime(t.year, t.month, t.day, t.hour, t.minute, t.second, tzinfo=dt.timezone.utc).timestamp())


def wall_dt(s):
    return dt.datetime.fromtimestamp(s, dt.timezone.utc)


def log(msg, echo=True):
    line = f"{ny_now():%Y-%m-%d %H:%M:%S} ET  {msg}"
    if echo:
        print(f"  [alerts] {msg}", flush=True)
    DIR.mkdir(exist_ok=True)
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def _read(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _write(path, data):
    DIR.mkdir(exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    tmp.replace(path)


def config():
    return _read(CONFIG_FILE)


def send(title, message, priority="high", tags=""):
    """Push one notification to the ntfy channel in alerts/config.json."""
    cfg = config()
    if not cfg.get("topic"):
        raise RuntimeError("alerts are not set up yet: run  python alerts.py setup")
    url = cfg.get("server", "https://ntfy.sh").rstrip("/") + "/" + cfg["topic"]
    headers = {"Title": title, "Priority": priority}  # header values must be plain ASCII
    if tags:
        headers["Tags"] = tags
    req = urllib.request.Request(url, data=message.encode("utf-8"), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        r.read()


# ---------------------------------------------------------------- the strategy

def bars_1h(symbol):
    df = app.get_bars(symbol, "1h")
    t = app._wall_clock_epoch(df.index)
    return [{"time": int(ti), "open": float(o), "high": float(h), "low": float(l), "close": float(c), "volume": float(v)}
            for ti, o, h, l, c, v in zip(t, df["Open"], df["High"], df["Low"], df["Close"], df["Volume"])]


def current_call(now=None):
    """The strategy's call from the last finished 1h bar, like the app's panel."""
    now = now or ny_now()
    soxl, soxs = bars_1h("SOXL"), bars_1h("SOXS")
    state = pivot.admf_pivot(soxl, soxs)
    k = pivot.last_closed_1h(soxl, wall(now) - GRACE_SEC)
    return {"state": state[k] if k >= 0 else None, "bar_time": soxl[k]["time"] if k >= 0 else None,
            "newest_bar_time": soxl[-1]["time"], "price": soxl[-1]["close"]}


def describe(call):
    st = call["state"]
    if not st:
        return "no call yet"
    if st["left"]:
        why = f"{st['cause']['why']} at {wall_dt(st['cause']['time']):%H:%M}, cash for {st['left']} more bar(s)"
    else:
        why = f"ended early by {st['ended']}" if st["ended"] else "no red signal"
    return f"{'HOLD SOXL' if st['hold'] else 'CASH'} (after the {wall_dt(call['bar_time']):%a %H:%M} bar: {why})"


def alert_for(call):
    """Title and text for a change to this call."""
    st, bar = call["state"], wall_dt(call["bar_time"])
    last_of_day = bar.hour == 15 and bar.minute == 30
    when = "at the 9:30 open on the next trading day" if last_of_day else "at the next bar's open (now)"
    price = f"SOXL last ${call['price']:.2f}."
    if st["hold"]:
        reason = f"{st['ended']} ended the cash period early" if st["ended"] else "the cash period ran out with no new red"
        return ("BUY SOXL", f"Call is now HOLD SOXL after the {bar:%H:%M} bar ({reason}). Buy {when}. {price}",
                "chart_with_upwards_trend")
    return ("SELL SOXL", f"Call is now CASH after the {bar:%H:%M} bar ({st['cause']['why']}). Sell {when}; "
            f"cash for the next {st['left']} bars unless SOXL aqua or SOXS orange comes first. {price}",
            "chart_with_downwards_trend")


# ---------------------------------------------------------------- the watcher

def check_once(now=None):
    """Look at the latest finished bar and push an alert if the call changed. Returns what it did."""
    now = now or ny_now()
    call = current_call(now)
    if call["state"] is None:
        return "not enough 1h bars yet"
    if wall(now) - call["newest_bar_time"] > STALE_SEC:
        return "no fresh 1h bars (market closed)"
    s = _read(STATE_FILE)
    if s.get("bar_time") == call["bar_time"]:
        return "no new bar"
    hold = call["state"]["hold"]
    first = "hold" not in s
    if not first and s["hold"] != hold:
        title, text, tags = alert_for(call)
        send(title, text, tags=tags)
        log(f"sent {title}: {text}")
    elif first:
        log(f"watching; current call {describe(call)}")
    _write(STATE_FILE, {"hold": hold, "bar_time": call["bar_time"], "checked": f"{now:%Y-%m-%d %H:%M}"})
    return describe(call)


def _loop():
    errors = 0
    while True:
        now = ny_now()
        # bars only finish on weekdays between 10:30 and 16:00, so look from 9:30 to 16:30
        if now.weekday() < 5 and dt.time(9, 30) <= now.time() <= dt.time(16, 30):
            try:
                check_once(now)
                errors = 0
            except Exception as e:  # keep running through network hiccups
                errors += 1
                if errors in (1, 10):
                    log(f"check failed ({e}); will keep retrying")
        time.sleep(CHECK_EVERY_SEC)


def start_in_background():
    """Called by app.py. Starts the watcher if alerts are set up."""
    global _started
    if _started or not config().get("topic"):
        return False
    _started = True
    threading.Thread(target=_loop, name="alerts", daemon=True).start()
    return True


# ---------------------------------------------------------------- commands

def cmd_setup():
    cfg = config()
    if cfg.get("topic"):
        print(f"Already set up. Your channel: {cfg['topic']}")
    else:
        cfg = {"server": "https://ntfy.sh", "topic": "localcharts-" + secrets.token_hex(10)}
        _write(CONFIG_FILE, cfg)
        print(f"Made your channel: {cfg['topic']}")
    print(f"""
On your phone:
  1. Install the free "ntfy" app (App Store or Google Play).
  2. Tap +, enter the channel name  {cfg['topic']}  and tap Subscribe.
     (Or open {cfg['server']}/{cfg['topic']} on the phone.)
  3. Run  python alerts.py test  and a notification should arrive.

Keep the channel name private: anyone who knows it can read and send to it.
From now on, the alerts run whenever the chart app is running.""")


def cmd_test():
    call = current_call()
    send("Local Charts test", f"Alerts are working. Current call: {describe(call)}", priority="default", tags="bell")
    print("Sent. Current call:", describe(call))


def cmd_status():
    print(describe(current_call()))


def cmd_check():
    node = shutil.which("node")
    if not node:
        sys.exit("Node.js is not installed, so the JavaScript side can't be run. Get it from nodejs.org.")
    soxl, soxs = bars_1h("SOXL"), bars_1h("SOXS")
    py = pivot.admf_pivot(soxl, soxs)
    js_code = r"""
const fs = require('fs'), vm = require('vm'), path = require('path');
const [dir, dataFile] = process.argv.slice(2);
const ctx = { Math, Map, Set, Array, Number, Object, JSON }; vm.createContext(ctx);
for (const f of ['static/indicators.js', 'static/strategies.js'])
  vm.runInContext(fs.readFileSync(path.join(dir, f), 'utf8') + ';if (typeof Strat !== "undefined") globalThis.Strat = Strat;', ctx);
const { soxl, soxs } = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
const st = ctx.Strat.admfPivot(soxl, soxs).state;
process.stdout.write(JSON.stringify(st.map(s => s ? [s.hold, s.left, s.soxlColor, s.soxsColor] : null)));
"""
    with tempfile.TemporaryDirectory() as td:
        data = Path(td) / "bars.json"
        data.write_text(json.dumps({"soxl": soxl, "soxs": soxs}))
        script = Path(td) / "check.js"
        script.write_text(js_code)
        res = subprocess.run([node, str(script), str(HERE), str(data)], capture_output=True, text=True)
    if res.returncode:
        sys.exit("The JavaScript side failed:\n" + res.stderr)
    js = json.loads(res.stdout)
    mine = [[s["hold"], s["left"], s["soxlColor"], s["soxsColor"]] if s else None for s in py]
    bad = [i for i, (a, b) in enumerate(zip(mine, js)) if a != b]
    print(f"{len(soxl)} bars compared, {len(bad)} differences" +
          (f" (first at {wall_dt(soxl[bad[0]]['time']):%Y-%m-%d %H:%M})" if bad else ""))
    sys.exit(1 if bad else 0)


def main():
    cmds = {"setup": cmd_setup, "test": cmd_test, "status": cmd_status, "check": cmd_check}
    if len(sys.argv) != 2 or sys.argv[1] not in cmds:
        sys.exit(__doc__)
    cmds[sys.argv[1]]()


if __name__ == "__main__":
    main()
