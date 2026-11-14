package com.example.htmlopener;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 1;
    private static final String PREFS = "html_opener";
    private static final String KEY_TREE_URI = "tree_uri";

    private static final int[] AVATAR_COLORS = {
            0xFF0F766E, 0xFF2563EB, 0xFF7C3AED, 0xFFDB2777,
            0xFFEA580C, 0xFF0891B2, 0xFF4F46E5, 0xFF059669
    };

    private final List<WebApp> apps = new ArrayList<>();

    private AppAdapter adapter;
    private TextView emptyTitle;
    private TextView emptyBody;
    private SharedPreferences prefs;
    private DocumentFile root;
    private HtmlServer server;

    private static final class WebApp {
        final String name;
        final String entryPath;

        WebApp(String name, String entryPath) {
            this.name = name;
            this.entryPath = entryPath;
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
        View emptyState = findViewById(R.id.empty_state);

        adapter = new AppAdapter();
        ListView listView = findViewById(R.id.list_files);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyState);
        listView.setOnItemClickListener((parent, view, position, id) -> openInBrowser(apps.get(position)));

        restoreFolder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_import) {
            chooseFolder();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
                    apps.add(new WebApp(rootName == null ? "当前文件夹" : rootName, rootEntry));
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

        adapter.notifyDataSetChanged();

        emptyTitle.setText(root == null ? R.string.empty_title : R.string.empty_title_folder);
        emptyBody.setText(root == null ? R.string.empty_body : R.string.empty_body_folder);

        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setTitle(R.string.app_name);
            if (root == null) {
                bar.setSubtitle(R.string.subtitle_default);
            } else {
                String rootName = root.getName();
                if (rootName == null) {
                    rootName = getString(R.string.current_folder);
                }
                bar.setSubtitle(getString(R.string.subtitle_folder, rootName, apps.size()));
            }
        }
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
            TextView avatar = view.findViewById(R.id.tv_avatar);
            TextView name = view.findViewById(R.id.tv_name);
            TextView entry = view.findViewById(R.id.tv_entry);

            name.setText(app.name);
            entry.setText(getString(R.string.entry_label, entryFile(app.entryPath)));

            String initial = "?";
            if (!app.name.isEmpty()) {
                initial = new String(Character.toChars(app.name.codePointAt(0)))
                        .toUpperCase(Locale.getDefault());
            }
            avatar.setText(initial);

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(dp(14));
            background.setColor(AVATAR_COLORS[(app.name.hashCode() & 0x7fffffff)
                    % AVATAR_COLORS.length]);
            avatar.setBackground(background);
            return view;
        }
    }

    private String entryFile(String entryPath) {
        int slash = entryPath.indexOf('/');
        return slash >= 0 ? entryPath.substring(slash + 1) : entryPath;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void openInBrowser(WebApp app) {
        try {
            String url = server.urlFor(app.entryPath);
            startServerService();

            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            // 不包 createChooser，让系统弹窗直接提供「仅一次 / 始终」。
            startActivity(view);
        } catch (IOException e) {
            Toast.makeText(this, R.string.server_failed, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            stopService(new Intent(this, ServerService.class));
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
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
