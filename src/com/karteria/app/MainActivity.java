package com.karteria.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_SAVE = 1;
    private static final int REQ_OPEN = 2;
    private static final int REQ_HEALTH = 9;
    private static final int REQ_ROUTE = 10;
    private Health health;
    private String routeSession;
    private long routeStart;
    private boolean openHealthPrivacy;
    private WebView web;
    private String pendingSave;

    private android.widget.FrameLayout root;
    private float sat = 0, sab = 0;   // status / nav bar heights in CSS px

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? Color.parseColor("#121315") : Color.parseColor("#f3f1ec");
        int bar = dark ? Color.parseColor("#1c1e21") : Color.parseColor("#ffffff");

        root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));

        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            // Edge-to-edge (required from API 36): draw behind the bars and pad content ourselves.
            w.setDecorFitsSystemWindows(false);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                // Keyboard open: shrink the page above it. Closed: let the page paint under the nav bar.
                v.setPadding(bars.left, 0, bars.right, ime > 0 ? ime : 0);
                float d = getResources().getDisplayMetrics().density;
                sat = bars.top / d;
                sab = ime > 0 ? 0 : bars.bottom / d;
                pushInsets();
                return insets;
            });
        } else {
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bar);
        }
        applyBarIcons(!dark);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pushInsets();
                if (openHealthPrivacy) { openHealthPrivacy = false; web.evaluateJavascript("window.openHealthPrivacy && openHealthPrivacy()", null); }
            }
        });
        // Opened from Health Connect's "why does this app need access?" link
        openHealthPrivacy = getIntent() != null && "android.intent.action.VIEW_PERMISSION_USAGE".equals(getIntent().getAction());
        if (Health.supported()) health = new Health(this, (id, json) -> runOnUiThread(() ->
                web.evaluateJavascript("window.onHealth(" + id + "," + JSONObject.quote(json) + ")", null)));
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void pushInsets() {
        if (web == null) return;
        web.evaluateJavascript("(function(r){r.style.setProperty('--sat','" + sat + "px');r.style.setProperty('--sab','"
                + sab + "px');})(document.documentElement)", null);
    }

    private void applyBarIcons(boolean light) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) c.setSystemBarsAppearance(light ? mask : 0, mask);
        } else {
            w.getDecorView().setSystemUiVisibility(light
                    ? (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : 0);
        }
    }

    class Bridge {
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String content) {
            runOnUiThread(() -> {
                pendingSave = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_SAVE);
            });
        }

        @JavascriptInterface
        public void scheduleFast(double at, String title, String text, boolean ask) {
            FastAlarm.schedule(MainActivity.this, (long) at, title, text);
            if (ask && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7));
            }
        }

        @JavascriptInterface
        public void setBars(final String bg, final String bar, final boolean light) {
            runOnUiThread(() -> {
                try {
                    int b = Color.parseColor(bg);
                    web.setBackgroundColor(b);
                    root.setBackgroundColor(b);
                    if (Build.VERSION.SDK_INT < 30) {
                        getWindow().setStatusBarColor(b);
                        getWindow().setNavigationBarColor(Color.parseColor(bar));
                    }
                    applyBarIcons(light);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void cancelFast() {
            FastAlarm.cancel(MainActivity.this);
        }

        @JavascriptInterface
        public boolean canExactAlarm() {
            return FastAlarm.canExact(MainActivity.this);
        }

        @JavascriptInterface
        public void openExactAlarmSettings() {
            if (Build.VERSION.SDK_INT >= 31) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                });
            }
        }

        /* ---------- Health Connect (Android 14+) ---------- */
        @JavascriptInterface
        public boolean hcSupported() { return health != null; }

        @JavascriptInterface
        public String hcGranted() { return health == null ? "[]" : health.granted(); }

        @JavascriptInterface
        public void hcRequest() {
            if (health == null) return;
            runOnUiThread(() -> requestPermissions(Health.fullPerms(), REQ_HEALTH));
        }

        @JavascriptInterface
        public void hcManage() {
            if (health == null) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                            .putExtra(Intent.EXTRA_PACKAGE_NAME, getPackageName()));
                } catch (Exception e) {
                    try { startActivity(new Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void hcSync(int id, double from, double to) { if (health != null) health.sync(id, (long) from, (long) to); }

        @JavascriptInterface
        public void hcStats(int id, String sessions) { if (health != null) health.workoutStats(id, sessions); }

        @JavascriptInterface
        public void hcDetail(int id, String sessionId, double start, double end) {
            if (health != null) health.workoutDetail(id, sessionId, (long) start, (long) end);
        }

        @JavascriptInterface
        public void hcRoute(final String sessionId, double start) {
            if (health == null) return;
            routeSession = sessionId;
            routeStart = (long) start;
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent("android.health.connect.action.REQUEST_EXERCISE_ROUTE")
                            .putExtra("android.health.connect.extra.SESSION_ID", sessionId), REQ_ROUTE);
                } catch (Exception e) {
                    web.evaluateJavascript("window.onHealthRoute(" + JSONObject.quote(sessionId) + ",null)", null);
                }
            });
        }

        @JavascriptInterface
        public void openFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_OPEN);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROUTE) {
            String json = (resultCode == RESULT_OK && health != null) ? Health.routeFromResult(data, routeStart) : null;
            web.evaluateJavascript("window.onHealthRoute(" + JSONObject.quote(String.valueOf(routeSession)) + ","
                    + (json == null ? "null" : JSONObject.quote(json)) + ")", null);
            return;
        }
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_SAVE) {
            boolean ok = false;
            if (uri != null && pendingSave != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    os.write(pendingSave.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
            }
            pendingSave = null;
            web.evaluateJavascript("window.onNativeSaved(" + ok + ")", null);
        } else if (requestCode == REQ_OPEN && uri != null) {
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                web.evaluateJavascript("window.onNativeFile(" + JSONObject.quote(sb.toString()) + ")", null);
            } catch (Exception e) {
                web.evaluateJavascript("toast('Could not read that file')", null);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_HEALTH && health != null)
            web.evaluateJavascript("window.onHealthPerms && onHealthPerms(" + JSONObject.quote(health.granted()) + ")", null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && "android.intent.action.VIEW_PERMISSION_USAGE".equals(intent.getAction()))
            web.evaluateJavascript("window.openHealthPrivacy && openHealthPrivacy()", null);
    }

    @Override
    protected void onDestroy() {
        if (health != null) health.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.handleBack && window.handleBack()", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // re-arm with exact timing if the user just granted exact alarms
        if (web != null) web.evaluateJavascript("window.syncFastAlarm && syncFastAlarm(); window.onHealthResume && onHealthResume()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        android.webkit.CookieManager.getInstance().flush();
    }
}
