package com.localcharts.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SOXL ADMF Pivot in Java, for the phone's background alerts. A line-for-line port of pivot.py, itself a
 * port of Ind.admf() (colors only) and Strat.admfPivot() in static/indicators.js and static/strategies.js,
 * using the same order of floating-point operations so the colors and calls are identical.
 * PivotTest compares it with the JavaScript bar by bar.
 *
 * The admf port is derived from "ADMF Divergence (High/Low Highlight + Alerts)" (c) stonksforlife,
 * published under the Mozilla Public License 2.0 (https://mozilla.org/MPL/2.0/), like indicators.js.
 */
public final class Pivot {
    private Pivot() { }

    /** A bar with New York wall-clock time stored as if UTC (the `time` the front end uses). */
    public static final class Bar {
        public final long time;
        public final double open, high, low, close, volume;
        public Bar(long time, double open, double high, double low, double close, double volume) {
            this.time = time; this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
        }
    }

    public static final class State {
        public boolean hold;
        public int left;
        public String causeWhy;     // null when not in a cash period
        public long causeTime;
        public String bear, bull, ended, soxlColor, soxsColor;
    }

    private static final double NA = Double.NaN;

    private static boolean nn(double v) { return !Double.isNaN(v); }

    static double[] rma(double[] src, int p) {
        double[] out = new double[src.length];
        double e = NA, s = 0.0;
        int n = 0;
        for (int i = 0; i < src.length; i++) {
            double v = src[i];
            if (nn(v)) {
                if (!nn(e)) {
                    s += v;
                    n++;
                    if (n == p) e = s / p;
                } else {
                    e = (e * (p - 1) + v) / p;
                }
            }
            out[i] = e;
        }
        return out;
    }

    static double[] highest(double[] src, int p) {
        double[] out = new double[src.length];
        for (int i = 0; i < src.length; i++) {
            if (i < p - 1) { out[i] = NA; continue; }
            double m = NA;
            for (int j = i - p + 1; j <= i; j++) if (nn(src[j]) && (!nn(m) || src[j] > m)) m = src[j];
            out[i] = m;
        }
        return out;
    }

    static double[] lowest(double[] src, int p) {
        double[] out = new double[src.length];
        for (int i = 0; i < src.length; i++) {
            if (i < p - 1) { out[i] = NA; continue; }
            double m = NA;
            for (int j = i - p + 1; j <= i; j++) if (nn(src[j]) && (!nn(m) || src[j] < m)) m = src[j];
            out[i] = m;
        }
        return out;
    }

    /** Pine ta.valuewhen(cond, src, 1): the value src had at the 2nd most recent bar where cond was true. */
    static double[] valuewhen1(boolean[] cond, double[] src) {
        double[] out = new double[cond.length];
        double last = NA, prev = NA;
        int count = 0;
        for (int i = 0; i < cond.length; i++) {
            if (cond[i]) { prev = last; last = src[i]; count++; }
            out[i] = count > 1 ? prev : NA;
        }
        return out;
    }

    private static double[] shift1(double[] a) {
        double[] out = new double[a.length];
        if (a.length > 0) out[0] = NA;
        System.arraycopy(a, 0, out, 1, Math.max(0, a.length - 1));
        return out;
    }

    private static boolean gt(double a, double b) { return nn(a) && nn(b) && a > b; }
    private static boolean lt(double a, double b) { return nn(a) && nn(b) && a < b; }

    /** 'red'/'orange'/'lime'/'aqua'/'blue' per bar. */
    public static String[] admfColors(List<Bar> bars, int length, int lookback) {
        int n = bars.size();
        double[] x = new double[n];
        if (n > 0) x[0] = NA;
        for (int i = 1; i < n; i++) {
            Bar b = bars.get(i);
            double c1 = bars.get(i - 1).close;
            double tr = Math.max(Math.max(b.high - b.low, Math.abs(b.high - c1)), Math.abs(b.low - c1));
            if (tr == 0) { x[i] = NA; continue; }
            double r = (b.close - c1) / tr;
            r = 1 * r + (r > 0 ? 1 : r < 0 ? -1 : 0) * 0;
            double trl = Math.min(b.low, c1), trh = Math.max(b.high, c1);
            double vol = b.volume * (c1 + b.close + trl + trh) / 4;
            x[i] = vol * r;
        }
        double[] hist = rma(x, length);
        double[] H = new double[n], L = new double[n];
        for (int i = 0; i < n; i++) { H[i] = bars.get(i).high; L[i] = bars.get(i).low; }
        double[] ph = highest(H, lookback), pl = lowest(L, lookback), hh = highest(hist, lookback), hl = lowest(hist, lookback);
        boolean[] cph = new boolean[n], cpl = new boolean[n], chh = new boolean[n], chl = new boolean[n];
        for (int i = 0; i < n; i++) {
            cph[i] = nn(ph[i]) && H[i] == ph[i];
            cpl[i] = nn(pl[i]) && L[i] == pl[i];
            chh[i] = nn(hh[i]) && hist[i] == hh[i];
            chl[i] = nn(hl[i]) && hist[i] == hl[i];
        }
        double[] phPrev = valuewhen1(cph, shift1(ph)), plPrev = valuewhen1(cpl, shift1(pl));
        double[] hhPrev = valuewhen1(chh, shift1(hh)), hlPrev = valuewhen1(chl, shift1(hl));
        String[] colors = new String[n];
        for (int i = 0; i < n; i++) {
            double hi = H[i], lo = L[i], h = hist[i];
            boolean regBear = gt(hi, phPrev[i]) && lt(h, hhPrev[i]);
            boolean regBull = lt(lo, plPrev[i]) && gt(h, hlPrev[i]);
            boolean hidBear = lt(hi, phPrev[i]) && gt(h, hhPrev[i]);
            boolean hidBull = gt(lo, plPrev[i]) && lt(h, hlPrev[i]);
            colors[i] = regBear ? "red" : hidBear ? "orange" : regBull ? "lime" : hidBull ? "aqua" : "blue";
        }
        return colors;
    }

    /** Same rule as Strat.admfPivot(). One state per SOXL bar (null during warm-up). */
    public static List<State> admfPivot(List<Bar> soxl, List<Bar> soxs, int cashBars, int warmup) {
        String[] cl = admfColors(soxl, 14, 20);
        String[] csAll = admfColors(soxs, 14, 20);
        Map<Long, String> byTime = new HashMap<>();
        for (int i = 0; i < soxs.size(); i++) byTime.put(soxs.get(i).time, csAll[i]);
        List<State> state = new ArrayList<>();
        int left = 0;
        String causeWhy = null;
        long causeTime = 0;
        for (int i = 0; i < soxl.size(); i++) {
            String cs = byTime.get(soxl.get(i).time);
            String bear = Objects.equals(cs, "red") ? "SOXS red" : cl[i].equals("red") ? "SOXL red" : null;
            String bull = cl[i].equals("aqua") ? "SOXL aqua" : Objects.equals(cs, "orange") ? "SOXS orange" : null;
            String ended = null;
            if (bear != null) { left = cashBars; causeWhy = bear; causeTime = soxl.get(i).time; }
            else if (bull != null && left > 0) { left = 0; ended = bull; }
            if (i >= warmup) {
                State s = new State();
                s.hold = left == 0;
                s.left = left;
                s.causeWhy = left > 0 ? causeWhy : null;
                s.causeTime = left > 0 ? causeTime : 0;
                s.bear = bear; s.bull = bull; s.ended = ended; s.soxlColor = cl[i]; s.soxsColor = cs;
                state.add(s);
            } else {
                state.add(null);
            }
            if (left > 0) left--;
        }
        return state;
    }

    public static List<State> admfPivot(List<Bar> soxl, List<Bar> soxs) { return admfPivot(soxl, soxs, 4, 60); }

    /** Index of the last finished 1h bar (wall-clock seconds); the 15:30 bar ends at the 16:00 close. */
    public static int lastClosed1h(List<Bar> bars, long nowWall) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            long t = bars.get(i).time;
            if (Math.min(t + 3600, t - Math.floorMod(t, 86400) + 16 * 3600) <= nowWall) return i;
        }
        return -1;
    }
}
