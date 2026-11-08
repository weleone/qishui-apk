package com.weleone.qishui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PREFS = "qishui_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_SERVERS = "servers";
    private static final String DEFAULT_URL = "http://192.168.2.3:8107";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView);

        // 右上角常驻设置按钮, 随时能换服务器
        Button settingsBtn = new Button(this);
        settingsBtn.setText("⚙");
        settingsBtn.setTextSize(18);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        lp.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
        settingsBtn.setLayoutParams(lp);
        settingsBtn.setOnClickListener(v -> showServerManager());
        root.addView(settingsBtn);

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());

        // 点页面里的下载链接, 直接下到手机本地下载目录
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimetype);
                req.addRequestHeader("User-Agent", userAgent);
                req.setTitle(fileName);
                req.setDescription("汽水抓取下载");
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(req);
                Toast.makeText(MainActivity.this, "已开始下载到手机: " + fileName, Toast.LENGTH_LONG).show();
            }
        });

        String url = getPrefs().getString(KEY_URL, null);
        if (url == null) {
            showServerManager();
        } else {
            webView.loadUrl(url);
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private ArrayList<String> getServers() {
        Set<String> set = getPrefs().getStringSet(KEY_SERVERS, null);
        ArrayList<String> list = new ArrayList<>();
        if (set != null) list.addAll(set);
        String cur = getPrefs().getString(KEY_URL, null);
        if (cur != null && !list.contains(cur)) list.add(0, cur);
        if (list.isEmpty()) list.add(DEFAULT_URL);
        return list;
    }

    private void saveServers(ArrayList<String> list) {
        getPrefs().edit().putStringSet(KEY_SERVERS, new LinkedHashSet<>(list)).apply();
    }

    private void showServerManager() {
        ArrayList<String> servers = getServers();
        String cur = getPrefs().getString(KEY_URL, DEFAULT_URL);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, servers) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                android.widget.TextView tv = (android.widget.TextView) v;
                String u = getItem(position);
                tv.setText(u + (u.equals(cur) ? "  ✓" : ""));
                return v;
            }
        };
        listView.setAdapter(adapter);
        layout.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 点一下切换, 长按删除
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            getPrefs().edit().putString(KEY_URL, url).apply();
            webView.loadUrl(url);
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除服务器")
                    .setMessage(url)
                    .setPositiveButton("删除", (d, w) -> {
                        servers.remove(position);
                        saveServers(servers);
                        if (url.equals(cur) && !servers.isEmpty()) {
                            getPrefs().edit().putString(KEY_URL, servers.get(0)).apply();
                            webView.loadUrl(servers.get(0));
                        }
                        showServerManager();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        Button addBtn = new Button(this);
        addBtn.setText("添加服务器");
        addBtn.setOnClickListener(v -> showAddServer(servers));
        layout.addView(addBtn);

        new AlertDialog.Builder(this)
                .setTitle("服务器管理 (点一下切换, 长按删除)")
                .setView(layout)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAddServer(ArrayList<String> servers) {
        final EditText input = new EditText(this);
        input.setHint("http://192.168.2.3:8107 或外网地址");
        new AlertDialog.Builder(this)
                .setTitle("添加服务器")
                .setView(input)
                .setPositiveButton("添加并连接", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        if (!url.startsWith("http")) url = "http://" + url;
                        if (!servers.contains(url)) servers.add(url);
                        saveServers(servers);
                        getPrefs().edit().putString(KEY_URL, url).apply();
                        webView.loadUrl(url);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
