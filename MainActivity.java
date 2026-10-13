package com.magic.iptv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private static final int FILE_REQ = 1;

    private WebView web;
    static MainActivity self;

    /** Exécute du JavaScript dans la page (appelé par la notification / l'écran de verrouillage). */
    static void js(final String code) {
        final MainActivity a = self;
        if (a == null || a.web == null) return;
        a.runOnUiThread(new Runnable() {
            public void run() { a.web.evaluateJavascript(code, null); }
        });
    }
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private ValueCallback<Uri[]> fileCb;
    private WebChromeClient chrome;

    /** Pont JS : window.AndroidApp.exitApp() ferme réellement l'application. */
    private class Bridge {
        @JavascriptInterface
        public String loadData() {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "iptv_data.json");
                if (!f.exists()) return "";
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return out.toString("UTF-8");
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean saveData(String json) {
            try {
                java.io.File dir = getFilesDir();
                java.io.File tmp = new java.io.File(dir, "iptv_data.tmp");
                java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
                o.write(json.getBytes("UTF-8"));
                o.flush();
                o.getFD().sync();
                o.close();
                return tmp.renameTo(new java.io.File(dir, "iptv_data.json"));
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void setOrientation(final int m) {
            runOnUiThread(new Runnable() {
                public void run() {
                    int o = m == 1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            : m == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : m == 3 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                    setRequestedOrientation(o);
                }
            });
        }

        @JavascriptInterface
        public float getBrightness() {
            try {
                float b = getWindow().getAttributes().screenBrightness;
                if (b >= 0) return b;
                return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
            } catch (Exception e) { return 0.5f; }
        }

        @JavascriptInterface
        public void setBrightness(final float f) {
            runOnUiThread(new Runnable() {
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = Math.max(0.02f, Math.min(1f, f));
                    getWindow().setAttributes(lp);
                }
            });
        }

        @JavascriptInterface
        public int getVolume() {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                return max <= 0 ? 0 : Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
            } catch (Exception e) { return 50; }
        }

        @JavascriptInterface
        public void setVolume(int pct) {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0, Math.min(100, pct)) * max / 100f), 0);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void setMedia(final boolean active, final boolean playing, final boolean rec, final String title, final String sub) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(MainActivity.this, PlaybackService.class);
                        if (active) {
                            if (PlaybackService.running) {
                                PlaybackService.update(title, sub, playing, rec);
                            } else {
                                i.putExtra("t", title);
                                i.putExtra("s", sub);
                                i.putExtra("p", playing);
                                i.putExtra("r", rec);
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                                else startService(i);
                            }
                        } else {
                            stopService(i);
                        }
                    } catch (Exception ignored) { }
                }
            });
        }

        private java.io.OutputStream recOut;
        private android.net.Uri recUri;
        private String recName = "";

        @JavascriptInterface
        public synchronized boolean recStart(String name, String ext) {
            try {
                recStop();
                recName = name + "_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + "." + ext;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, recName);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "mp4".equals(ext) ? "video/mp4" : "video/mp2t");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MAGIC IPTV");
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    recUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                    if (recUri == null) return false;
                    recOut = getContentResolver().openOutputStream(recUri);
                } else {
                    java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    if (dir == null) return false;
                    dir.mkdirs();
                    recOut = new java.io.FileOutputStream(new java.io.File(dir, recName));
                }
                return recOut != null;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public synchronized boolean recChunk(String b64) {
            try {
                if (recOut == null) return false;
                recOut.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public synchronized String recStop() {
            String n = recName;
            try {
                if (recOut != null) { recOut.flush(); recOut.close(); }
                if (recUri != null && Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(recUri, cv, null, null);
                }
            } catch (Exception ignored) { }
            boolean had = recOut != null;
            recOut = null;
            recUri = null;
            recName = "";
            return had ? n : "";
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(new Runnable() {
                public void run() { finishAndRemoveTask(); }
            });
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        self = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF07111F));

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07111F);
        web = new WebView(this);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFF07111F);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u.toString());
                return true;
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v;
                customCb = cb;
                root.addView(v, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                v.requestFocus();
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
                web.requestFocus();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        openExternal(r.getUrl().toString());
                        return true;
                    }
                    @Override
                    public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                        if (url != null && !url.startsWith("about:")) {
                            openExternal(url);
                            v.stopLoading();
                        }
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), FILE_REQ);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        };
        web.setWebChromeClient(chrome);

        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
        hideSystemUi();
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {            // sortie du plein écran vidéo
            chrome.onHideCustomView();
        } else if (web.canGoBack()) {        // la page gère Retour (liste / confirmation)
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQ && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override protected void onPause() { super.onPause(); if (!PlaybackService.running) web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideSystemUi(); }

    @Override
    protected void onDestroy() {
        self = null;
        try { stopService(new Intent(this, PlaybackService.class)); } catch (Exception ignored) { }
        web.destroy();
        super.onDestroy();
    }
}
