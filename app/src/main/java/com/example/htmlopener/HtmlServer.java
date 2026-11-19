package com.example.htmlopener;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只监听 127.0.0.1 的极简静态文件服务。
 * 浏览器通过 http://127.0.0.1:<port>/t/<token>/... 访问所选 SAF 文件夹，
 * 这样 HTML 里的相对路径 JS/CSS/图片都能正常加载，且不会复制任何文件。
 *
 * 同一个服务也提供受限制的写接口，供页面保存 JSON 数据：
 * - PUT    <应用目录>/state.json
 * - PUT    <应用目录>/articles/<name>.json
 * - DELETE <应用目录>/state.json
 * - DELETE <应用目录>/articles/<name>.json
 */
final class HtmlServer {

    private static final long MAX_FILE_SIZE = 64L * 1024 * 1024;
    /** 写入请求体上限：1 MiB。 */
    private static final int MAX_WRITE_BYTES = 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADER_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_TARGET_LENGTH = 4096;
    private static final int MAX_SEGMENTS = 32;

    private static final String JSON_TYPE = "application/json";
    private static final String ERROR_READ_ONLY =
            "当前文件夹只读：请在 HTML 打开器中重新导入目录并允许修改";
    private static final String ERROR_SCOPE =
            "只能修改当前应用的 state.json 或 articles/*.json";

    private static HtmlServer instance;

    static synchronized HtmlServer get(Context context) {
        if (instance == null) {
            instance = new HtmlServer(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    /** 写操作串行化，避免并发创建 articles 目录或写同一文件。 */
    private final Object writeLock = new Object();

    private ServerSocket serverSocket;
    private int port;
    private String token;
    private volatile DocumentFile root;
    private volatile boolean writable;

    private HtmlServer(Context appContext) {
        this.appContext = appContext;
    }

    void setRoot(DocumentFile root) {
        setRoot(root, root != null && root.canWrite());
    }

    void setRoot(DocumentFile root, boolean writable) {
        this.root = root;
        this.writable = writable;
    }

    synchronized String urlFor(String fileName) throws IOException {
        ensureStarted();
        StringBuilder url = new StringBuilder("http://127.0.0.1:")
                .append(port).append("/t/").append(token).append('/');
        String[] segments = fileName.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                url.append('/');
            }
            url.append(Uri.encode(segments[i]));
        }
        return url.toString();
    }

    private void ensureStarted() throws IOException {
        if (serverSocket != null) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        // 必须显式绑定 IPv4：部分设备上 getLoopbackAddress() 返回 ::1，
        // 而浏览器访问的是 127.0.0.1，会得到 ERR_CONNECTION_REFUSED。
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        serverSocket = socket;
        port = socket.getLocalPort();
        token = randomToken();

        Thread accept = new Thread(this::acceptLoop, "html-file-server");
        accept.setDaemon(true);
        accept.start();
    }

    private String randomToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket socket = serverSocket;
            if (socket == null || socket.isClosed()) {
                return;
            }
            try {
                Socket client = socket.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return;
                }
                // 避免 accept 持续失败时占满 CPU。
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(15000);
            try {
                Request request = readRequest(
                        socket.getInputStream(), socket.getOutputStream());
                route(socket, request);
            } catch (HttpException e) {
                sendError(socket, e.status, e.getMessage());
            } catch (Exception e) {
                sendError(socket, 500, "服务器内部错误");
            }
        } catch (Exception ignored) {
            // 连接已断开、超时或响应写入失败。
        }
    }

    /**
     * 从原始 InputStream 读取请求行和请求头，再按 Content-Length 精确读取请求体。
     * 不使用 BufferedReader，避免它预读并吞掉请求体开头。
     */
    private Request readRequest(InputStream in, OutputStream out)
            throws IOException, HttpException {
        String requestLine = readHeaderLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new HttpException(400, "请求为空");
        }

        int headerBytes = requestLine.length();
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || parts.length > 3) {
            throw new HttpException(400, "请求行格式错误");
        }

        Request request = new Request();
        request.method = parts[0];
        request.target = parts[1];
        if (request.target.length() > MAX_TARGET_LENGTH) {
            throw new HttpException(400, "请求路径过长");
        }

        while (true) {
            String line = readHeaderLine(in);
            if (line == null) {
                throw new HttpException(400, "请求头不完整");
            }
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new HttpException(400, "请求头过大");
            }
            if (line.isEmpty()) {
                break;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(400, "请求头格式错误");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.isEmpty()) {
                throw new HttpException(400, "请求头格式错误");
            }
            if (request.headers.size() >= MAX_HEADERS) {
                throw new HttpException(400, "请求头过多");
            }
            if ("content-length".equals(name) && request.headers.containsKey(name)) {
                throw new HttpException(400, "Content-Length 重复");
            }
            request.headers.put(name, value);
        }

        if ("PUT".equals(request.method)) {
            if (request.headers.containsKey("transfer-encoding")) {
                throw new HttpException(400, "不支持 Transfer-Encoding");
            }
            String lengthHeader = request.headers.get("content-length");
            if (lengthHeader == null) {
                throw new HttpException(400, "缺少 Content-Length");
            }
            long length;
            try {
                length = Long.parseLong(lengthHeader.trim());
            } catch (NumberFormatException e) {
                throw new HttpException(400, "Content-Length 不合法");
            }
            if (length < 0) {
                throw new HttpException(400, "Content-Length 不合法");
            }
            if (length > MAX_WRITE_BYTES) {
                throw new HttpException(413, "请求体超过 1 MB 限制");
            }
            if (!isJsonContentType(request.headers.get("content-type"))) {
                throw new HttpException(400, "Content-Type 必须为 application/json");
            }

            String expect = request.headers.get("expect");
            if (expect != null && "100-continue".equalsIgnoreCase(expect.trim())) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }

            request.body = readExactly(in, (int) length);
        }

        return request;
    }

    private String readHeaderLine(InputStream in) throws IOException, HttpException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                if (length > MAX_HEADER_LINE) {
                    throw new HttpException(400, "请求头过长");
                }
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            if (line.size() > MAX_HEADER_LINE + 1) {
                throw new HttpException(400, "请求头过长");
            }
        }
        if (line.size() == 0) {
            return null;
        }
        throw new HttpException(400, "请求头不完整");
    }

    private byte[] readExactly(InputStream in, int length) throws IOException, HttpException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(body, offset, length - offset);
            if (read == -1) {
                throw new HttpException(400, "请求体不完整");
            }
            offset += read;
        }
        return body;
    }

    private void route(Socket socket, Request request) throws IOException, HttpException {
        String method = request.method;
        if (!"GET".equals(method) && !"HEAD".equals(method)
                && !"PUT".equals(method) && !"DELETE".equals(method)) {
            sendError(socket, 405, "不支持的请求方法",
                    "Allow: GET, HEAD, PUT, DELETE\r\n");
            return;
        }

        List<String> segments = parseSegments(request.target);
        if ("GET".equals(method) || "HEAD".equals(method)) {
            serveFile(socket, request, segments);
        } else if ("PUT".equals(method)) {
            putFile(socket, request, segments);
        } else {
            deleteFile(socket, request, segments);
        }
    }

    private List<String> parseSegments(String target) throws HttpException {
        int query = target.indexOf('?');
        if (query >= 0) {
            target = target.substring(0, query);
        }

        String prefix = "/t/" + token + "/";
        if (!target.startsWith(prefix)) {
            throw new HttpException(404, "Not Found");
        }

        String encodedPath = target.substring(prefix.length());
        if (encodedPath.isEmpty()) {
            throw new HttpException(404, "Not Found");
        }

        List<String> segments = new ArrayList<>();
        for (String raw : encodedPath.split("/", -1)) {
            String segment;
            try {
                segment = Uri.decode(raw);
            } catch (IllegalArgumentException e) {
                throw new HttpException(400, "路径编码错误");
            }
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0
                    || segment.indexOf('\0') >= 0) {
                throw new HttpException(400, "路径不合法");
            }
            segments.add(segment);
            if (segments.size() > MAX_SEGMENTS) {
                throw new HttpException(400, "路径层级过深");
            }
        }
        return segments;
    }

    private void serveFile(Socket socket, Request request, List<String> segments)
            throws IOException, HttpException {
        DocumentFile file = resolve(root, segments);
        if (file == null || !file.isFile()) {
            throw new HttpException(404, "文件不存在");
        }
        if (file.length() > MAX_FILE_SIZE) {
            throw new HttpException(413, "文件过大");
        }

        byte[] body;
        try (InputStream in = appContext.getContentResolver()
                .openInputStream(file.getUri())) {
            if (in == null) {
                throw new HttpException(404, "文件不存在");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            body = buffer.toByteArray();
        }

        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + mimeType(file.getName()) + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!"HEAD".equals(request.method)) {
            out.write(body);
        }
        out.flush();
    }

    private void putFile(Socket socket, Request request, List<String> segments)
            throws IOException, HttpException {
        if (!isJsonName(segments.get(segments.size() - 1))) {
            throw new HttpException(403, "只允许写入 .json 文件");
        }

        synchronized (writeLock) {
            DocumentFile base = root;
            if (base == null) {
                throw new HttpException(404, "尚未导入文件夹");
            }
            if (!canModify(base)) {
                throw new HttpException(403, ERROR_READ_ONLY);
            }

            WriteTarget target = resolveWriteTarget(
                    base, request.headers.get("referer"), segments);
            if (!canModify(target.appDir)) {
                throw new HttpException(403, ERROR_READ_ONLY);
            }

            DocumentFile parent = target.appDir;
            for (int i = 0; i < target.relative.size() - 1; i++) {
                parent = ensureDirectory(parent, target.relative.get(i));
            }

            String fileName = target.relative.get(target.relative.size() - 1);
            DocumentFile file = parent.findFile(fileName);
            boolean existed = file != null;
            if (existed && !file.isFile()) {
                throw new HttpException(403, "同名目录已存在，无法写入");
            }
            if (existed && !canModify(file)) {
                throw new HttpException(403, ERROR_READ_ONLY);
            }
            if (!existed) {
                try {
                    file = parent.createFile(JSON_TYPE, fileName);
                } catch (SecurityException e) {
                    throw new HttpException(403, ERROR_READ_ONLY);
                } catch (Exception e) {
                    throw new HttpException(500, "创建文件失败");
                }
                if (file == null) {
                    if (!canModify(parent)) {
                        throw new HttpException(403, ERROR_READ_ONLY);
                    }
                    throw new HttpException(500, "创建文件失败");
                }
            }

            try (OutputStream out = openOutputStream(file)) {
                out.write(request.body);
                out.flush();
            } catch (SecurityException e) {
                deleteQuietly(file, existed);
                throw new HttpException(403, ERROR_READ_ONLY);
            } catch (IOException e) {
                deleteQuietly(file, existed);
                if (!canModify(base) || looksReadOnly(e)) {
                    throw new HttpException(403, ERROR_READ_ONLY);
                }
                throw new HttpException(500, "写入文件失败");
            }

            sendEmpty(socket, existed ? 204 : 201);
        }
    }

    private void deleteFile(Socket socket, Request request, List<String> segments)
            throws IOException, HttpException {
        if (!isJsonName(segments.get(segments.size() - 1))) {
            throw new HttpException(403, "只允许删除 .json 文件");
        }

        synchronized (writeLock) {
            DocumentFile base = root;
            if (base == null) {
                throw new HttpException(404, "尚未导入文件夹");
            }
            if (!canModify(base)) {
                throw new HttpException(403, ERROR_READ_ONLY);
            }

            WriteTarget target = resolveWriteTarget(
                    base, request.headers.get("referer"), segments);
            if (!canModify(target.appDir)) {
                throw new HttpException(403, ERROR_READ_ONLY);
            }

            DocumentFile file = resolve(target.appDir, target.relative);
            if (file == null) {
                throw new HttpException(404, "文件不存在");
            }
            if (file.isDirectory()) {
                throw new HttpException(403, "不能删除目录");
            }
            if (!file.isFile()) {
                throw new HttpException(404, "文件不存在");
            }

            boolean deleted;
            try {
                deleted = file.delete();
            } catch (SecurityException e) {
                throw new HttpException(403, ERROR_READ_ONLY);
            } catch (Exception e) {
                throw new HttpException(500, "删除文件失败");
            }
            if (!deleted) {
                if (!canModify(base)) {
                    throw new HttpException(403, ERROR_READ_ONLY);
                }
                throw new HttpException(500, "删除文件失败");
            }
            sendEmpty(socket, 204);
        }
    }

    private DocumentFile ensureDirectory(DocumentFile parent, String name)
            throws HttpException {
        DocumentFile existing = parent.findFile(name);
        if (existing != null) {
            if (existing.isDirectory()) {
                if (!canModify(existing)) {
                    throw new HttpException(403, ERROR_READ_ONLY);
                }
                return existing;
            }
            throw new HttpException(403, "同名文件已存在，无法创建目录");
        }

        DocumentFile created;
        try {
            created = parent.createDirectory(name);
        } catch (SecurityException e) {
            throw new HttpException(403, ERROR_READ_ONLY);
        } catch (Exception e) {
            throw new HttpException(500, "创建目录失败");
        }
        if (created == null) {
            if (!canModify(parent)) {
                throw new HttpException(403, ERROR_READ_ONLY);
            }
            throw new HttpException(500, "创建目录失败");
        }
        return created;
    }

    /**
     * 解析写入目标：
     * - 有同源 Referer 时，限定为 Referer 所在应用的 state.json / articles/*.json；
     * - 缺少 Referer 时（例如命令行工具），只按路径形状做兜底校验。
     */
    private WriteTarget resolveWriteTarget(DocumentFile base, String referer,
                                           List<String> segments) throws HttpException {
        String currentApp = appFromReferer(base, referer);
        if (currentApp != null) {
            List<String> relative;
            if (currentApp.isEmpty()) {
                relative = new ArrayList<>(segments);
            } else {
                if (segments.size() < 2 || !currentApp.equals(segments.get(0))) {
                    throw new HttpException(403, ERROR_SCOPE);
                }
                relative = new ArrayList<>(segments.subList(1, segments.size()));
            }
            if (!isStateOrArticle(relative)) {
                throw new HttpException(403, ERROR_SCOPE);
            }

            DocumentFile appDir = base;
            if (!currentApp.isEmpty()) {
                appDir = base.findFile(currentApp);
                if (appDir == null || !appDir.isDirectory()) {
                    throw new HttpException(404, "应用目录不存在");
                }
            }
            return new WriteTarget(appDir, relative);
        }

        // 没有 Referer：只能使用 /state.json、/articles/*.json 或 <应用>/... 形式。
        if (segments.size() == 1) {
            if ("state.json".equals(segments.get(0))) {
                return new WriteTarget(base, new ArrayList<>(segments));
            }
            throw new HttpException(403, ERROR_SCOPE);
        }
        if (segments.size() == 2) {
            if ("articles".equals(segments.get(0))
                    && isJsonName(segments.get(1))) {
                List<String> relative = new ArrayList<>();
                relative.add("articles");
                relative.add(segments.get(1));
                return new WriteTarget(base, relative);
            }
            if ("state.json".equals(segments.get(1))) {
                DocumentFile appDir = findAppDir(base, segments.get(0));
                return new WriteTarget(appDir,
                        singleItemList("state.json"));
            }
            throw new HttpException(403, ERROR_SCOPE);
        }
        if (segments.size() == 3 && "articles".equals(segments.get(1))
                && isJsonName(segments.get(2))) {
            DocumentFile appDir = findAppDir(base, segments.get(0));
            List<String> relative = new ArrayList<>();
            relative.add("articles");
            relative.add(segments.get(2));
            return new WriteTarget(appDir, relative);
        }
        throw new HttpException(403, ERROR_SCOPE);
    }

    private DocumentFile findAppDir(DocumentFile base, String name)
            throws HttpException {
        DocumentFile appDir = base.findFile(name);
        if (appDir == null || !appDir.isDirectory()) {
            throw new HttpException(404, "应用目录不存在");
        }
        return appDir;
    }

    private List<String> singleItemList(String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    private boolean isStateOrArticle(List<String> relative) {
        if (relative.size() == 1) {
            return "state.json".equals(relative.get(0));
        }
        if (relative.size() == 2) {
            return "articles".equals(relative.get(0))
                    && isJsonName(relative.get(1));
        }
        return false;
    }

    /**
     * 从同源 Referer 推断当前页面所属的应用目录。
     * 返回 null 表示没有 Referer；返回空字符串表示页面位于父目录根部。
     */
    private String appFromReferer(DocumentFile base, String referer)
            throws HttpException {
        if (referer == null || referer.isEmpty()) {
            return null;
        }

        Uri uri;
        try {
            uri = Uri.parse(referer);
        } catch (Exception e) {
            throw new HttpException(403, "Referer 不合法");
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !"http".equalsIgnoreCase(scheme)
                || host == null
                || !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                || uri.getPort() != port) {
            throw new HttpException(403, "跨来源写入被拒绝");
        }

        String path = uri.getEncodedPath();
        String prefix = "/t/" + token + "/";
        if (path == null || !path.startsWith(prefix)) {
            throw new HttpException(403, "Referer 不合法");
        }
        String rest = path.substring(prefix.length());
        if (rest.isEmpty()) {
            throw new HttpException(403, "Referer 不合法");
        }

        String firstRaw = rest.split("/", 2)[0];
        String first;
        try {
            first = Uri.decode(firstRaw);
        } catch (IllegalArgumentException e) {
            throw new HttpException(403, "Referer 不合法");
        }
        if (first.isEmpty() || ".".equals(first) || "..".equals(first)
                || first.indexOf('/') >= 0 || first.indexOf('\\') >= 0
                || first.indexOf('\0') >= 0) {
            throw new HttpException(403, "Referer 不合法");
        }

        DocumentFile firstDoc = base.findFile(first);
        if (firstDoc == null) {
            throw new HttpException(403, "Referer 指向的页面不存在");
        }
        return firstDoc.isDirectory() ? first : "";
    }

    private boolean canModify(DocumentFile dir) {
        if (!writable) {
            return false;
        }
        try {
            return dir.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    private OutputStream openOutputStream(DocumentFile file) throws IOException {
        try {
            OutputStream out = appContext.getContentResolver()
                    .openOutputStream(file.getUri(), "wt");
            if (out != null) {
                return out;
            }
        } catch (FileNotFoundException | IllegalArgumentException ignored) {
            // 部分 Provider 不支持 "wt"，退回 "w"。
        }
        OutputStream out = appContext.getContentResolver()
                .openOutputStream(file.getUri(), "w");
        if (out == null) {
            throw new IOException("无法打开输出流");
        }
        return out;
    }

    private void deleteQuietly(DocumentFile file, boolean existed) {
        if (existed) {
            return;
        }
        try {
            file.delete();
        } catch (Exception ignored) {
            // 清理失败时保留文件，避免掩盖原始错误。
        }
    }

    private boolean isJsonContentType(String value) {
        if (value == null) {
            return false;
        }
        int semicolon = value.indexOf(';');
        String type = (semicolon >= 0 ? value.substring(0, semicolon) : value).trim();
        return JSON_TYPE.equalsIgnoreCase(type);
    }

    /** 部分文件系统在无写权限时抛出的 IO 错误，映射为 403 更清晰。 */
    private boolean looksReadOnly(IOException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("eacces")
                || lower.contains("erofs")
                || lower.contains("permission denied")
                || lower.contains("read-only");
    }

    private boolean isJsonName(String name) {
        return name.length() > 5
                && name.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private DocumentFile resolve(DocumentFile base, List<String> segments) {
        DocumentFile current = base;
        for (String segment : segments) {
            if (current == null || !current.isDirectory()) {
                return null;
            }
            current = current.findFile(segment);
        }
        return current;
    }

    private String mimeType(String name) {
        String extension = "";
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }

        String mime;
        switch (extension) {
            case "html":
            case "htm":
                mime = "text/html";
                break;
            case "css":
                mime = "text/css";
                break;
            case "js":
            case "mjs":
                mime = "application/javascript";
                break;
            case "json":
                mime = "application/json";
                break;
            case "xml":
                mime = "application/xml";
                break;
            case "svg":
                mime = "image/svg+xml";
                break;
            case "wasm":
                mime = "application/wasm";
                break;
            default:
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                break;
        }

        if (mime == null) {
            mime = "application/octet-stream";
        }
        if (mime.startsWith("text/") || mime.contains("javascript")
                || mime.contains("json") || mime.contains("xml")) {
            mime += "; charset=utf-8";
        }
        return mime;
    }

    private void sendEmpty(Socket socket, int code) throws IOException {
        String headers = "HTTP/1.1 " + code + " " + reasonPhrase(code) + "\r\n"
                + "Content-Length: 0\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void sendError(Socket socket, int code, String message) throws IOException {
        sendError(socket, code, message, "");
    }

    private void sendError(Socket socket, int code, String message, String extraHeaders)
            throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + " " + reasonPhrase(code) + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + extraHeaders
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private String reasonPhrase(int code) {
        switch (code) {
            case 201:
                return "Created";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 500:
                return "Internal Server Error";
            default:
                return "Error";
        }
    }

    private static final class Request {
        String method;
        String target;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private static final class WriteTarget {
        final DocumentFile appDir;
        final List<String> relative;

        WriteTarget(DocumentFile appDir, List<String> relative) {
            this.appDir = appDir;
            this.relative = relative;
        }
    }

    private static final class HttpException extends Exception {
        final int status;

        HttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
