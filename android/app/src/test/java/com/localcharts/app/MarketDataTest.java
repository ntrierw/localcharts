package com.localcharts.app;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** How Yahoo's raw replies are turned into bars (matching what yfinance gives the desktop app). */
public class MarketDataTest {

    static long et(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(MarketData.ET).toEpochSecond();
    }

    static String reply(long[] ts, double[] close, String tradingPeriods) {
        StringBuilder t = new StringBuilder(), o = new StringBuilder(), v = new StringBuilder();
        for (int i = 0; i < ts.length; i++) {
            String sep = i > 0 ? "," : "";
            t.append(sep).append(ts[i]);
            o.append(sep).append(close[i]);
            v.append(sep).append(1000 * (i + 1));
        }
        return "{\"chart\":{\"result\":[{\"meta\":{" + (tradingPeriods == null ? "" : "\"tradingPeriods\":" + tradingPeriods)
                + "},\"timestamp\":[" + t + "],\"indicators\":{\"quote\":[{\"open\":[" + o + "],\"high\":[" + o + "],\"low\":["
                + o + "],\"close\":[" + o + "],\"volume\":[" + v + "]}]}}],\"error\":null}}";
    }

    @Test
    public void hourlyDropsTheBarAfterTheClose() throws Exception {
        long s = et(2026, 11, 13, 9, 30), e = et(2026, 11, 13, 16, 0);
        String tp = "[[{\"start\":" + s + ",\"end\":" + e + "}]]";
        long[] ts = {et(2026, 11, 13, 14, 30), et(2026, 11, 13, 15, 30), et(2026, 11, 13, 16, 0)};
        List<MarketData.Bar> b = MarketData.parse(new JSONObject(reply(ts, new double[]{1, 2, 3}, tp)), "1h");
        assertEquals(2, b.size());
        assertEquals(ts[1], b.get(1).t);
    }

    @Test
    public void weeklyFoldsTheLiveRowIntoItsWeek() throws Exception {
        long[] ts = {et(2026, 11, 2, 0, 0), et(2026, 11, 9, 0, 0), et(2026, 11, 13, 16, 0)};
        List<MarketData.Bar> b = MarketData.parse(new JSONObject(reply(ts, new double[]{5, 6, 7}, null)), "1wk");
        assertEquals(2, b.size());
        assertEquals(et(2026, 11, 9, 0, 0), b.get(1).t);
        assertEquals(7, b.get(1).c, 0);          // close from the live row
        assertEquals(2000 + 3000, b.get(1).v, 0);  // volumes added
        assertEquals(7, b.get(1).h, 0);
    }

    @Test
    public void dailyBarsAreDatedAtMidnight() throws Exception {
        long[] ts = {et(2026, 11, 12, 9, 30), et(2026, 11, 13, 9, 30)};
        List<MarketData.Bar> b = MarketData.parse(new JSONObject(reply(ts, new double[]{1, 2}, null)), "1d");
        assertEquals(et(2026, 11, 13, 0, 0), b.get(1).t);
    }

    @Test
    public void fourHourBarsFollowTheSession() {
        List<MarketData.Bar> h = MarketData.listOf(
                new MarketData.Bar(et(2026, 11, 13, 9, 30), 10, 11, 9, 10.5, 100),
                new MarketData.Bar(et(2026, 11, 13, 10, 30), 10.5, 12, 10, 11, 100),
                new MarketData.Bar(et(2026, 11, 13, 11, 30), 11, 11.5, 8, 9, 100),
                new MarketData.Bar(et(2026, 11, 13, 12, 30), 9, 9.5, 8.5, 9.2, 100),
                new MarketData.Bar(et(2026, 11, 13, 13, 30), 9.2, 10, 9, 9.9, 50),
                new MarketData.Bar(et(2026, 11, 13, 14, 30), 9.9, 10.2, 9.8, 10, 50),
                new MarketData.Bar(et(2026, 11, 13, 15, 30), 10, 10.1, 9.7, 9.8, 50));
        List<MarketData.Bar> f = MarketData.to4h(h);
        assertEquals(2, f.size());
        assertEquals(et(2026, 11, 13, 9, 30), f.get(0).t);
        assertEquals(10, f.get(0).o, 0); assertEquals(12, f.get(0).h, 0); assertEquals(8, f.get(0).l, 0);
        assertEquals(9.2, f.get(0).c, 0); assertEquals(400, f.get(0).v, 0);
        assertEquals(et(2026, 11, 13, 13, 30), f.get(1).t);
        assertEquals(150, f.get(1).v, 0);
    }

    @Test
    public void mergeSpotsASplit() {
        List<MarketData.Bar> saved = MarketData.listOf(new MarketData.Bar(1, 1, 1, 1, 100, 1), new MarketData.Bar(2, 1, 1, 1, 100, 1));
        List<MarketData.Bar> same = MarketData.listOf(new MarketData.Bar(2, 1, 1, 1, 100.5, 1), new MarketData.Bar(3, 1, 1, 1, 101, 1));
        assertEquals(3, MarketData.merge(saved, same).size());
        List<MarketData.Bar> split = MarketData.listOf(new MarketData.Bar(2, 1, 1, 1, 10, 1), new MarketData.Bar(3, 1, 1, 1, 10, 1));
        assertNull(MarketData.merge(saved, split));
    }

    @Test
    public void wallClockTime() {
        long t = et(2026, 7, 1, 9, 30);   // summer (EDT)
        assertEquals(LocalDate.of(2026, 7, 1).toEpochDay() * 86400 + 9 * 3600 + 1800, MarketData.wall(t));
        long w = et(2026, 1, 5, 15, 30);  // winter (EST)
        assertEquals(LocalDate.of(2026, 1, 5).toEpochDay() * 86400 + 15 * 3600 + 1800, MarketData.wall(w));
    }

    @Test
    public void alertScheduleFollowsBarCloses() {
        ZonedDateTime fri = LocalDateTime.of(2026, 11, 13, 10, 0).atZone(MarketData.ET);
        assertEquals(LocalDateTime.of(2026, 11, 13, 10, 32, 10), Alerts.nextSlot(fri).toLocalDateTime());
        ZonedDateTime late = LocalDateTime.of(2026, 11, 13, 15, 40).atZone(MarketData.ET);
        assertEquals(LocalDateTime.of(2026, 11, 13, 16, 2, 10), Alerts.nextSlot(late).toLocalDateTime());
        ZonedDateTime after = LocalDateTime.of(2026, 11, 13, 16, 5).atZone(MarketData.ET);   // Friday evening -> Monday
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 32, 10), Alerts.nextSlot(after).toLocalDateTime());
        // the bar that should be final: at 11:33 the 10:30 bar
        ZonedDateTime t = LocalDateTime.of(2026, 11, 13, 11, 33).atZone(MarketData.ET);
        assertEquals(Alerts.wall(LocalDateTime.of(2026, 11, 13, 10, 30).atZone(MarketData.ET)), Alerts.expectedLastBar(t));
        ZonedDateTime close = LocalDateTime.of(2026, 11, 13, 16, 3).atZone(MarketData.ET);
        assertEquals(Alerts.wall(LocalDateTime.of(2026, 11, 13, 15, 30).atZone(MarketData.ET)), Alerts.expectedLastBar(close));
    }
}
