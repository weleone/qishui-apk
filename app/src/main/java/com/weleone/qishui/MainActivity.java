package com.weleone.qishui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    private static final String PREFS = "neko_prefs";
    private static final String KEY_URL = "server_url";
    private static final String DEFAULT_URL = "http://192.168.2.3:8107";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());

        String url = getPrefs().getString(KEY_URL, null);
        if (url == null) {
            showUrlDialog(true);
        } else {
            webView.loadUrl(url);
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void showUrlDialog(boolean firstRun) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final EditText input = new EditText(this);
        input.setHint("http://192.168.2.3:8107");
        String cur = getPrefs().getString(KEY_URL, DEFAULT_URL);
        input.setText(cur);
        layout.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("汽水音乐 服务器地址");
        b.setView(layout);
        b.setPositiveButton("连接", (d, w) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                if (!url.startsWith("http")) url = "http://" + url;
                getPrefs().edit().putString(KEY_URL, url).apply();
                webView.loadUrl(url);
            } else if (firstRun) {
                finish();
            }
        });
        if (!firstRun) {
            b.setNegativeButton("取消", null);
        } else {
            b.setOnCancelListener(d -> finish());
        }
        b.show();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, "设置服务器");
        menu.add(0, 2, 0, "刷新");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            showUrlDialog(false);
            return true;
        } else if (item.getItemId() == 2) {
            webView.reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
