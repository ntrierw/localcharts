package com.localcharts.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Hosts the chart app in a WebView. The page is served from the APK's assets under a private https
 * origin, and its /api/bars and /api/quotes requests are answered on the phone by MarketData, so the
 * shared front end (static/*.js) runs unchanged and no computer is needed.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    private static final int REQ_NOTIFY = 7;

    private WebView web;
    private MarketData data;
    private String pendingTab;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        data = MarketData.get(getFilesDir());
        Alerts.ensureChannel(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#131722"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#131722"));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // keep the page clear of the status bar, navigation bar and keyboard (Android 15 draws edge to edge)
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                        | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient());   // shows the page's confirm() dialogs and color picker

        pendingTab = getIntent().getStringExtra("tab");
        web.loadUrl(ORIGIN + "/");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String tab = intent.getStringExtra("tab");
        if (tab != null) js("window.lcShowTab && lcShowTab('" + tab.replaceAll("[^a-z]", "") + "')");
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        js("window.lcResume && lcResume()");
        Alerts.catchUp(this);
    }

    @Override
    protected void onPause() {
        web.onPause();
        web.pauseTimers();   // no refreshing in the background; the alerts don't need the page
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // let the page close a dialog or go back to the chart first
        web.evaluateJavascript("window.lcBack ? lcBack() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIFY) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            Alerts.setEnabled(this, ok);
            js("window.lcAlertsChanged && lcAlertsChanged()");
        }
    }

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ------------------------------------------------------------------ serving the page and the data

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("png", "image/png");
        MIME.put("svg", "image/svg+xml");
        MIME.put("json", "application/json");
        MIME.put("txt", "text/plain");
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                if (path.equals("/api/bars")) return json(data.apiBars(u.getQueryParameter("symbol"), u.getQueryParameter("tf")));
                if (path.equals("/api/quotes")) return json(data.apiQuotes(u.getQueryParameter("symbols")));
                String asset;
                if (path.equals("/") || path.equals("/index.html")) asset = "mobile/index.html";
                else if (path.startsWith("/static/") || path.startsWith("/mobile/")) asset = path.substring(1);
                else return notFound();
                if (asset.contains("..")) return notFound();
                InputStream in = getAssets().open(asset);
                String ext = asset.substring(asset.lastIndexOf('.') + 1);
                String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
                WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", in);
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-store");
                r.setResponseHeaders(h);
                return r;
            } catch (IOException e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
            return true;   // links to other sites open in the browser
        }
    }

    private static WebResourceResponse json(MarketData.Reply r) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/json", "UTF-8", r.status, r.status == 200 ? "OK" : "Error", h,
                new ByteArrayInputStream(r.body.getBytes(StandardCharsets.UTF_8)));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    // ------------------------------------------------------------------ what the page can ask the phone

    private final class Bridge {
        @JavascriptInterface
        public String alertsInfo() { return Alerts.info(MainActivity.this); }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String takePendingTab() { String t = pendingTab; pendingTab = null; return t == null ? "" : t; }

        @JavascriptInterface
        public void setAlerts(boolean on) {
            runOnUiThread(() -> {
                if (on && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                    return;   // continues in onRequestPermissionsResult
                }
                Alerts.setEnabled(MainActivity.this, on);
                js("window.lcAlertsChanged && lcAlertsChanged()");
            });
        }

        /** Runs a slow action off the main thread and calls window.lcNative(id, ok, text) when done. */
        @JavascriptInterface
        public void run(String action, String id) {
            new Thread(() -> {
                boolean ok = true;
                String out;
                try {
                    switch (action) {
                        case "test": out = Alerts.test(MainActivity.this); break;
                        case "status": out = Alerts.status(MainActivity.this); break;
                        case "clearCache": data.clearCache(); out = "Saved price history deleted"; break;
                        default: throw new IllegalArgumentException("unknown action " + action);
                    }
                } catch (Exception e) {
                    ok = false;
                    out = MarketData.errMsg(e);
                }
                js("window.lcNative && lcNative(" + MarketData.jstr(id) + "," + ok + "," + MarketData.jstr(out) + ")");
            }, "bridge-" + action).start();
        }

        @JavascriptInterface
        public void openSettings(String which) {
            runOnUiThread(() -> {
                Intent i;
                String pkg = getPackageName();
                switch (which) {
                    case "notifications":
                        i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                        break;
                    case "exact":
                        i = Build.VERSION.SDK_INT >= 31
                                ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg))
                                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                        break;
                    case "battery":
                        i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg));
                        break;
                    default:
                        i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                }
                try { startActivity(i); }
                catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                }
            });
        }
    }
}
