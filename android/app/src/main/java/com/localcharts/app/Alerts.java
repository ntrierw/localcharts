package com.localcharts.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phone alerts for the SOXL ADMF Pivot: a notification whenever the call changes (BUY or SELL). The
 * Android counterpart of alerts.py, using the phone's own notifications instead of ntfy.
 *
 * Instead of checking every 30 seconds, it wakes up about 2 minutes after each 1h bar closes on weekdays
 * (10:32, 11:32, ... 15:32 and 16:02 New York time), and again every 2 minutes if Yahoo hasn't got that
 * bar yet. It also checks when the phone starts and when the app is opened, so a change missed while the
 * phone was off is still reported (with the bar time). Nothing here places orders.
 */
public final class Alerts {
    private Alerts() { }

    static final long GRACE_SEC = 120;        // wait this long after a bar ends before trusting its data
    static final long STALE_SEC = 75 * 60;    // the newest 1h bar must have started within this long
    static final long RETRY_SEC = 120;
    static final String CHANNEL = "calls";
    static final String ACTION_CHECK = "com.localcharts.app.CHECK";
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter DHM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final Object RUN_LOCK = new Object();

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("alerts", Context.MODE_PRIVATE); }

    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }

    static ZonedDateTime nyNow() { return ZonedDateTime.now(MarketData.ET); }

    /** A New York time as wall-clock seconds stored as if UTC (how bar times are stored). */
    static long wall(ZonedDateTime t) { return t.toLocalDateTime().toEpochSecond(ZoneOffset.UTC); }

    static LocalDateTime wallDt(long s) { return LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC); }

    // ------------------------------------------------------------------ the strategy

    static final class Call {
        Pivot.State state; long barTime, newestBarTime; double price;
    }

    static List<Pivot.Bar> bars1h(Context c, String symbol) throws IOException {
        List<Pivot.Bar> out = new ArrayList<>();
        for (MarketData.Bar b : MarketData.get(c.getFilesDir()).getBars(symbol, "1h"))
            out.add(new Pivot.Bar(MarketData.wall(b.t), b.o, b.h, b.l, b.c, b.v));
        return out;
    }

    /** The strategy's call from the last finished 1h bar, like the app's panel. */
    static Call currentCall(Context c, ZonedDateTime now) throws IOException {
        List<Pivot.Bar> soxl = bars1h(c, "SOXL"), soxs = bars1h(c, "SOXS");
        if (soxl.isEmpty()) throw new IOException("no SOXL data");
        List<Pivot.State> st = Pivot.admfPivot(soxl, soxs);
        int k = Pivot.lastClosed1h(soxl, wall(now) - GRACE_SEC);
        Call call = new Call();
        call.state = k >= 0 ? st.get(k) : null;
        call.barTime = k >= 0 ? soxl.get(k).time : 0;
        call.newestBarTime = soxl.get(soxl.size() - 1).time;
        call.price = soxl.get(soxl.size() - 1).close;
        return call;
    }

    static String describe(Call call) {
        Pivot.State st = call.state;
        if (st == null) return "no call yet";
        String why;
        if (st.left > 0) why = st.causeWhy + " at " + wallDt(st.causeTime).format(HM) + ", cash for " + st.left + " more bar(s)";
        else why = st.ended != null ? "ended early by " + st.ended : "no red signal";
        return (st.hold ? "HOLD SOXL" : "CASH") + " (after the " + wallDt(call.barTime).format(DHM) + " bar: " + why + ")";
    }

    /** Title and text for a change to this call. */
    static String[] alertFor(Call call) {
        Pivot.State st = call.state;
        LocalDateTime bar = wallDt(call.barTime);
        boolean lastOfDay = bar.getHour() == 15 && bar.getMinute() == 30;
        String when = lastOfDay ? "at the 9:30 open on the next trading day" : "at the next bar's open (now)";
        String price = String.format(Locale.US, "SOXL last $%.2f.", call.price);
        if (st.hold) {
            String reason = st.ended != null ? st.ended + " ended the cash period early" : "the cash period ran out with no new red";
            return new String[]{"BUY SOXL", "Call is now HOLD SOXL after the " + bar.format(HM) + " bar (" + reason + "). Buy "
                    + when + ". " + price};
        }
        return new String[]{"SELL SOXL", "Call is now CASH after the " + bar.format(HM) + " bar (" + st.causeWhy + "). Sell "
                + when + "; cash for the next " + st.left + " bars unless SOXL aqua or SOXS orange comes first. " + price};
    }

    // ------------------------------------------------------------------ the check

    /** Look at the latest finished bar and notify if the call changed. Returns what it found. */
    static String checkOnce(Context c, ZonedDateTime now) throws IOException {
        Call call = currentCall(c, now);
        if (call.state == null) return "not enough 1h bars yet";
        if (wall(now) - call.newestBarTime > STALE_SEC) return "no fresh 1h bars (market closed)";
        SharedPreferences p = prefs(c);
        if (p.getLong("bar_time", -1) == call.barTime) return "no new bar · " + describe(call);
        boolean hold = call.state.hold;
        boolean first = !p.contains("hold");
        if (!first && p.getBoolean("hold", true) != hold) {
            String[] a = alertFor(call);
            notify(c, a[0], a[1], hold);
            log(c, "sent " + a[0] + ": " + a[1]);
        } else if (first) {
            log(c, "watching; current call " + describe(call));
        }
        p.edit().putBoolean("hold", hold).putLong("bar_time", call.barTime).apply();
        return describe(call);
    }

    /** Runs a check now (on the calling thread; do not call on the main thread) and schedules the next one. */
    public static void runScheduledCheck(Context c) {
        synchronized (RUN_LOCK) {
            if (!enabled(c)) { cancel(c); return; }
            ZonedDateTime now = nyNow();
            String result;
            boolean retry = false;
            try {
                result = checkOnce(c, now);
                // Yahoo hasn't got the bar that just closed yet: look again shortly
                long expect = expectedLastBar(now);
                long seen = prefs(c).getLong("bar_time", -1);
                retry = expect > 0 && seen < expect && !result.contains("market closed");
            } catch (Exception e) {
                result = "check failed: " + MarketData.errMsg(e);
                retry = true;
                int errs = prefs(c).getInt("errors", 0) + 1;
                prefs(c).edit().putInt("errors", errs).apply();
                if (errs == 1 || errs == 10) log(c, result + " (will keep retrying)");
            }
            if (!result.startsWith("check failed")) prefs(c).edit().putInt("errors", 0).apply();
            prefs(c).edit().putString("last_result", result).putString("last_run", now.format(STAMP)).apply();
            schedule(c, now, retry);
        }
    }

    /** Start time (wall seconds) of the newest 1h bar that should be final by `now`, or 0 outside market days. */
    static long expectedLastBar(ZonedDateTime now) {
        if (!isWeekday(now)) return 0;
        long w = wall(now) - GRACE_SEC, day = w - Math.floorMod(w, 86400);
        long best = 0;
        for (int k = 0; k < 7; k++) {
            long start = day + 9 * 3600 + 30 * 60 + k * 3600L, end = Math.min(start + 3600, day + 16 * 3600);
            if (end <= w) best = start;
        }
        return best;
    }

    static boolean isWeekday(ZonedDateTime t) {
        return t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** The next time to look: ~2 minutes after the next 1h bar closes (weekdays, New York time). */
    static ZonedDateTime nextSlot(ZonedDateTime now) {
        ZonedDateTime day = now.toLocalDate().atStartOfDay(MarketData.ET);
        for (int d = 0; d < 8; d++) {
            ZonedDateTime base = day.plusDays(d);
            if (!isWeekday(base)) continue;
            for (int k = 0; k < 7; k++) {
                LocalTime end = k < 6 ? LocalTime.of(10 + k, 30) : LocalTime.of(16, 0);
                ZonedDateTime slot = base.toLocalDate().atTime(end).atZone(MarketData.ET).plusSeconds(GRACE_SEC + 10);
                if (slot.isAfter(now)) return slot;
            }
        }
        return now.plusHours(1);
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, AlertReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, ZonedDateTime now, boolean retry) {
        if (!enabled(c)) { cancel(c); return; }
        ZonedDateTime next = nextSlot(now);
        LocalTime t = now.toLocalTime();
        if (retry && isWeekday(now) && !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 30))) {
            ZonedDateTime r = now.plusSeconds(RETRY_SEC);
            if (r.isBefore(next)) next = r;
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long at = next.toInstant().toEpochMilli();
        if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c));
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c));
        prefs(c).edit().putString("next_check", next.format(STAMP)).apply();
    }

    static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(alarmIntent(c));
        prefs(c).edit().remove("next_check").apply();
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
        if (on) {
            log(c, "alerts turned on");
            new Thread(() -> runScheduledCheck(c), "alerts-check").start();
        } else {
            log(c, "alerts turned off");
            cancel(c);
        }
    }

    /** Called when the app opens or the phone starts: catch up on anything missed and keep the schedule. */
    public static void catchUp(Context c) {
        if (enabled(c)) new Thread(() -> runScheduledCheck(c), "alerts-check").start();
    }

    // ------------------------------------------------------------------ notifications

    static boolean canExact(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return false;
        return c.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static boolean batteryUnrestricted(Context c) {
        return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ADMF Pivot call changes", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("BUY SOXL / SELL SOXL when the SOXL ADMF Pivot call changes");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    static void notify(Context c, String title, String text, boolean up) {
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("tab", "strategy");
        PendingIntent pi = PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_chart)
                .setColor(up ? 0xFF26A69A : 0xFFEF5350)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        c.getSystemService(NotificationManager.class).notify((int) (System.currentTimeMillis() / 1000 % 100000), n);
    }

    /** Sends a test notification with the current call. Returns the call text. */
    static String test(Context c) throws IOException {
        String d = describe(currentCall(c, nyNow()));
        notify(c, "Local Charts test", "Alerts are working. Current call: " + d, true);
        log(c, "sent test notification");
        return d;
    }

    static String status(Context c) throws IOException {
        return describe(currentCall(c, nyNow()));
    }

    // ------------------------------------------------------------------ log

    static File logFile(Context c) { return new File(c.getFilesDir(), "alerts.log"); }

    static synchronized void log(Context c, String msg) {
        File f = logFile(c);
        try {
            List<String> lines = f.exists() ? new ArrayList<>(Files.readAllLines(f.toPath())) : new ArrayList<>();
            lines.add(nyNow().format(STAMP) + " ET  " + msg);
            if (lines.size() > 300) lines = lines.subList(lines.size() - 300, lines.size());
            try (FileWriter w = new FileWriter(f)) { for (String l : lines) w.write(l + "\n"); }
        } catch (IOException ignored) { }
    }

    /** Everything the Alerts screen shows, as JSON. */
    static String info(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled(c));
            o.put("canNotify", canNotify(c));
            o.put("exact", canExact(c));
            o.put("battery", batteryUnrestricted(c));
            o.put("lastRun", p.getString("last_run", ""));
            o.put("lastResult", p.getString("last_result", ""));
            o.put("nextCheck", p.getString("next_check", ""));
            JSONArray log = new JSONArray();
            File f = logFile(c);
            if (f.exists()) {
                List<String> lines = Files.readAllLines(f.toPath());
                for (int i = lines.size() - 1; i >= Math.max(0, lines.size() - 30); i--) log.put(lines.get(i));
            }
            o.put("log", log);
        } catch (Exception ignored) { }
        return o.toString();
    }
}
