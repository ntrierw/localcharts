package com.localcharts.app;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A refresh must never lose today's bars, and the status must say when Yahoo didn't deliver. */
public class RefreshTest {
    static final LocalDate TODAY = LocalDate.now(MarketData.ET), YDAY = TODAY.minusDays(1);

    static MarketData.Bar bar(LocalDate d, int h, int m, double c) {
        return new MarketData.Bar(d.atTime(h, m).atZone(MarketData.ET).toEpochSecond(), c, c, c, c, 100);
    }

    static List<MarketData.Bar> bars(LocalDate d, int fromHour, int toHour) {
        List<MarketData.Bar> out = new ArrayList<>();
        for (int h = fromHour; h <= toHour; h++) out.add(bar(d, h, 30, 10 + h));
        return out;
    }

    static List<MarketData.Bar> concat(List<MarketData.Bar> a, List<MarketData.Bar> b) {
        List<MarketData.Bar> out = new ArrayList<>(a); out.addAll(b); return out;
    }

    /** What the fake Yahoo answers next. */
    static List<MarketData.Bar> next;
    static IOException fail;

    static MarketData make(File dir) {
        MarketData md = new MarketData(dir);
        md.refreshSec = 0;   // every request asks "Yahoo" again
        md.fetcher = (s, y, days, start) -> {
            if (fail != null) throw fail;
            List<MarketData.Bar> out = new ArrayList<>();
            for (MarketData.Bar b : next) if (start == null || !MarketData.etDate(b.t).isBefore(start)) out.add(b);
            return out;
        };
        return md;
    }

    static JSONObject get(MarketData md) throws Exception {
        MarketData.Reply r = md.apiBars("SOXL", "1h");
        assertEquals(r.body, 200, r.status);
        return new JSONObject(r.body);
    }

    static long lastTime(JSONObject j) throws Exception {
        return j.getJSONArray("bars").getJSONObject(j.getJSONArray("bars").length() - 1).getLong("time");
    }

    @Test
    public void todaysBarsSurviveBadAnswers() throws Exception {
        File dir = Files.createTempDirectory("refresh").toFile();
        MarketData md = make(dir);
        long t1030 = MarketData.wall(bar(TODAY, 10, 30, 0).t), t1130 = MarketData.wall(bar(TODAY, 11, 30, 0).t);

        fail = null;
        next = concat(bars(YDAY, 9, 15), bars(TODAY, 9, 10));
        JSONObject j = get(md);
        assertEquals(t1030, lastTime(j));
        assertTrue(j.isNull("problem"));
        String updated = j.getString("updated");

        next = new ArrayList<>();                                   // Yahoo sends nothing
        j = get(md);
        assertEquals(t1030, lastTime(j));
        assertEquals("Yahoo sent no bars", j.getString("problem"));
        assertEquals(updated, j.getString("updated"));              // not claimed as freshly updated

        next = bars(YDAY, 9, 15);                                   // a server that's a day behind
        j = get(md);
        assertEquals(t1030, lastTime(j));

        fail = new java.net.UnknownHostException("query2.finance.yahoo.com");   // offline
        j = get(md);
        assertEquals(t1030, lastTime(j));
        assertEquals("no internet connection", j.getString("problem"));

        fail = null;                                                // back to normal, with a new bar
        next = concat(bars(YDAY, 9, 15), bars(TODAY, 9, 11));
        j = get(md);
        assertEquals(t1130, lastTime(j));
        assertTrue(j.isNull("problem"));
    }

    @Test
    public void offlineStartSaysTheBarsAreOnlySaved() throws Exception {
        File dir = Files.createTempDirectory("refresh").toFile();
        fail = null;
        next = concat(bars(YDAY, 9, 15), bars(TODAY, 9, 10));
        get(make(dir));                                             // saves yesterday's bars

        fail = new java.net.ConnectException("no route");
        JSONObject j = get(make(dir));                              // app restarted while offline
        assertEquals(MarketData.wall(bar(YDAY, 15, 30, 0).t), lastTime(j));
        assertTrue(j.isNull("updated"));
        assertEquals("no internet connection", j.getString("problem"));
        fail = null;
    }
}
