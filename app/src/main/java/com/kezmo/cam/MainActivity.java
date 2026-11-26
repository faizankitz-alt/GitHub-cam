package com.kezmo.cam;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;

public class MainActivity extends Activity {
    private WebView web;
    private volatile boolean volumeShutter = true;
    private volatile Uri lastSaved;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true); // remembers camera settings
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        // Serves the bundled AI files. Every other web request is refused, so nothing is loaded from the internet.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("data".equals(u.getScheme()) || "blob".equals(u.getScheme())) return null;
                String path = u.getPath();
                if ("kezmocam.invalid".equals(u.getHost()) && path != null && path.startsWith("/ai/") && !path.contains("..")) {
                    String mime = path.endsWith(".wasm") ? "application/wasm"
                            : path.endsWith("js") ? "text/javascript" : "application/octet-stream";
                    try {
                        HashMap<String, String> headers = new HashMap<>();
                        headers.put("Access-Control-Allow-Origin", "*");
                        return new WebResourceResponse(mime, null, 200, "OK", headers, getAssets().open(path.substring(1)));
                    } catch (Exception e) {
                        // missing file: fall through to "not found"
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        new HashMap<>(), new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    ArrayList<String> ok = new ArrayList<>();
                    for (String r : request.getResources()) {
                        if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && has(Manifest.permission.CAMERA)) ok.add(r);
                        if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
                    }
                    if (ok.isEmpty()) request.deny();
                    else request.grant(ok.toArray(new String[0]));
                });
            }
        });
        if (has(Manifest.permission.CAMERA)) load();
        else requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void load() {
        try (InputStream in = getAssets().open("index.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            // https base URL so the page is allowed to use the camera
            web.loadDataWithBaseURL("https://kezmocam.invalid/", out.toString("UTF-8"), "text/html", "UTF-8", null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        load();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (volumeShutter && (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                web.evaluateJavascript("window.volumeShot && volumeShot()", null);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // first close any open panel inside the app
        web.evaluateJavascript("!!(window.backPressed && backPressed())", handled -> {
            if (!"true".equals(handled)) finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("window.appPause && appPause()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.evaluateJavascript("window.appResume && appResume()", null);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    /** Saves photos and videos into the phone gallery, chunk by chunk. */
    class Bridge {
        private Uri uri;
        private OutputStream out;

        @JavascriptInterface
        public boolean begin(String name, String mime) {
            try {
                boolean video = mime.startsWith("video/");
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/KezmoCam");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = getContentResolver().insert(video
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                out = getContentResolver().openOutputStream(uri);
                return out != null;
            } catch (Exception e) {
                abort();
                return false;
            }
        }

        @JavascriptInterface
        public boolean chunk(String base64) {
            try {
                out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                abort();
                return false;
            }
        }

        @JavascriptInterface
        public boolean end() {
            try {
                out.close();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
                lastSaved = uri;
                out = null;
                uri = null;
                return true;
            } catch (Exception e) {
                abort();
                return false;
            }
        }

        private void abort() {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            if (uri != null) getContentResolver().delete(uri, null, null);
            out = null;
            uri = null;
        }

        @JavascriptInterface
        public void setVolumeShutter(boolean on) {
            volumeShutter = on;
        }

        /** Opens the latest photo or video, or the gallery if nothing was taken yet. */
        @JavascriptInterface
        public void openLast() {
            Uri target = lastSaved != null ? lastSaved : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception ignored) {
                // no gallery app can open it
            }
        }

        @JavascriptInterface
        public void openSettings() {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }
}
