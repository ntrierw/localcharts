package com.localcharts.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The phone's copy of the desktop data server (app.py): downloads bars from Yahoo Finance, keeps closed
 * bars on disk so later refreshes only ask for new ones, builds TradingView-style 4h bars, and answers
 * the same /api/bars and /api/quotes requests the shared front end (static/app.js) makes.
 *
 * It reproduces what yfinance does to Yahoo's raw data (drops bars outside regular hours, folds Yahoo's
 * separate "live" row into its bar, dates daily/weekly bars at midnight New York time), so the
 * indicators and strategies see the same bars as on the desktop.
 */
public final class MarketData {
    public static final ZoneId ET = ZoneId.of("America/New_York");
    static final long LIVE_REFRESH_SEC = 20;      // ask Yahoo for bars newer than the saved ones this often
    static final double SPLIT_TOLERANCE = 0.02;   // saved vs fresh close differing by more => re-download all

    /** One bar. t is the bar's start in real epoch seconds. */
    public static final class Bar {
        public final long t;
        public final double o, h, l, c, v;
        public Bar(long t, double o, double h, double l, double c, double v) {
            this.t = t; this.o = o; this.h = h; this.l = l; this.c = c; this.v = v;
        }
    }

    /** timeframe -> Yahoo interval, days of history (null = all), group into 4h? */
    static final class Tf {
        final String yint; final Integer days; final boolean to4h;
        Tf(String yint, Integer days, boolean to4h) { this.yint = yint; this.days = days; this.to4h = to4h; }
    }
    static final Map<String, Tf> TIMEFRAMES = new HashMap<>();
    static {
        TIMEFRAMES.put("5m", new Tf("5m", 59, false));
        TIMEFRAMES.put("15m", new Tf("15m", 59, false));
        TIMEFRAMES.put("1h", new Tf("1h", 729, false));
        TIMEFRAMES.put("4h", new Tf("1h", 729, true));
        TIMEFRAMES.put("1D", new Tf("1d", null, false));
        TIMEFRAMES.put("1W", new Tf("1wk", null, false));
    }

    static long span(String yint) {
        switch (yint) {
            case "5m": return 300;
            case "15m": return 900;
            case "1h": return 3600;
            case "1d": return 86400;
            default: return 7 * 86400;
        }
    }

    private static final class Entry {
        final List<Bar> fin, df; final long liveAt;
        Entry(List<Bar> fin, List<Bar> df, long liveAt) { this.fin = fin; this.df = df; this.liveAt = liveAt; }
    }

    private static MarketData instance;
    private final File cacheDir;
    private final Map<String, Entry> cache = new HashMap<>();
    private final Map<String, Object> keyLocks = new HashMap<>();
    private final Map<String, Object[]> quoteCache = new HashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public static synchronized MarketData get(File filesDir) {
        if (instance == null) instance = new MarketData(new File(filesDir, "cache"));
        return instance;
    }

    MarketData(File cacheDir) { this.cacheDir = cacheDir; }

    // ------------------------------------------------------------------ Yahoo

    private static String httpGet(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "LocalCharts/1.0 (Android)");
        con.setRequestProperty("Accept", "application/json");
        try {
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) {
                String msg = "HTTP " + code;
                try {
                    JSONObject err = new JSONObject(body).getJSONObject("chart").getJSONObject("error");
                    msg = err.optString("description", msg);
                } catch (Exception ignored) { }
                throw new YahooError(code, msg);
            }
            return body;
        } finally {
            con.disconnect();
        }
    }

    static final class YahooError extends IOException {
        final int code;
        YahooError(int code, String msg) { super(msg); this.code = code; }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    /** Download bars: from `start` (a New York date) if given, else the last `days` days, else everything. */
    static List<Bar> yahoo(String symbol, String yint, Integer days, LocalDate start) throws IOException {
        long now = System.currentTimeMillis() / 1000;
        long p1;
        if (start == null && days == null) p1 = now - 3122064000L;            // "max", like yfinance
        else {
            if (start == null) start = LocalDate.now(ET).minusDays(days);
            p1 = start.atStartOfDay(ET).toEpochSecond();
        }
        String q = "/v8/finance/chart/" + URLEncoder.encode(symbol, "UTF-8") + "?period1=" + p1 + "&period2=" + now
                + "&interval=" + yint + "&includePrePost=false&events=div%2Csplits";
        String body;
        try {
            body = httpGet("https://query2.finance.yahoo.com" + q);
        } catch (YahooError e) {
            throw e;                      // Yahoo answered (no such symbol, busy, ...): report it
        } catch (IOException e) {         // network trouble: try Yahoo's other server once
            body = httpGet("https://query1.finance.yahoo.com" + q);
        }
        try {
            return parse(new JSONObject(body), yint);
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected reply from Yahoo: " + e.getMessage());
        }
    }

    static List<Bar> parse(JSONObject root, String yint) throws org.json.JSONException {
        JSONObject chart = root.getJSONObject("chart");
        JSONArray results = chart.optJSONArray("result");
        if (results == null || results.length() == 0) return new ArrayList<>();
        JSONObject res = results.getJSONObject(0);
        JSONArray ts = res.optJSONArray("timestamp");
        if (ts == null) return new ArrayList<>();
        JSONObject q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray O = q.optJSONArray("open"), H = q.optJSONArray("high"), L = q.optJSONArray("low"),
                C = q.optJSONArray("close"), V = q.optJSONArray("volume");
        if (O == null || H == null || L == null || C == null) return new ArrayList<>();
        boolean intraday = yint.endsWith("m") || yint.endsWith("h");
        long sp = span(yint);

        // regular session of each day, for dropping bars Yahoo returns outside it (like yfinance)
        Map<LocalDate, long[]> sessions = new HashMap<>();
        JSONArray tps = res.getJSONObject("meta").optJSONArray("tradingPeriods");
        if (intraday && tps != null) {
            for (int i = 0; i < tps.length(); i++) {
                JSONArray day = tps.optJSONArray(i);
                if (day == null || day.length() == 0) continue;
                JSONObject p = day.getJSONObject(0);
                long s = p.getLong("start"), e = p.getLong("end");
                sessions.put(etDate(s), new long[]{s, e});
            }
        }

        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < ts.length(); i++) {
            if (O.isNull(i) || H.isNull(i) || L.isNull(i) || C.isNull(i)) continue;
            long t = ts.getLong(i);
            double o = O.getDouble(i), h = H.getDouble(i), l = L.getDouble(i), c = C.getDouble(i);
            if (Double.isNaN(o) || Double.isNaN(h) || Double.isNaN(l) || Double.isNaN(c)) continue;
            double v = V == null || V.isNull(i) ? 0 : V.getDouble(i);
            if (intraday) {
                long[] s = sessions.get(etDate(t));
                if (s != null && (t >= s[1] || t + sp <= s[0])) continue;
            }
            out.add(new Bar(t, o, h, l, c, v));
        }
        fixLiveSeparate(out, yint);
        if (!intraday) {
            // daily/weekly bars are dated at midnight New York time
            List<Bar> norm = new ArrayList<>(out.size());
            for (Bar b : out) {
                ZonedDateTime z = Instant.ofEpochSecond(b.t).atZone(ET);
                if (z.getMinute() == 0 && (z.getHour() == 22 || z.getHour() == 23)) z = z.plusHours(24 - z.getHour());
                long t = z.toLocalDate().atStartOfDay(ET).toEpochSecond();
                norm.add(new Bar(t, b.o, b.h, b.l, b.c, b.v));
            }
            out = norm;
        }
        return dedupe(out);
    }

    /** Yahoo often returns the bar still forming as its own row after the real one: fold it in (as yfinance does). */
    static void fixLiveSeparate(List<Bar> q, String yint) {
        int n = q.size();
        if (n < 2) return;
        Bar b1 = q.get(n - 1), b2 = q.get(n - 2);
        ZonedDateTime d1 = Instant.ofEpochSecond(b1.t).atZone(ET), d2 = Instant.ofEpochSecond(b2.t).atZone(ET);
        if (yint.equals("1d")) {
            if (d1.toLocalDate().equals(d2.toLocalDate())) q.remove(n - 2);
            return;
        }
        boolean same;
        if (yint.equals("1wk")) {
            same = !d2.isAfter(d1) && d1.get(IsoFields.WEEK_BASED_YEAR) == d2.get(IsoFields.WEEK_BASED_YEAR)
                    && d1.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == d2.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        } else {
            same = b1.t - b2.t < span(yint);
        }
        if (!same || b1.t == b2.t) return;
        Bar m = new Bar(b2.t, b2.o, Math.max(b1.h, b2.h), Math.min(b1.l, b2.l), b1.c, b2.v + b1.v);
        q.set(n - 2, m);
        q.remove(n - 1);
    }

    static List<Bar> dedupe(List<Bar> bars) {
        TreeMap<Long, Bar> m = new TreeMap<>();
        for (Bar b : bars) m.put(b.t, b);   // later wins
        return new ArrayList<>(m.values());
    }

    static LocalDate etDate(long t) {
        return Instant.ofEpochSecond(t).atZone(ET).toLocalDate();
    }

    // ------------------------------------------------------------------ disk cache of closed bars

    private File cacheFile(String symbol, String yint) {
        StringBuilder safe = new StringBuilder();
        for (char ch : symbol.toCharArray()) {
            if (Character.isLetterOrDigit(ch) && ch < 128 || ch == '.' || ch == '-') safe.append(ch);
            else safe.append(String.format("%%%02X", (int) ch));
        }
        return new File(cacheDir, safe + "_" + yint + ".csv");
    }

    private List<Bar> loadDisk(String symbol, String yint) {
        File f = cacheFile(symbol, yint);
        List<Bar> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length != 6 || p[0].equals("time")) continue;
                out.add(new Bar(Long.parseLong(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5])));
            }
            return dedupe(out);
        } catch (Exception e) {   // a damaged file just means downloading again
            return new ArrayList<>();
        }
    }

    private void saveDisk(String symbol, String yint, List<Bar> bars) {
        try {
            cacheDir.mkdirs();
            File f = cacheFile(symbol, yint), tmp = new File(f.getPath() + ".tmp");
            try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
                w.write("time,open,high,low,close,volume\n");
                for (Bar b : bars)
                    w.write(b.t + "," + b.o + "," + b.h + "," + b.l + "," + b.c + "," + b.v + "\n");
            }
            if (!tmp.renameTo(f)) tmp.delete();   // never leave a half-written file behind
        } catch (IOException ignored) { }
    }

    /** Delete all saved bars (forces a full download next time). */
    public synchronized void clearCache() {
        synchronized (cache) { cache.clear(); }
        synchronized (quoteCache) { quoteCache.clear(); }
        File[] fs = cacheDir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** Bars that ended before today (New York time). Those won't change, so they can be saved. */
    static List<Bar> closed(List<Bar> df, String yint) {
        long today = LocalDate.now(ET).atStartOfDay(ET).toEpochSecond(), sp = span(yint);
        List<Bar> out = new ArrayList<>();
        for (Bar b : df) if (b.t + sp <= today) out.add(b);
        return out;
    }

    /** Add fresh bars to the saved ones (fresh wins). Returns null when the overlapping closes don't
     *  match (a split rewrote past prices), meaning the saved bars can't be trusted. */
    static List<Bar> merge(List<Bar> saved, List<Bar> fresh) {
        if (saved.isEmpty()) return fresh;
        if (fresh.isEmpty()) return saved;
        Map<Long, Bar> s = new HashMap<>();
        for (Bar b : saved) s.put(b.t, b);
        List<Double> ratios = new ArrayList<>();
        for (Bar b : fresh) { Bar o = s.get(b.t); if (o != null) ratios.add(b.c / o.c); }
        if (ratios.isEmpty()) return null;
        Collections.sort(ratios);
        int k = ratios.size();
        double med = k % 2 == 1 ? ratios.get(k / 2) : (ratios.get(k / 2 - 1) + ratios.get(k / 2)) / 2;
        if (!(Math.abs(med - 1) <= SPLIT_TOLERANCE)) return null;
        List<Bar> all = new ArrayList<>(saved);
        all.addAll(fresh);
        return dedupe(all);
    }

    private Object lockFor(String key) {
        synchronized (keyLocks) {
            Object l = keyLocks.get(key);
            if (l == null) { l = new Object(); keyLocks.put(key, l); }
            return l;
        }
    }

    private List<Bar> base(String symbol, String yint, Integer days) throws IOException {
        String key = symbol + "|" + yint;
        synchronized (lockFor(key)) {   // one download at a time per symbol/interval
            Entry ent;
            synchronized (cache) { ent = cache.get(key); }
            long now = System.currentTimeMillis() / 1000;
            if (ent != null && now - ent.liveAt < LIVE_REFRESH_SEC) return ent.df;
            List<Bar> fin = ent != null ? ent.fin : loadDisk(symbol, yint);

            // only ask for bars from the last saved one onward; re-getting that bar lets us spot splits
            LocalDate start = null;
            if (!fin.isEmpty()) {
                LocalDate last = etDate(fin.get(fin.size() - 1).t);
                if (days == null || !last.isBefore(LocalDate.now(ET).minusDays(days))) start = last;
            }
            List<Bar> df;
            boolean reset;
            try {
                List<Bar> fresh = yahoo(symbol, yint, days, start);
                df = merge(fin, fresh);
                reset = df == null;
                if (reset) df = start != null ? yahoo(symbol, yint, days, null) : fresh;
            } catch (IOException e) {   // keep showing what we have if Yahoo is unreachable
                if (ent == null && fin.isEmpty()) throw e;
                df = ent != null ? ent.df : fin;
                reset = false;
            }
            List<Bar> newFin = closed(df, yint);
            if (reset || !sameTimes(newFin, fin)) saveDisk(symbol, yint, newFin);
            synchronized (cache) { cache.put(key, new Entry(newFin, df, now)); }
            return df;
        }
    }

    private static boolean sameTimes(List<Bar> a, List<Bar> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i).t != b.get(i).t) return false;
        return true;
    }

    /** Group 1h bars into TradingView-style 4h session bars: 9:30-13:30 and 13:30-16:00 ET. */
    static List<Bar> to4h(List<Bar> df) {
        List<Bar> out = new ArrayList<>();
        long key = Long.MIN_VALUE;
        double o = 0, h = 0, l = 0, c = 0, v = 0;
        for (Bar b : df) {
            ZonedDateTime z = Instant.ofEpochSecond(b.t).atZone(ET);
            int mins = z.getHour() * 60 + z.getMinute();
            int off = mins < 13 * 60 + 30 ? 9 * 60 + 30 : 13 * 60 + 30;
            long k = z.toLocalDate().atStartOfDay(ET).plusMinutes(off).toEpochSecond();
            if (k != key) {
                if (key != Long.MIN_VALUE) out.add(new Bar(key, o, h, l, c, v));
                key = k; o = b.o; h = b.h; l = b.l; c = b.c; v = b.v;
            } else {
                h = Math.max(h, b.h); l = Math.min(l, b.l); c = b.c; v += b.v;
            }
        }
        if (key != Long.MIN_VALUE) out.add(new Bar(key, o, h, l, c, v));
        return out;
    }

    public List<Bar> getBars(String symbol, String tf) throws IOException {
        Tf t = TIMEFRAMES.get(tf);
        if (t == null) throw new IllegalArgumentException("unknown timeframe " + tf);
        List<Bar> df = base(symbol, t.yint, t.days);
        return t.to4h ? to4h(df) : df;
    }

    /** The chart library shows times as UTC, so bars carry New York wall-clock time as if it were UTC. */
    public static long wall(long t) {
        return t + ET.getRules().getOffset(Instant.ofEpochSecond(t)).getTotalSeconds();
    }

    // ------------------------------------------------------------------ the /api responses

    /** A response: HTTP status and JSON body. */
    public static final class Reply {
        public final int status; public final String body;
        Reply(int status, String body) { this.status = status; this.body = body; }
    }

    static String jstr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    static String errMsg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    public Reply apiBars(String symbol, String tf) {
        symbol = (symbol == null || symbol.trim().isEmpty() ? "SOXL" : symbol).trim().toUpperCase(java.util.Locale.ROOT);
        if (tf == null) tf = "4h";
        if (!TIMEFRAMES.containsKey(tf)) return new Reply(400, "{\"error\":" + jstr("unknown timeframe " + tf) + "}");
        List<Bar> bars;
        try {
            bars = getBars(symbol, tf);
        } catch (Exception e) {
            String m = errMsg(e);
            if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                    || e instanceof java.net.ConnectException)
                m = "no internet connection";
            else if (e instanceof YahooError && ((YahooError) e).code == 429)
                m = "Yahoo is busy (too many requests), try again in a minute";
            return new Reply(502, "{\"error\":" + jstr("could not load " + symbol + ": " + m) + "}");
        }
        if (bars.isEmpty()) return new Reply(404, "{\"error\":" + jstr("no data for " + symbol) + "}");
        StringBuilder b = new StringBuilder(bars.size() * 110 + 200);
        b.append("{\"symbol\":").append(jstr(symbol)).append(",\"tf\":").append(jstr(tf)).append(",\"bars\":[");
        for (int i = 0; i < bars.size(); i++) {
            Bar x = bars.get(i);
            if (i > 0) b.append(',');
            b.append("{\"time\":").append(wall(x.t)).append(",\"open\":").append(num(x.o)).append(",\"high\":")
                    .append(num(x.h)).append(",\"low\":").append(num(x.l)).append(",\"close\":").append(num(x.c))
                    .append(",\"volume\":").append(num(x.v)).append('}');
        }
        String updated = LocalDateTime.now().withNano(0).toString();
        if (updated.length() == 16) updated += ":00";
        b.append("],\"updated\":").append(jstr(updated)).append(",\"source\":\"Yahoo Finance\"}");
        return new Reply(200, b.toString());
    }

    private String quote(String s) {
        synchronized (quoteCache) {
            Object[] c = quoteCache.get(s);
            if (c != null && System.currentTimeMillis() / 1000 - (Long) c[0] < LIVE_REFRESH_SEC) return (String) c[1];
        }
        String q;
        try {
            List<Bar> d = getBars(s, "1D");
            if (d.size() < 2) throw new IOException("not enough data");
            double last = d.get(d.size() - 1).c, prev = d.get(d.size() - 2).c;
            q = "{\"symbol\":" + jstr(s) + ",\"last\":" + num(last) + ",\"chg\":" + num(last - prev)
                    + ",\"chgPct\":" + num((last / prev - 1) * 100) + "}";
        } catch (Exception e) {
            q = "{\"symbol\":" + jstr(s) + ",\"error\":" + jstr(errMsg(e)) + "}";
        }
        synchronized (quoteCache) { quoteCache.put(s, new Object[]{System.currentTimeMillis() / 1000, q}); }
        return q;
    }

    public Reply apiQuotes(String symbols) {
        List<String> syms = new ArrayList<>();
        if (symbols != null)
            for (String s : symbols.split(",")) if (!s.trim().isEmpty() && syms.size() < 40) syms.add(s.trim().toUpperCase(java.util.Locale.ROOT));
        List<Future<String>> fs = new ArrayList<>();
        for (String s : syms) fs.add(pool.submit(() -> quote(s)));
        StringBuilder b = new StringBuilder("{\"quotes\":[");
        for (int i = 0; i < fs.size(); i++) {
            if (i > 0) b.append(',');
            try { b.append(fs.get(i).get()); }
            catch (Exception e) { b.append("{\"symbol\":").append(jstr(syms.get(i))).append(",\"error\":\"failed\"}"); }
        }
        return new Reply(200, b.append("]}").toString());
    }

    // for tests
    static List<Bar> listOf(Bar... bars) { return new ArrayList<>(Arrays.asList(bars)); }
    static boolean isWeekday(LocalDate d) { return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY; }
}
