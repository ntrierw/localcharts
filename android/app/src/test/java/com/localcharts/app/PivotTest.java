package com.localcharts.app;

import org.json.JSONArray;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * The phone's alerts use a Java copy of the ADMF Pivot. This runs the app's JavaScript (static/indicators.js
 * and static/strategies.js) with Node on the same bars and checks every bar's call and colors match, the
 * way `python alerts.py check` does for the Python copy. Skipped when Node.js isn't installed.
 */
public class PivotTest {

    static List<Pivot.Bar> walk(long seed, int n, double start, boolean dropSome) {
        Random r = new Random(seed);
        List<Pivot.Bar> out = new ArrayList<>();
        long day = 1735689600L;   // 2025-01-01, wall-clock seconds
        double c = start;
        int k = 0;
        while (out.size() < n) {
            for (int h = 0; h < 7 && out.size() < n; h++, k++) {
                long t = day + 9 * 3600 + 30 * 60 + h * 3600L;
                double o = c * (1 + r.nextGaussian() * 0.003);
                c = o * (1 + r.nextGaussian() * 0.02 + 0.001 * Math.sin(k / 40.0));
                double hi = Math.max(o, c) * (1 + Math.abs(r.nextGaussian()) * 0.006);
                double lo = Math.min(o, c) * (1 - Math.abs(r.nextGaussian()) * 0.006);
                if (k % 97 == 5) { hi = lo = o = c; }                      // flat bar (true range 0)
                if (dropSome && k % 53 == 7) continue;                   // a bar missing on one symbol
                out.add(new Pivot.Bar(t, o, hi, lo, c, Math.round(1e6 + r.nextDouble() * 9e6)));
            }
            day += 86400;
        }
        return out;
    }

    static String json(List<Pivot.Bar> bars) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < bars.size(); i++) {
            Pivot.Bar x = bars.get(i);
            if (i > 0) b.append(',');
            b.append("{\"time\":").append(x.time).append(",\"open\":").append(x.open).append(",\"high\":").append(x.high)
                    .append(",\"low\":").append(x.low).append(",\"close\":").append(x.close).append(",\"volume\":")
                    .append(x.volume).append('}');
        }
        return b.append(']').toString();
    }

    static File staticDir() {
        for (String p : new String[]{"../../static", "../static", "static"}) {
            File f = new File(p);
            if (new File(f, "strategies.js").exists()) return f;
        }
        throw new IllegalStateException("static/ not found");
    }

    static boolean haveNode() {
        try { return new ProcessBuilder("node", "--version").start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @Test
    public void matchesJavaScript() throws Exception {
        Assume.assumeTrue("Node.js not installed", haveNode());
        for (long seed = 1; seed <= 3; seed++) {
            List<Pivot.Bar> soxl = walk(seed, 1500, 30, false), soxs = walk(seed + 100, 1500, 40, true);
            File dir = Files.createTempDirectory("pivot").toFile();
            File data = new File(dir, "bars.json"), script = new File(dir, "check.js");
            Files.write(data.toPath(), ("{\"soxl\":" + json(soxl) + ",\"soxs\":" + json(soxs) + "}").getBytes(StandardCharsets.UTF_8));
            Files.write(script.toPath(), String.join("\n",
                    "const fs = require('fs'), vm = require('vm'), path = require('path');",
                    "const [dir, dataFile] = process.argv.slice(2);",
                    "const ctx = { Math, Map, Set, Array, Number, Object, JSON }; vm.createContext(ctx);",
                    "for (const f of ['indicators.js', 'strategies.js'])",
                    "  vm.runInContext(fs.readFileSync(path.join(dir, f), 'utf8') + ';if (typeof Strat !== \"undefined\") globalThis.Strat = Strat;', ctx);",
                    "const { soxl, soxs } = JSON.parse(fs.readFileSync(dataFile, 'utf8'));",
                    "const st = ctx.Strat.admfPivot(soxl, soxs).state;",
                    "process.stdout.write(JSON.stringify(st.map(s => s ? [s.hold, s.left, s.soxlColor, s.soxsColor, s.ended] : null)));"
            ).getBytes(StandardCharsets.UTF_8));
            Process p = new ProcessBuilder("node", script.getPath(), staticDir().getPath(), data.getPath()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("node failed: " + out, 0, p.waitFor());
            JSONArray js = new JSONArray(out);

            List<Pivot.State> mine = Pivot.admfPivot(soxl, soxs);
            assertEquals(js.length(), mine.size());
            int holds = 0, cash = 0;
            for (int i = 0; i < mine.size(); i++) {
                Pivot.State s = mine.get(i);
                if (js.isNull(i)) { assertEquals("bar " + i, null, s); continue; }
                JSONArray e = js.getJSONArray(i);
                String where = "seed " + seed + " bar " + i;
                assertEquals(where, e.getBoolean(0), s.hold);
                assertEquals(where, e.getInt(1), s.left);
                assertEquals(where, e.getString(2), s.soxlColor);
                assertEquals(where, e.isNull(3) ? null : e.getString(3), s.soxsColor);
                assertEquals(where, e.isNull(4) ? null : e.getString(4), s.ended);
                if (s.hold) holds++; else cash++;
            }
            // make sure the test data actually exercises both calls
            org.junit.Assert.assertTrue(holds > 50 && cash > 50);
        }
    }

    @Test
    public void lastClosed1h() {
        List<Pivot.Bar> b = new ArrayList<>();
        long day = 1735776000L;   // a day at 00:00 wall-clock
        for (int h = 0; h < 7; h++) b.add(new Pivot.Bar(day + 9 * 3600 + 1800 + h * 3600L, 1, 1, 1, 1, 1));
        assertEquals(-1, Pivot.lastClosed1h(b, day + 10 * 3600 + 1799));
        assertEquals(0, Pivot.lastClosed1h(b, day + 10 * 3600 + 1800));
        assertEquals(5, Pivot.lastClosed1h(b, day + 15 * 3600 + 3599));
        assertEquals(6, Pivot.lastClosed1h(b, day + 16 * 3600));     // the 15:30 bar ends at 16:00
    }
}
