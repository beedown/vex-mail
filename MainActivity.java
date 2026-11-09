package com.vexmail.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** VEX for Android: shows the VEX app (bundled inside the APK) full screen. */
public class MainActivity extends Activity {
    static final String INSTALL_ACTION = "com.vexmail.app.INSTALL_STATUS";
    private WebView web;

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
        web.addJavascriptInterface(new MailBridge(this, web), "VexNative");
        setContentView(web);

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
        handleInstall(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstall(intent);
    }

    /** Android reports back on an update: show its confirmation if it needs one, or say what went wrong. */
    private void handleInstall(Intent intent) {
        if (intent == null || !INSTALL_ACTION.equals(intent.getAction())) return;
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
