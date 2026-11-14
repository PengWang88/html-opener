package com.example.htmlopener;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只监听 127.0.0.1 的极简静态文件服务。
 * 浏览器通过 http://127.0.0.1:<port>/t/<token>/... 访问所选 SAF 文件夹，
 * 这样 HTML 里的相对路径 JS/CSS/图片都能正常加载，且不会复制任何文件。
 */
final class HtmlServer {

    private static final long MAX_FILE_SIZE = 64L * 1024 * 1024;

    private static HtmlServer instance;

    static synchronized HtmlServer get(Context context) {
        if (instance == null) {
            instance = new HtmlServer(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final ExecutorService workers = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private int port;
    private String token;
    private volatile DocumentFile root;

    private HtmlServer(Context appContext) {
        this.appContext = appContext;
    }

    void setRoot(DocumentFile root) {
        this.root = root;
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
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(15000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // 请求头不需要，全部丢弃。
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String target = parts[1];
            boolean head = "HEAD".equals(method);
            if (!"GET".equals(method) && !head) {
                sendError(socket, 405, "Method Not Allowed");
                return;
            }

            int query = target.indexOf('?');
            if (query >= 0) {
                target = target.substring(0, query);
            }

            String prefix = "/t/" + token + "/";
            if (!target.startsWith(prefix)) {
                sendError(socket, 404, "Not Found");
                return;
            }

            String encodedPath = target.substring(prefix.length());
            if (encodedPath.isEmpty()) {
                sendError(socket, 404, "Not Found");
                return;
            }

            List<String> segments = new ArrayList<>();
            for (String raw : encodedPath.split("/", -1)) {
                String segment = Uri.decode(raw);
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                        || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
                    sendError(socket, 400, "Bad Request");
                    return;
                }
                segments.add(segment);
            }

            DocumentFile file = resolve(segments);
            if (file == null || !file.isFile()) {
                sendError(socket, 404, "Not Found");
                return;
            }
            if (file.length() > MAX_FILE_SIZE) {
                sendError(socket, 413, "File Too Large");
                return;
            }

            byte[] body;
            try (InputStream in = appContext.getContentResolver()
                    .openInputStream(file.getUri())) {
                if (in == null) {
                    sendError(socket, 404, "Not Found");
                    return;
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
            if (!head) {
                out.write(body);
            }
            out.flush();
        } catch (Exception ignored) {
            // 单个请求失败不影响后续请求。
        }
    }

    private DocumentFile resolve(List<String> segments) {
        DocumentFile current = root;
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

    private void sendError(Socket socket, int code, String message) {
        try {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 " + code + " " + message + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            // 已断开。
        }
    }
}
