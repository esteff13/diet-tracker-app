package com.armon.diettracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Thin Android shell around the Diet Tracker web app (Apps Script).
 * - All features come from the web app, so most updates never need a new APK.
 * - Clients never paste anything: the "Open my tracker" button on the launcher page opens
 *   diettracker://open?id=CODE, which this app saves.
 * - When a newer APK is published on GitHub, an "Update ready" popup offers it (user taps Install).
 */
public class MainActivity extends Activity {

    static final String EXEC = "https://script.google.com/macros/s/AKfycbx5PJGv0YFlTcxA7xWD7jxInKOFGKXkkC1XBgDPKMMRzpwsoTrWlmxXRsnMgRzqHC5qrQ/exec";
    static final String RELEASES = "https://api.github.com/repos/esteff13/diet-tracker-app/releases/latest";
    static final String APP_BASE = "https://diet-tracker.app/";

    private static final String PREFS = "diet_tracker";
    private static final String KEY_LINK = "link";
    private static final String KEY_CHECKED = "update_checked_at";
    private static final int REQ_FILE = 41;

    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;
    private Uri cameraUri;
    private File pendingApk;          // downloaded update waiting for "allow installs" permission
    private boolean updateDialogUp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B1220"));
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("app".equals(u.getScheme())) { askForLink(true); return true; }   // "I have a link" on the welcome screen
                if (isAppHost(u)) return false;          // stay inside the app
                openOutside(u);                          // Sheet links etc. open in the browser / Sheets app
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                return openPhotoPicker(params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            }
        });

        if (!handleMagicLink(getIntent())) {
            String link = savedLink();
            if (link.isEmpty()) showWelcome();
            else loadApp(link);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleMagicLink(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingApk != null && canInstall()) { File f = pendingApk; pendingApk = null; installApk(f); return; }
        checkForUpdate(false);
    }

    /* ---------- link setup ---------- */

    /** diettracker://open?id=CODE -> save the client's link and open it. */
    private boolean handleMagicLink(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"diettracker".equals(data.getScheme())) return false;
        String id = data.getQueryParameter("id");
        id = id == null ? "" : id.replaceAll("[^A-Za-z0-9]", "");
        if (id.isEmpty()) return false;
        String link = EXEC + "?id=" + id;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LINK, link).apply();
        loadApp(link);
        web.clearHistory();
        Toast.makeText(this, "Your tracker is connected ✓", Toast.LENGTH_SHORT).show();
        return true;
    }

    private String savedLink() {
        String link = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LINK, "");
        if (link.isEmpty()) link = BuildConfig.DEFAULT_LINK;
        return link == null ? "" : link;
    }

    private void showWelcome() {
        String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0B1220;color:#e2e8f0;"
            + "font-family:system-ui,sans-serif;text-align:center;padding:24px;box-sizing:border-box}"
            + ".o{width:64px;height:64px;border-radius:50%;margin:0 auto 18px;background:rgba(56,189,248,.12);color:#38bdf8;font-size:30px;line-height:64px}"
            + "h1{font-size:22px;margin:0 0 10px}p{color:#94a3b8;line-height:1.5;margin:0 0 26px}a{color:#64748b;font-size:14px}</style></head><body><div>"
            + "<div class='o'>&#10022;</div><h1>Almost there</h1>"
            + "<p>Open your welcome message and tap <b style='color:#e2e8f0'>Open my tracker</b>.<br>This app connects by itself.</p>"
            + "<a href='app://link'>I have a link instead</a></div></body></html>";
        web.loadDataWithBaseURL(APP_BASE, html, "text/html", "UTF-8", null);
    }

    /**
     * Loads the web app inside a full-screen frame on a local page. Google only shows its
     * "created by a Google Apps Script user" bar when the app is opened directly, not when framed.
     */
    private void loadApp(String link) {
        String safe = link.replace("\"", "").replace("<", "").replace(">", "").replace("&", "&amp;");
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover'>"
            + "<style>html,body{margin:0;height:100%;background:#0B1220;overflow:hidden}"
            + "iframe{position:fixed;top:0;left:0;width:100%;height:100%;border:0}</style></head>"
            + "<body><iframe src=\"" + safe + "\" allow=\"camera; clipboard-read; clipboard-write\"></iframe></body></html>";
        web.loadDataWithBaseURL(APP_BASE, html, "text/html", "UTF-8", null);
    }

    private void askForLink(boolean cancelable) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://script.google.com/macros/s/.../exec?id=...");
        input.setText(savedLink());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Paste your app link")
            .setMessage("The link ends with ?id=...")
            .setView(box)
            .setCancelable(cancelable)
            .setPositiveButton("Open", null);
        if (cancelable) b.setNegativeButton("Cancel", null);
        final AlertDialog d = b.create();
        d.setOnShowListener(dlg -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String link = input.getText().toString().trim();
            if (!link.startsWith("https://script.google.com/macros/s/")) {
                Toast.makeText(this, "That doesn't look like an app link. Copy the whole link and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LINK, link).apply();
            d.dismiss();
            loadApp(link);
            web.clearHistory();
        }));
        d.show();
    }

    private static boolean isAppHost(Uri u) {
        String h = u.getHost() == null ? "" : u.getHost();
        return h.equals("diet-tracker.app") || h.equals("script.google.com") || h.endsWith(".googleusercontent.com")
            || h.equals("accounts.google.com") || "about".equals(u.getScheme()) || "blob".equals(u.getScheme()) || "data".equals(u.getScheme());
    }

    private void openOutside(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "No app can open that link.", Toast.LENGTH_SHORT).show(); }
    }

    /* ---------- self-update: "Update ready" popup ---------- */

    private void checkForUpdate(boolean force) {
        if (!BuildConfig.STABLE_KEY || updateDialogUp) return;   // test builds can't update in place, so don't offer
        long last = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_CHECKED, 0);
        if (!force && System.currentTimeMillis() - last < 3L * 60 * 60 * 1000) return;   // at most every 3 hours
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(httpGet(RELEASES));
                int latest = Integer.parseInt(rel.getString("tag_name").replaceAll("[^0-9]", ""));
                if (latest <= BuildConfig.VERSION_CODE) {
                    if (force) runOnUiThread(() -> Toast.makeText(this, "You're on the newest version ✓", Toast.LENGTH_SHORT).show());
                    return;
                }
                String apkUrl = null;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) { apkUrl = a.getString("browser_download_url"); break; }
                }
                if (apkUrl == null) return;
                final String url = apkUrl;
                final String notes = rel.optString("body", "").trim();
                runOnUiThread(() -> showUpdateDialog(url, notes));
            } catch (Exception ignored) { /* offline or GitHub hiccup: try again next time */ }
        }).start();
    }

    private void showUpdateDialog(String url, String notes) {
        if (isFinishing()) return;
        updateDialogUp = true;
        String first = notes.isEmpty() ? "" : notes.split("\n")[0];
        new AlertDialog.Builder(this)
            .setTitle("Update ready")
            .setMessage("A new version of the app is ready." + (first.isEmpty() ? "" : "\n\n" + first))
            .setPositiveButton("Update", (d, w) -> { updateDialogUp = false; downloadUpdate(url); })
            .setNegativeButton("Later", (d, w) -> updateDialogUp = false)
            .setOnCancelListener(d -> updateDialogUp = false)
            .show();
    }

    private void downloadUpdate(String url) {
        final AlertDialog progress = new AlertDialog.Builder(this)
            .setTitle("Updating")
            .setMessage("Downloading the new version...")
            .setCancelable(false)
            .show();
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, "DietTracker.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                runOnUiThread(() -> { progress.dismiss(); installApk(out); });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.dismiss(); Toast.makeText(this, "Update download failed. Check your internet and try again.", Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void installApk(File apk) {
        if (!canInstall()) {
            pendingApk = apk;
            new AlertDialog.Builder(this)
                .setTitle("One-time permission")
                .setMessage("To install updates, turn on \"Allow from this source\" on the next screen, then come back.")
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException e) { pendingApk = null; }
                })
                .setNegativeButton("Not now", (d, w) -> pendingApk = null)
                .show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Could not open the installer.", Toast.LENGTH_LONG).show(); }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /* ---------- camera / gallery for the photo buttons ---------- */

    private boolean openPhotoPicker(boolean multiple) {
        Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
        gallery.addCategory(Intent.CATEGORY_OPENABLE);
        gallery.setType("image/*");
        if (multiple) gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);   // pick several meal photos at once

        Intent chooser = Intent.createChooser(gallery, "Add a food photo");
        cameraUri = null;
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "meal_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{ camera });
        } catch (Exception e) {
            cameraUri = null;   // camera not available: gallery still works
        }
        try {
            startActivityForResult(chooser, REQ_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            if (pendingFiles != null) pendingFiles.onReceiveValue(null);
            pendingFiles = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || pendingFiles == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                int n = Math.min(data.getClipData().getItemCount(), 4);
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            }
            else if (data != null && data.getData() != null) result = new Uri[]{ data.getData() };
            else if (cameraUri != null) result = new Uri[]{ cameraUri };
        }
        pendingFiles.onReceiveValue(result);
        pendingFiles = null;
    }

    /* ---------- back button ---------- */

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return; }
        new AlertDialog.Builder(this)
            .setItems(new CharSequence[]{ "Close the app", "Reload", "Check for updates", "Change app link" }, (dlg, which) -> {
                if (which == 0) finish();
                else if (which == 1) { String l = savedLink(); if (!l.isEmpty()) loadApp(l); else showWelcome(); }
                else if (which == 2) {
                    if (!BuildConfig.STABLE_KEY) Toast.makeText(this, "This is a test build. Updates turn on after setup.", Toast.LENGTH_LONG).show();
                    else { Toast.makeText(this, "Checking...", Toast.LENGTH_SHORT).show(); checkForUpdate(true); }
                }
                else askForLink(true);
            })
            .show();
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
