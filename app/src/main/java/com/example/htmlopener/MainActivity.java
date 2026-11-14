package com.example.htmlopener;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 1;
    private static final String PREFS = "html_opener";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LINKS = "quick_links";
    private static final String KEY_TAB = "current_tab";
    private static final int TAB_APPS = 0;
    private static final int TAB_LINKS = 1;
    private static final String[] CHROME_PACKAGES = {
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary"
    };

    private final List<WebApp> apps = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();

    private AppAdapter appsAdapter;
    private LinkAdapter linksAdapter;
    private TextView emptyTitle;
    private TextView emptyBody;
    private TextView folderView;
    private TextView appCountView;
    private TextView linkCountView;
    private View pageApps;
    private View pageLinks;
    private View indicatorApps;
    private View indicatorLinks;
    private TextView labelApps;
    private TextView labelLinks;
    private SharedPreferences prefs;
    private DocumentFile root;
    private HtmlServer server;
    private int currentTab = TAB_APPS;

    private static final class WebApp {
        final String name;
        final String entryPath;

        WebApp(String name, String entryPath) {
            this.name = name;
            this.entryPath = entryPath;
        }
    }

    private static final class Link {
        final String name;
        final String url;

        Link(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        server = HtmlServer.get(getApplicationContext());

        emptyTitle = findViewById(R.id.empty_title);
        emptyBody = findViewById(R.id.empty_body);
        folderView = findViewById(R.id.tv_folder);
        appCountView = findViewById(R.id.tv_count);
        linkCountView = findViewById(R.id.tv_link_count);
        pageApps = findViewById(R.id.page_apps);
        pageLinks = findViewById(R.id.page_links);
        indicatorApps = findViewById(R.id.indicator_apps);
        indicatorLinks = findViewById(R.id.indicator_links);
        labelApps = findViewById(R.id.label_apps);
        labelLinks = findViewById(R.id.label_links);

        View appEmptyState = findViewById(R.id.empty_state);
        View linkEmptyState = findViewById(R.id.empty_links_state);

        applyTitleStyle();
        applyLinksTitleStyle();

        TextView importButton = findViewById(R.id.btn_import);
        importButton.setOnClickListener(v -> chooseFolder());
        TextView addLinkButton = findViewById(R.id.btn_add_link);
        addLinkButton.setOnClickListener(v -> showLinkDialog(-1, false));

        appsAdapter = new AppAdapter();
        ListView appsList = findViewById(R.id.list_files);
        appsList.setAdapter(appsAdapter);
        appsList.setEmptyView(appEmptyState);
        appsList.setOnItemClickListener((parent, view, position, id) -> openInBrowser(apps.get(position)));

        linksAdapter = new LinkAdapter();
        ListView linksList = findViewById(R.id.list_links);
        linksList.setAdapter(linksAdapter);
        linksList.setEmptyView(linkEmptyState);
        linksList.setOnItemClickListener((parent, view, position, id) -> openLink(links.get(position)));

        findViewById(R.id.nav_apps).setOnClickListener(v -> showTab(TAB_APPS));
        findViewById(R.id.nav_links).setOnClickListener(v -> showTab(TAB_LINKS));

        restoreFolder();
        loadLinks();

        currentTab = savedInstanceState == null
                ? TAB_APPS
                : savedInstanceState.getInt(KEY_TAB, TAB_APPS);
        showTab(currentTab);
    }

    private void applyTitleStyle() {
        TextView title = findViewById(R.id.tv_title);
        String text = getString(R.string.header_title);
        SpannableString styled = new SpannableString(text);
        int opener = text.indexOf("OPENER");
        if (opener >= 0) {
            styled.setSpan(new ForegroundColorSpan(getColor(R.color.swiss_red)),
                    opener, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(styled);
    }

    private void applyLinksTitleStyle() {
        TextView title = findViewById(R.id.tv_links_title);
        String text = getString(R.string.links_title);
        SpannableString styled = new SpannableString(text);
        if (text.endsWith(".")) {
            styled.setSpan(new ForegroundColorSpan(getColor(R.color.swiss_red)),
                    text.length() - 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(styled);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        refreshLinks();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(KEY_TAB, currentTab);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (currentTab != TAB_APPS) {
            showTab(TAB_APPS);
            return;
        }
        super.onBackPressed();
    }

    private void showTab(int tab) {
        currentTab = tab;
        boolean appsTab = tab == TAB_APPS;

        pageApps.setVisibility(appsTab ? View.VISIBLE : View.GONE);
        pageLinks.setVisibility(appsTab ? View.GONE : View.VISIBLE);

        indicatorApps.setVisibility(appsTab ? View.VISIBLE : View.INVISIBLE);
        indicatorLinks.setVisibility(appsTab ? View.INVISIBLE : View.VISIBLE);

        labelApps.setTextColor(getColor(appsTab ? R.color.ink : R.color.muted));
        labelLinks.setTextColor(getColor(appsTab ? R.color.muted : R.color.ink));
    }

    private void restoreFolder() {
        String saved = prefs.getString(KEY_TREE_URI, null);
        if (saved == null) {
            return;
        }
        try {
            Uri uri = Uri.parse(saved);
            DocumentFile dir = DocumentFile.fromTreeUri(getApplicationContext(), uri);
            if (dir != null && dir.isDirectory() && dir.canRead()) {
                root = dir;
                server.setRoot(dir);
            } else {
                clearSavedFolder();
            }
        } catch (Exception e) {
            clearSavedFolder();
        }
    }

    private void clearSavedFolder() {
        prefs.edit().remove(KEY_TREE_URI).apply();
        root = null;
        server.setRoot(null);
    }

    private void chooseFolder() {
        Toast.makeText(this, R.string.pick_folder_hint, Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        try {
            int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (takeFlags == 0) {
                takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
            getContentResolver().takePersistableUriPermission(uri, takeFlags);

            DocumentFile dir = DocumentFile.fromTreeUri(getApplicationContext(), uri);
            if (dir == null || !dir.isDirectory() || !dir.canRead()) {
                throw new IOException("无法读取所选文件夹");
            }

            String old = prefs.getString(KEY_TREE_URI, null);
            if (old != null && !old.equals(uri.toString())) {
                try {
                    getContentResolver().releasePersistableUriPermission(
                            Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // 旧授权可能已经失效，忽略即可。
                }
            }

            prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply();
            root = dir;
            server.setRoot(dir);
            refreshList();

            if (apps.isEmpty()) {
                Toast.makeText(this, R.string.no_apps, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            Toast.makeText(this, getString(R.string.import_failed, message),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshList() {
        apps.clear();
        if (root != null) {
            try {
                // 所选文件夹本身也可以是一个 Web 应用。
                String rootEntry = findEntry(root);
                if (rootEntry != null && isIndex(rootEntry)) {
                    String rootName = root.getName();
                    apps.add(new WebApp(rootName == null ? getString(R.string.current_folder) : rootName, rootEntry));
                }

                // 每个子文件夹视为一个 Web 应用。
                for (DocumentFile child : root.listFiles()) {
                    String name = child.getName();
                    if (name == null || !child.isDirectory()) {
                        continue;
                    }
                    String entry = findEntry(child);
                    if (entry != null) {
                        apps.add(new WebApp(name, name + "/" + entry));
                    }
                }

                apps.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
            } catch (Exception ignored) {
                // 授权失效时按空列表处理。
            }
        }

        appsAdapter.notifyDataSetChanged();

        emptyTitle.setText(root == null ? R.string.empty_title : R.string.empty_title_folder);
        emptyBody.setText(root == null ? R.string.empty_body : R.string.empty_body_folder);

        String folderName;
        if (root == null) {
            folderName = getString(R.string.folder_none);
        } else {
            folderName = root.getName();
            if (folderName == null) {
                folderName = getString(R.string.folder_none);
            }
        }
        folderView.setText(folderName);
        appCountView.setText(getString(R.string.app_count,
                String.format(Locale.ROOT, "%02d", apps.size())));
    }

    /** 返回应用文件夹内的入口 HTML 相对路径：优先 index.html/.htm，否则第一个 HTML。 */
    private String findEntry(DocumentFile dir) {
        String firstHtml = null;
        for (DocumentFile child : dir.listFiles()) {
            String name = child.getName();
            if (name == null || !child.isFile() || !isHtml(name)) {
                continue;
            }
            if (isIndex(name)) {
                return name;
            }
            if (firstHtml == null
                    || String.CASE_INSENSITIVE_ORDER.compare(name, firstHtml) < 0) {
                firstHtml = name;
            }
        }
        return firstHtml;
    }

    private boolean isIndex(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("index.html") || lower.equals("index.htm");
    }

    private boolean isHtml(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private void loadLinks() {
        links.clear();
        String json = prefs.getString(KEY_LINKS, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String name = object.optString("name", "");
                String url = object.optString("url", "");
                if (url.isEmpty()) {
                    continue;
                }
                if (name.isEmpty()) {
                    name = suggestName(url);
                }
                links.add(new Link(name, url));
            }
        } catch (JSONException ignored) {
            // 数据损坏时按空列表处理。
        }
        refreshLinks();
    }

    private void saveLinks() {
        JSONArray array = new JSONArray();
        for (Link link : links) {
            try {
                JSONObject object = new JSONObject();
                object.put("name", link.name);
                object.put("url", link.url);
                array.put(object);
            } catch (JSONException ignored) {
                // 字符串写入不会失败，忽略即可。
            }
        }
        prefs.edit().putString(KEY_LINKS, array.toString()).apply();
    }

    private void refreshLinks() {
        linksAdapter.notifyDataSetChanged();
        linkCountView.setText(getString(R.string.link_count,
                String.format(Locale.ROOT, "%02d", links.size())));
    }

    private void showLinkDialog(final int position, final boolean editing) {
        View content = getLayoutInflater().inflate(R.layout.dialog_link, null);
        final EditText nameInput = content.findViewById(R.id.et_link_name);
        final EditText urlInput = content.findViewById(R.id.et_link_url);

        if (editing) {
            if (position < 0 || position >= links.size()) {
                return;
            }
            Link link = links.get(position);
            nameInput.setText(link.name);
            urlInput.setText(link.url);
            nameInput.setSelection(nameInput.length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(editing ? R.string.edit_link_title : R.string.add_link_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        if (editing) {
            builder.setNeutralButton(R.string.delete, null);
        }

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String url = normalizeUrl(urlInput.getText().toString());
                if (url == null) {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    name = suggestName(url);
                }
                if (editing) {
                    if (position >= 0 && position < links.size()) {
                        links.set(position, new Link(name, url));
                    }
                } else {
                    links.add(new Link(name, url));
                }
                saveLinks();
                refreshLinks();
                dialog.dismiss();
            });

            if (editing) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener(v -> confirmDeleteLink(position, dialog));
            }
        });
        dialog.show();
        nameInput.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void confirmDeleteLink(final int position, final AlertDialog editDialog) {
        if (position < 0 || position >= links.size()) {
            return;
        }
        Link link = links.get(position);
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_link_title)
                .setMessage(getString(R.string.delete_link_message, link.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    if (position < links.size()) {
                        links.remove(position);
                    }
                    saveLinks();
                    refreshLinks();
                    editDialog.dismiss();
                })
                .show();
    }

    private String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (lower.contains("://")) {
                return null;
            }
            value = "https://" + value;
        }
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        return value;
    }

    private String suggestName(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return url;
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host;
    }

    private void openLink(Link link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link.url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_browser_link, Toast.LENGTH_SHORT).show();
        }
    }

    private final class AppAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public WebApp getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_app, parent, false);
            }

            WebApp app = apps.get(position);
            TextView index = view.findViewById(R.id.tv_index);
            TextView name = view.findViewById(R.id.tv_name);
            TextView entry = view.findViewById(R.id.tv_entry);

            index.setText(String.format(Locale.ROOT, "%02d", position + 1));
            name.setText(app.name);
            entry.setText(entryFile(app.entryPath));
            return view;
        }
    }

    private final class LinkAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return links.size();
        }

        @Override
        public Link getItem(int position) {
            return links.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_link, parent, false);
            }

            Link link = links.get(position);
            TextView index = view.findViewById(R.id.tv_link_index);
            TextView name = view.findViewById(R.id.tv_link_name);
            TextView url = view.findViewById(R.id.tv_link_url);
            TextView edit = view.findViewById(R.id.tv_link_edit);

            index.setText(String.format(Locale.ROOT, "%02d", position + 1));
            name.setText(link.name);
            url.setText(link.url);
            edit.setOnClickListener(v -> showLinkDialog(position, true));
            return view;
        }
    }

    private String entryFile(String entryPath) {
        int slash = entryPath.indexOf('/');
        return slash >= 0 ? entryPath.substring(slash + 1) : entryPath;
    }

    private void openInBrowser(WebApp app) {
        try {
            String url = server.urlFor(app.entryPath);
            startServerService();

            // 显式指定 Chrome：本地 127.0.0.1 地址可能被已安装的 PWA/WebAPK 抢走，
            // 隐式 ACTION_VIEW 会打开那个 PWA 而不是真正的浏览器。
            if (!openInChrome(url)) {
                stopService(new Intent(this, ServerService.class));
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, R.string.server_failed, Toast.LENGTH_LONG).show();
        }
    }

    private boolean openInChrome(String url) {
        Intent base = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        for (String packageName : CHROME_PACKAGES) {
            try {
                Intent chrome = new Intent(base);
                chrome.setPackage(packageName);
                startActivity(chrome);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // 尝试下一个 Chrome 渠道。
            }
        }
        try {
            startActivity(base);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private void startServerService() {
        Intent service = new Intent(this, ServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception ignored) {
            // 即使服务启动失败，也先尝试用当前进程打开页面。
        }
    }
}
