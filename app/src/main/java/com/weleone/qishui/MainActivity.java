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
import android.webkit.WebResourceRequest;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.ListView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PREFS = "qishui_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_SERVERS = "servers";
    private static final String DEFAULT_URL = "http://192.168.2.3:8107";

    private WebView webView;
    private static final int REQ_SAVE_FILE = 1001;
    private String pendingUrl;
    private String pendingMime;
    private String pendingFileName;

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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                // 下载链接不让 WebView 自己打开, 走下载选择弹窗
                if (u.contains("/api/file/") || u.contains("/api/zip/")) {
                    startDownloadChoice(u, "", "", "audio/mpeg");
                    return true;
                }
                return false;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("/api/file/") || url.contains("/api/zip/")) {
                    startDownloadChoice(url, "", "", "audio/mpeg");
                    return true;
                }
                return false;
            }
        });

        // 点页面里的下载链接, 让用户选: 存服务器 (网络存储) 还是下到手机
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
        // 服务器旧版头会把 "-.mp3; filename*=UTF-8''..." 当成文件名, 这里兜底解出来
        if (fileName != null && (fileName.contains(";") || fileName.contains("filename"))) {
            int i = fileName.indexOf("UTF-8''");
            if (i >= 0) {
                try { fileName = java.net.URLDecoder.decode(fileName.substring(i + 7), "UTF-8"); }
                catch (Exception ignored) { fileName = null; }
            } else {
                fileName = null;
            }
        }
                startDownloadChoice(url, userAgent, contentDisposition, mimetype);
            }
        });
        String url = getPrefs().getString(KEY_URL, null);
        if (url == null) {
            showServerManager();
        } else {
            webView.loadUrl(url);
        }
    }

    private void startDownloadChoice(String url, String userAgent, String contentDisposition, String mimetype) {
        // 网页在地址里带了 ?name=歌名.mp3, 优先用它, 否则只剩序号会存成 0.mp3
        String fileName = null;
        try {
            String q = Uri.parse(url).getQueryParameter("name");
            if (q != null && !q.isEmpty()) fileName = q;
        } catch (Exception ignored) {}
        if (fileName == null) fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
        // 服务器旧版头会把 "-.mp3; filename*=UTF-8''..." 当成文件名, 这里兜底解出来
        if (fileName != null && (fileName.contains(";") || fileName.contains("filename"))) {
            int i = fileName.indexOf("UTF-8''");
            if (i >= 0) {
                try { fileName = java.net.URLDecoder.decode(fileName.substring(i + 7), "UTF-8"); }
                catch (Exception ignored) { fileName = null; }
            } else {
                fileName = null;
            }
        }
        if (fileName == null || fileName.isEmpty()) {
            // 从 URL 里取文件名
            fileName = Uri.parse(url).getLastPathSegment();
            if (fileName == null) fileName = "qishui.mp3";
        }
        final String fUrl = url;
        final String fMime = mimetype;
        final String fAgent = userAgent;
        final String fName = fileName;
        // 弹路径选择: 默认下载目录 / 手动选位置
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("保存到手机哪里?")
                .setMessage(fName)
                .setPositiveButton("默认下载目录", (d, w) -> {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(fUrl));
                    req.setMimeType(fMime);
                    req.addRequestHeader("User-Agent", fAgent);
                    req.setTitle(fName);
                    req.setDescription("汽水抓取下载");
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fName);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    Toast.makeText(MainActivity.this, "已开始下载到手机下载目录: " + fName, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("手动选位置", (d, w) -> {
                    pendingUrl = fUrl;
                    pendingMime = fMime;
                    pendingFileName = fName;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(fMime != null && !fMime.isEmpty() ? fMime : "audio/mpeg");
                    intent.putExtra(Intent.EXTRA_TITLE, fName);
                    startActivityForResult(intent, REQ_SAVE_FILE);
                })
                .show();
    }

    private void loadServer(String url) {
        getPrefs().edit().putString(KEY_URL, url).apply();
        webView.loadUrl(url);
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

        // 长按删除
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除服务器")
                    .setMessage(url)
                    .setPositiveButton("删除", (d, w) -> {
                        servers.remove(position);
                        saveServers(servers);
                        if (url.equals(cur) && !servers.isEmpty()) {
                            loadServer(servers.get(0));
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("服务器管理 (点一下切换, 长按删除)")
                .setView(layout)
                .setNegativeButton("关闭", null)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            loadServer(url);
        });
        dialog.show();
    }

    private void showAddServer(ArrayList<String> servers) {
        final EditText input = new EditText(this);
        input.setText("http://");
        input.setSelection(input.getText().length());
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
                        loadServer(url);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri dest = data.getData();
            String url = pendingUrl;
            String name = pendingFileName;
            new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);
                    conn.connect();
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = getContentResolver().openOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "已保存到手机: " + name, Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
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
