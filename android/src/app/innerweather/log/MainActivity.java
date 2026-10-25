package app.innerweather.log;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Inner Weather Log, standalone. The Daylight app runs from this phone at https://app.innerweather.local/,
 * with its records kept in the app's own storage (window.claude is provided by runtime.js).
 * Every cold start checks the public update feed first, then loads.
 */
public class MainActivity extends Activity {

    static final String HOST = "app.innerweather.local";
    static final String APP_URL = "https://" + HOST + "/";
    static final String FEED = "https://raw.githubusercontent.com/ladygagastani/inner-weather-app/main/feed/";
    static final String ACTION_KEY = "app.innerweather.log.API_KEY";
    static final String ACTION_BACKUP = "app.innerweather.log.BACKUP";
    static final String ACTION_INSTALL_STATUS = "app.innerweather.log.INSTALL_STATUS";
    static final int REQ_FILE = 7;

    FrameLayout root;
    WebView web;
    View stripTop, stripBottom, stripLeft, stripRight;
    LinearLayout splash;
    TextView splashText;
    ProgressBar splashSpin;
    LinearLayout splashButtons;
    int topColor, bottomColor;
    ValueAnimator topAnim, bottomAnim;
    ValueCallback<Uri[]> fileCallback;
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean resumed, loaded, fallbackMargins;
    int[] insets = new int[5];
    String pendingAction;
    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("iwl", MODE_PRIVATE);
        Window w = getWindow();
        int edge = getColor(R.color.edge);
        topColor = bottomColor = edge;
        goEdgeToEdge(w);

        root = new FrameLayout(this);
        root.setBackgroundColor(edge);
        web = new WebView(this);
        setupWebView(web);
        web.setBackgroundColor(edge);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        stripTop = strip(edge);
        stripBottom = strip(edge);
        stripLeft = strip(edge);
        stripRight = strip(edge);
        buildSplash();
        setContentView(root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                applyInsets(insets);
                return insets;
            }
        });
        applyBarIcons(edge, edge);
        pendingAction = getIntent() != null ? getIntent().getAction() : null;
        Reminders.schedule(this);
        checkForUpdates();
    }

    View strip(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        root.addView(v, new FrameLayout.LayoutParams(0, 0));
        return v;
    }

    // ---------- Splash and the update check ----------

    void buildSplash() {
        float dp = getResources().getDisplayMetrics().density;
        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(getColor(R.color.ic_bg));
        splash.setClickable(true);
        int pad = (int) (32 * dp);
        splash.setPadding(pad, pad, pad, pad);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher_round);
        splash.addView(logo, new LinearLayout.LayoutParams((int) (88 * dp), (int) (88 * dp)));

        splashText = new TextView(this);
        splashText.setTextColor(0xFFEEF2FA);
        splashText.setTextSize(16);
        splashText.setGravity(Gravity.CENTER);
        splashText.setText("Checking for updates…");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = (int) (24 * dp);
        splash.addView(splashText, tp);

        splashSpin = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp));
        sp.topMargin = (int) (16 * dp);
        splash.addView(splashSpin, sp);

        splashButtons = new LinearLayout(this);
        splashButtons.setOrientation(LinearLayout.VERTICAL);
        splashButtons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = (int) (20 * dp);
        splash.addView(splashButtons, bp);

        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
    }

    Button splashButton(String label, boolean primary, View.OnClickListener l) {
        float dp = getResources().getDisplayMetrics().density;
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF111C35 : 0xFFEEF2FA);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(999 * dp);
        if (primary) bg.setColor(0xFFC9D6EA); else { bg.setColor(Color.TRANSPARENT); bg.setStroke((int) dp, 0x66C9D6EA); }
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams((int) (240 * dp), (int) (50 * dp));
        p.topMargin = (int) (10 * dp);
        splashButtons.addView(b, p);
        return b;
    }

    void splashStatus(String text, boolean spinning) {
        splashText.setText(text);
        splashSpin.setVisibility(spinning ? View.VISIBLE : View.GONE);
        splashButtons.removeAllViews();
    }

    int installedCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    void checkForUpdates() {
        splashStatus("Checking for updates…", true);
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject v = null;
                try {
                    v = new JSONObject(new String(fetch(FEED + "version.json?t=" + System.currentTimeMillis(), 6000), "UTF-8"));
                } catch (Exception ignored) { }
                final JSONObject feed = v;
                ui.post(new Runnable() { @Override public void run() { onFeed(feed); } });
            }
        }).start();
    }

    void onFeed(final JSONObject feed) {
        if (feed == null) { loadApp(); return; } // offline: open what's on the phone
        int apk = feed.optInt("apk", 0);
        if (apk > installedCode()) {
            splashStatus("A new version of the app is ready (" + feed.optString("apkName", "update") + ").", false);
            splashButton("Install update", true, new View.OnClickListener() {
                @Override public void onClick(View v) { downloadApk(feed); }
            });
            splashButton("Later", false, new View.OnClickListener() {
                @Override public void onClick(View v) { updateWeb(feed); }
            });
            return;
        }
        updateWeb(feed);
    }

    /** Daylight itself (app.html + runtime.js) updates in place, no reinstall. */
    void updateWeb(final JSONObject feed) {
        final int remote = feed.optInt("web", 0);
        if (remote <= webVersion()) { loadApp(); return; }
        splashStatus("Updating Inner Weather…", true);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    byte[] html = fetch(FEED + "app.html?v=" + remote, 20000);
                    byte[] js = fetch(FEED + "runtime.js?v=" + remote, 20000);
                    if (sha256(html).equalsIgnoreCase(feed.optString("appSha256"))
                        && sha256(js).equalsIgnoreCase(feed.optString("runtimeSha256"))) {
                        File dir = new File(getFilesDir(), "web");
                        dir.mkdirs();
                        writeAtomic(new File(dir, "app.html"), html);
                        writeAtomic(new File(dir, "runtime.js"), js);
                        writeAtomic(new File(dir, "version"), String.valueOf(remote).getBytes("UTF-8"));
                        ok = true;
                    }
                } catch (Exception ignored) { }
                final boolean done = ok;
                ui.post(new Runnable() { @Override public void run() {
                    if (!done) Toast.makeText(MainActivity.this, "Couldn't download the update. Opening the current version.", Toast.LENGTH_LONG).show();
                    loadApp();
                } });
            }
        }).start();
    }

    void downloadApk(final JSONObject feed) {
        splashStatus("Downloading the update…", true);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    byte[] apk = fetch(FEED + feed.optString("apkFile", "inner-weather.apk") + "?v=" + feed.optInt("apk"), 60000);
                    if (!sha256(apk).equalsIgnoreCase(feed.optString("apkSha256"))) throw new Exception("checksum");
                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(getPackageName());
                    int id = pi.createSession(params);
                    PackageInstaller.Session s = pi.openSession(id);
                    OutputStream os = s.openWrite("app.apk", 0, apk.length);
                    os.write(apk);
                    s.fsync(os);
                    os.close();
                    Intent back = new Intent(MainActivity.this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent p = PendingIntent.getActivity(MainActivity.this, id, back, flags);
                    s.commit(p.getIntentSender());
                    s.close();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String error = err;
                ui.post(new Runnable() { @Override public void run() {
                    if (error == null) {
                        splashStatus("Follow Android's prompt to install the update.", true);
                    } else {
                        splashStatus("The update didn't download. Check your connection.", false);
                        splashButton("Try again", true, new View.OnClickListener() {
                            @Override public void onClick(View v) { downloadApk(feed); }
                        });
                        splashButton("Open the app", false, new View.OnClickListener() {
                            @Override public void onClick(View v) { updateWeb(feed); }
                        });
                    }
                } });
            }
        }).start();
    }

    void onInstallStatus(Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { }
            }
        }
        if (status != PackageInstaller.STATUS_SUCCESS && !loaded) {
            splashStatus("The update wasn't installed.", false);
            splashButton("Try again", true, new View.OnClickListener() {
                @Override public void onClick(View v) { checkForUpdates(); }
            });
            splashButton("Open the app", false, new View.OnClickListener() {
                @Override public void onClick(View v) { loadApp(); }
            });
        }
    }

    void loadApp() {
        if (!loaded) {
            loaded = true;
            web.loadUrl(APP_URL);
        }
        splash.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
            @Override public void run() { splash.setVisibility(View.GONE); }
        }).start();
        scheduleSample(300);
    }

    static byte[] fetch(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void writeAtomic(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream os = new FileOutputStream(tmp);
        os.write(data);
        os.getFD().sync();
        os.close();
        if (!tmp.renameTo(f)) throw new Exception("rename failed");
    }

    int bundledWebVersion() {
        try { return Integer.parseInt(new String(readAll(getAssets().open("web/version")), "UTF-8").trim()); }
        catch (Exception e) { return 0; }
    }

    int downloadedWebVersion() {
        try { return Integer.parseInt(new String(readAll(new FileInputStream(new File(getFilesDir(), "web/version"))), "UTF-8").trim()); }
        catch (Exception e) { return 0; }
    }

    int webVersion() { return Math.max(bundledWebVersion(), downloadedWebVersion()); }

    /** The newest copy of a web file: downloaded if it's newer than the one inside the APK. */
    byte[] webFile(String name) throws Exception {
        if (downloadedWebVersion() > bundledWebVersion()) {
            File f = new File(getFilesDir(), "web/" + name);
            if (f.exists()) return readAll(new FileInputStream(f));
        }
        return readAll(getAssets().open("web/" + name));
    }

    // ---------- Edge to edge ----------

    @SuppressWarnings("deprecation")
    void goEdgeToEdge(Window w) {
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @SuppressWarnings("deprecation")
    void applyInsets(WindowInsets in) {
        int l, t, r, b, ime = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = in.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = bars.left; t = bars.top; r = bars.right; b = bars.bottom;
            ime = in.getInsets(WindowInsets.Type.ime()).bottom;
        } else {
            l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
            r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                l = Math.max(l, in.getDisplayCutout().getSafeInsetLeft());
                t = Math.max(t, in.getDisplayCutout().getSafeInsetTop());
                r = Math.max(r, in.getDisplayCutout().getSafeInsetRight());
            }
        }
        insets = new int[]{l, t, r, b, ime};
        FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) web.getLayoutParams();
        if (fallbackMargins) {
            // Old WebView without env(safe-area-inset-*): keep the page clear of the bars ourselves.
            wl.setMargins(l, t, r, Math.max(b, ime));
            place(stripTop, -1, t, Gravity.TOP);
            place(stripBottom, -1, b, Gravity.BOTTOM);
            place(stripLeft, l, -1, Gravity.LEFT);
            place(stripRight, r, -1, Gravity.RIGHT);
        } else {
            // Full screen: the page draws behind the status and navigation bars and pads itself with env(safe-area-inset-*).
            // Only the keyboard takes space away.
            wl.setMargins(0, 0, 0, ime > b ? ime : 0);
            place(stripTop, 0, 0, Gravity.TOP);
            place(stripBottom, 0, 0, Gravity.BOTTOM);
            place(stripLeft, 0, 0, Gravity.LEFT);
            place(stripRight, 0, 0, Gravity.RIGHT);
        }
        web.setLayoutParams(wl);
        scheduleSample(150);
    }

    void place(View v, int width, int height, int gravity) {
        v.setLayoutParams(new FrameLayout.LayoutParams(width, height, gravity));
        v.setVisibility((width == 0 || height == 0) ? View.GONE : View.VISIBLE);
    }

    // ---------- Blending the system bars with the page ----------

    final Runnable sampler = new Runnable() {
        @Override public void run() { sample(); }
    };

    void scheduleSample(long delay) {
        ui.removeCallbacks(sampler);
        if (resumed) ui.postDelayed(sampler, delay);
    }

    void sample() {
        if (!resumed || !loaded) return;
        if (Build.VERSION.SDK_INT < 26 || web.getWidth() < 8 || web.getHeight() < 8) {
            scheduleSample(500);
            return;
        }
        final int[] loc = new int[2];
        web.getLocationInWindow(loc);
        final int w = web.getWidth();
        final int h = web.getHeight();
        final int y1 = loc[1] + (fallbackMargins ? 1 : Math.max(1, insets[1] / 2));
        final int y2 = loc[1] + h - (fallbackMargins ? 2 : Math.max(2, insets[3] / 2));
        final Bitmap top = Bitmap.createBitmap(w, 1, Bitmap.Config.ARGB_8888);
        final Bitmap bot = Bitmap.createBitmap(w, 1, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), new Rect(loc[0], y1, loc[0] + w, y1 + 1), top,
                new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override public void onPixelCopyFinished(int res) {
                        if (res == PixelCopy.SUCCESS) setTop(dominant(top));
                        top.recycle();
                    }
                }, ui);
            PixelCopy.request(getWindow(), new Rect(loc[0], y2, loc[0] + w, y2 + 1), bot,
                new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override public void onPixelCopyFinished(int res) {
                        if (res == PixelCopy.SUCCESS) setBottom(dominant(bot));
                        bot.recycle();
                    }
                }, ui);
        } catch (IllegalArgumentException ignored) {
            // window surface not ready yet
        }
        scheduleSample(350);
    }

    /** Most common colour along the row, so a button or card edge doesn't tint the bar. */
    static int dominant(Bitmap row) {
        int w = row.getWidth();
        int n = Math.min(48, w);
        int[] px = new int[n];
        for (int i = 0; i < n; i++) px[i] = row.getPixel((int) ((i + 0.5f) * w / n), 0) | 0xFF000000;
        int best = px[n / 2], bestCount = 0;
        for (int i = 0; i < n; i++) {
            int c = 0;
            for (int j = 0; j < n; j++) if (close(px[i], px[j])) c++;
            if (c > bestCount) { bestCount = c; best = px[i]; }
        }
        return best;
    }

    static boolean close(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) < 10
            && Math.abs(Color.green(a) - Color.green(b)) < 10
            && Math.abs(Color.blue(a) - Color.blue(b)) < 10;
    }

    void setTop(int c) {
        if (close(c, topColor)) return;
        topAnim = animate(topAnim, topColor, c, true);
        topColor = c;
    }

    void setBottom(int c) {
        if (close(c, bottomColor)) return;
        bottomAnim = animate(bottomAnim, bottomColor, c, false);
        bottomColor = c;
    }

    ValueAnimator animate(ValueAnimator old, int from, int to, final boolean isTop) {
        if (old != null) old.cancel();
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(180);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                int c = (Integer) va.getAnimatedValue();
                if (isTop) {
                    stripTop.setBackgroundColor(c);
                    stripLeft.setBackgroundColor(c);
                    stripRight.setBackgroundColor(c);
                } else {
                    stripBottom.setBackgroundColor(c);
                }
            }
        });
        a.start();
        applyBarIcons(isTop ? to : topColor, isTop ? bottomColor : to);
        return a;
    }

    static boolean isLight(int c) {
        double l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
        return l > 0.6;
    }

    @SuppressWarnings("deprecation")
    void applyBarIcons(int top, int bottom) {
        boolean lightTop = isLight(top), lightBottom = isLight(bottom);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            int s = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
            int n = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance((lightTop ? s : 0) | (lightBottom ? n : 0), s | n);
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            f = lightTop ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            if (Build.VERSION.SDK_INT >= 26) {
                f = lightBottom ? (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            d.setSystemUiVisibility(f);
        }
    }

    // ---------- WebView ----------

    @SuppressWarnings("SetJavaScriptEnabled")
    void setupWebView(final WebView v) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        v.addJavascriptInterface(new Store(), "IWLStore");
        v.addJavascriptInterface(new Native(), "IWLNative");
        v.setWebViewClient(new Client());
        v.setWebChromeClient(new Chrome());
        v.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                if (url.startsWith("data:")) saveDataUrl(android.webkit.URLUtil.guessFileName(url, cd, mime), url);
                else if (url.startsWith("http")) openExternal(Uri.parse(url));
            }
        });
        v.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View view, int a, int b, int c, int d) { scheduleSample(60); }
        });
    }

    class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            try {
                String path = u.getPath() == null ? "/" : u.getPath();
                if (path.equals("/") || path.equals("/index.html")) {
                    String html = new String(webFile("app.html"), "UTF-8");
                    // The same page wrapper the claude.ai artifact host adds, plus the on-phone runtime first.
                    html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                        + "<script src=\"/__iwl/runtime.js\"></script></head><body>" + html + "</body></html>";
                    return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                        new ByteArrayInputStream(html.getBytes("UTF-8")));
                }
                if (path.equals("/__iwl/runtime.js")) {
                    return new WebResourceResponse("text/javascript", "utf-8", 200, "OK", headers,
                        new ByteArrayInputStream(webFile("runtime.js")));
                }
            } catch (Exception ignored) { }
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            openExternal(u);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            scheduleSample(100);
            if (pendingAction != null) {
                runAction(pendingAction);
                pendingAction = null;
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            recreate();
            return true;
        }
    }

    class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    // ---------- Storage, files and the API key, for runtime.js ----------

    File storeFile() { return new File(getFilesDir(), "store.json"); }

    class Store {
        /** The saved log, or on the very first start the records copied in from claude.ai (assets/seed.json). */
        @JavascriptInterface
        public String load() {
            try {
                File f = storeFile();
                if (f.exists()) return new String(readAll(new FileInputStream(f)), "UTF-8");
                return new String(readAll(getAssets().open("seed.json")), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void save(String json) {
            try {
                byte[] b = json.getBytes("UTF-8");
                writeAtomic(storeFile(), b);
                // One copy per day, the last 14 days.
                File dir = new File(getFilesDir(), "backups");
                dir.mkdirs();
                String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
                writeAtomic(new File(dir, "store-" + day + ".json"), b);
                File[] all = dir.listFiles();
                if (all != null && all.length > 14) {
                    Arrays.sort(all);
                    for (int i = 0; i < all.length - 14; i++) all[i].delete();
                }
            } catch (Exception e) {
                ui.post(new Runnable() { @Override public void run() {
                    Toast.makeText(MainActivity.this, "Couldn't save to the phone's storage.", Toast.LENGTH_LONG).show();
                } });
            }
        }
    }

    class Native {
        @JavascriptInterface
        public void saveFile(final String name, final String dataUrl) {
            ui.post(new Runnable() { @Override public void run() { saveDataUrl(name, dataUrl); } });
        }

        @JavascriptInterface
        public String getKey() { return prefs.getString("apiKey", ""); }

        @JavascriptInterface
        public void setKey(String key) { prefs.edit().putString("apiKey", key == null ? "" : key.trim()).apply(); }

        /** {"morning":"08:30","day":"13:00","evening":"21:30"}, null for off. */
        @JavascriptInterface
        public void setReminderTimes(String json) {
            prefs.edit().putString("reminderTimes", json == null ? "{}" : json).apply();
            Reminders.schedule(MainActivity.this);
            ui.post(new Runnable() { @Override public void run() { askNotificationPermission(); } });
        }

        @JavascriptInterface
        public String getReminderTimes() { return prefs.getString("reminderTimes", ""); }

        /** Status and navigation bar heights in CSS px, so the page can check that env(safe-area-inset-*) works. */
        @JavascriptInterface
        public String insets() {
            float d = getResources().getDisplayMetrics().density;
            return "{\"top\":" + Math.round(insets[1] / d) + ",\"bottom\":" + Math.round(insets[3] / d) + "}";
        }

        /** This WebView doesn't report safe-area insets: fall back to keeping the page clear of the bars. */
        @JavascriptInterface
        public void needMargins() {
            ui.post(new Runnable() { @Override public void run() {
                if (fallbackMargins) return;
                fallbackMargins = true;
                root.requestApplyInsets();
            } });
        }

        /** {"day":"2026-10-24","open":["morning","evening"]}; finished moments lose their reminder notification. */
        @JavascriptInterface
        public void setOpenMoments(String json) {
            prefs.edit().putString("openMoments", json == null ? "{}" : json).apply();
            for (String m : Reminders.MOMENTS) if (!Reminders.isOpen(MainActivity.this, m)) Reminders.clear(MainActivity.this, m);
        }
    }

    void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        }
    }

    void saveDataUrl(String name, String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            String meta = dataUrl.substring(5, comma);
            String mime = meta.split(";")[0];
            if (mime.isEmpty()) mime = "application/octet-stream";
            byte[] bytes = meta.endsWith(";base64")
                ? android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
                : Uri.decode(dataUrl.substring(comma + 1)).getBytes("UTF-8");
            saveBytes(name, mime, bytes);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save that file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void saveBytes(String name, String mime, byte[] bytes) throws Exception {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe);
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
            android.content.ContentResolver cr = getContentResolver();
            Uri item = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            OutputStream os = cr.openOutputStream(item);
            os.write(bytes);
            os.close();
            cv.clear();
            cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
            cr.update(item, cv, null, null);
            Toast.makeText(this, "Saved " + safe + " to Downloads", Toast.LENGTH_LONG).show();
        } else {
            File f = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), safe);
            FileOutputStream os = new FileOutputStream(f);
            os.write(bytes);
            os.close();
            Toast.makeText(this, "Saved to " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) { }
    }

    // ---------- Shortcuts, intents, lifecycle ----------

    void runAction(String action) {
        if (ACTION_KEY.equals(action)) {
            web.evaluateJavascript("window.iwlAskKey&&window.iwlAskKey('Paste a new Anthropic API key to change the one Ask Claude uses.')", null);
        } else if (ACTION_BACKUP.equals(action)) {
            web.evaluateJavascript("window.__iwlFlush&&window.__iwlFlush()", null);
            ui.postDelayed(new Runnable() { @Override public void run() {
                try {
                    String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
                    saveBytes("inner-weather-backup-" + day + ".json", "application/json", readAll(new FileInputStream(storeFile())));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Nothing to back up yet.", Toast.LENGTH_LONG).show();
                }
            } }, 400);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String a = intent.getAction();
        if (ACTION_INSTALL_STATUS.equals(a)) { onInstallStatus(intent); return; }
        setIntent(intent);
        if (loaded) runAction(a); else pendingAction = a;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (loaded) {
            // Let the page close its own sheet first (Escape), then leave.
            web.evaluateJavascript(
                "(function(){var d=document.querySelector('#iwl-key');if(d){d.remove();return 1}" +
                "var open=[].slice.call(document.querySelectorAll('[role=dialog]')).filter(function(e){return e.offsetParent!==null&&!e.hidden});" +
                "if(open.length){document.activeElement&&document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));" +
                "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return 1}" +
                "if(history.length>1&&location.hash){history.back();return 1}return 0})()",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String r) {
                        if (!"1".equals(r)) moveTaskToBack(true);
                    }
                });
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
        scheduleSample(200);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(sampler);
        web.evaluateJavascript("window.__iwlFlush&&window.__iwlFlush()", null);
        web.onPause();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        scheduleSample(250);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
