package com.vexmail.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** VEX for Android: shows the VEX app (bundled inside the APK) full screen. */
public class MainActivity extends Activity {
    static final String INSTALL_ACTION = "com.vexmail.app.INSTALL_STATUS";
    static volatile boolean foreground = false;
    private static final int PICK_FILES = 42;
    private WebView web;
    private MailBridge bridge;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            /** Links to websites open in your normal browser, not inside VEX. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            /** Lets you pick photos and files to attach to an email. */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent pick = params.createIntent();
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(pick, PICK_FILES);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        bridge = new MailBridge(this, web);
        web.addJavascriptInterface(bridge, "VexNative");
        setContentView(web);

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || fileCallback == null) return;
        Uri[] picked = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                picked = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < picked.length; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(picked);
        fileCallback = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null && "vexmail".equals(data.getScheme())) {
            bridge.finishOAuth(data);
            return;
        }
        if (INSTALL_ACTION.equals(intent.getAction())) handleInstall(intent);
    }

    /** Android reports back on an update: show its confirmation if it needs one, or say what went wrong. */
    private void handleInstall(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(confirm);
                } catch (Exception ignored) {
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String js = "window.vexUpdateFailed && window.vexUpdateFailed(" + JSONObject.quote(msg == null ? "Android stopped the update." : msg) + ")";
            web.evaluateJavascript(js, null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** The back button closes whatever is open inside VEX first, then leaves the app. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.vexBack && window.vexBack()) ? 'yes' : 'no'", value -> {
            if (!"\"yes\"".equals(value)) {
                finish();
            }
        });
    }
}
