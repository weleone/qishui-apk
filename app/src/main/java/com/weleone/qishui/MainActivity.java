package com.weleone.qishui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.widget.LinearLayout;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PREFS = "qishui_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_SERVERS = "servers";
    private static final String DEFAULT_URL = "http://192.168.1.3:8107";

    private WebView webView;
    private static final int REQ_SAVE_FILE = 1001;
    private String pendingUrl;
    private String pendingMime;
    private String pendingFileName;
    // 服务器连接状态: url -> true(通)/false(不通)/null(检测中); 当前列表的 adapter, 检测完刷新用 (仿 Tagr)
    private final Map<String, Boolean> serverStatus = new HashMap<>();
    private BaseAdapter serverAdapter;
    private AlertDialog serverDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        SwipeRefreshLayout swipe = new SwipeRefreshLayout(this);
        swipe.addView(webView);
        swipe.setOnRefreshListener(() -> {
            webView.reload();
            swipe.setRefreshing(false);
        });
        root.addView(swipe);


        // 右上角常驻设置按钮: TextView 透明无灰底 (仿 Tagr 的 ⚙), 随时能换服务器
        // 汽水页是固定深色底 (#0e0e11), 齿轮必须用白色, 黑色会被页面盖住看不见
        TextView settingsText = new TextView(this);
        settingsText.setText("⚙");
        settingsText.setTextSize(22);
        settingsText.setTextColor(Color.WHITE);
        settingsText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        lp.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
        settingsText.setLayoutParams(lp);
        settingsText.setOnClickListener(v -> showServerManager());
        root.addView(settingsText);

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showNoServicePage(request.getUrl().toString());
                }
            }
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                String cur = getPrefs().getString(KEY_URL, DEFAULT_URL);
                if (failingUrl != null && failingUrl.equals(cur)) {
                    showNoServicePage(failingUrl);
                }
            }
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

    // 服务器不通时显示的专设页面: “当前地址无服务” + 地址 + 右上角重选服务器的提示 (仿 Tagr)
    private void showNoServicePage(String url) {
        String safe = url == null ? "" : url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:sans-serif;background:#fff;color:#888;}h1{font-size:26px;color:#666;margin:0 0 10px;text-align:center;padding:0 24px;}p{font-size:14px;margin:4px 0;text-align:center;padding:0 24px;}</style></head>"
                + "<body><h1>当前地址无服务</h1>"
                + "<p>" + safe + "</p>"
                + "<p>点右上角 ⚙ 重新选择服务器</p>"
                + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void loadServer(String url) {
        getPrefs().edit().putString(KEY_URL, url).apply();
        Boolean st = serverStatus.get(url);
        if (st != null && !st) {
            // 已知不通 (红×), 直接显示无服务页, 不用等加载超时
            showNoServicePage(url);
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

    // 检测服务器连通性: GET 根地址, 2xx/3xx 算通, 超时或异常算不通; 结果回主线程刷新列表 (仿 Tagr)
    private void checkServer(String url) {
        serverStatus.put(url, null);
        new Thread(() -> {
            boolean ok = false;
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                int code = conn.getResponseCode();
                ok = code >= 200 && code < 400;
            } catch (Exception e) {
                ok = false;
            } finally {
                if (conn != null) conn.disconnect();
            }
            boolean result = ok;
            runOnUiThread(() -> {
                serverStatus.put(url, result);
                if (serverAdapter != null) serverAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    // 服务器列表行: [圆点选择] [地址] [连接状态 ✓绿/×红/…检测中] (仿 Tagr)
    private class ServerAdapter extends BaseAdapter {
        private final ArrayList<String> servers;
        private final String cur;
        ServerAdapter(ArrayList<String> servers, String cur) {
            this.servers = servers;
            this.cur = cur;
        }
        @Override public int getCount() { return servers.size(); }
        @Override public String getItem(int pos) { return servers.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            String url = servers.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int vpad = (int) (8 * getResources().getDisplayMetrics().density);
            row.setPadding(0, vpad, 0, vpad);

            RadioButton radio = new RadioButton(MainActivity.this);
            radio.setChecked(url.equals(cur));
            radio.setClickable(false);
            radio.setFocusable(false);
            row.addView(radio);

            TextView urlTv = new TextView(MainActivity.this);
            urlTv.setText(url);
            urlTv.setSingleLine(true);
            urlTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            urlTv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(urlTv);

            TextView statusTv = new TextView(MainActivity.this);
            statusTv.setTextSize(18);
            Boolean st = serverStatus.get(url);
            if (st == null) {
                statusTv.setText("…");
                statusTv.setTextColor(Color.GRAY);
            } else if (st) {
                statusTv.setText("✓");
                statusTv.setTextColor(Color.parseColor("#22AA55"));
            } else {
                statusTv.setText("×");
                statusTv.setTextColor(Color.RED);
            }
            row.addView(statusTv);
            return row;
        }
    }

    private void showServerManager() {
        if (serverDialog != null && serverDialog.isShowing()) serverDialog.dismiss();
        ArrayList<String> servers = getServers();
        String cur = getPrefs().getString(KEY_URL, DEFAULT_URL);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        ListView listView = new ListView(this);
        serverAdapter = new ServerAdapter(servers, cur);
        listView.setAdapter(serverAdapter);
        layout.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 长按删除 / 编辑
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除服务器")
                    .setMessage(url)
                    .setPositiveButton("删除", (d, w) -> {
                        servers.remove(position);
                        serverStatus.remove(url);
                        saveServers(servers);
                        if (url.equals(cur) && !servers.isEmpty()) {
                            loadServer(servers.get(0));
                        }
                        showServerManager();
                    })
                    .setNeutralButton("编辑", (d, w) -> showEditServer(servers, position, url, cur))
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        Button addBtn = new Button(this);
        addBtn.setText("添加服务器");
        addBtn.setOnClickListener(v -> showAddServer(servers));
        layout.addView(addBtn);

        serverDialog = new AlertDialog.Builder(this)
                .setTitle("服务器管理 (点圆点或点行切换, 长按删除)")
                .setView(layout)
                .setNegativeButton("关闭", null)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            loadServer(url);
            if (serverDialog != null) serverDialog.dismiss();
        });
        serverDialog.show();
        // 打开就测全部服务器的连通性
        for (String url : servers) checkServer(url);
    }

    // 长按菜单里的编辑: 改服务器地址 (改 IP / 端口不用删了重加)
    private void showEditServer(ArrayList<String> servers, int position, String oldUrl, String cur) {
        final EditText input = new EditText(this);
        input.setText(oldUrl);
        input.setSelection(input.getText().length());
        input.setHint("http://192.168.1.3:8107 或外网地址");
        new AlertDialog.Builder(this)
                .setTitle("编辑服务器")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty() || url.equals(oldUrl)) return;
                    if (!url.startsWith("http")) url = "http://" + url;
                    servers.set(position, url);
                    serverStatus.remove(oldUrl);
                    saveServers(servers);
                    if (oldUrl.equals(cur)) {
                        loadServer(url);
                    }
                    // 重新打开服务器管理, 改完的地址出现并立即测连通性
                    showServerManager();
                })
                .setNegativeButton("取消", null)
                .show();
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
                        // 重新打开服务器管理, 新加的服务器出现并立即测连通性
                        showServerManager();
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
