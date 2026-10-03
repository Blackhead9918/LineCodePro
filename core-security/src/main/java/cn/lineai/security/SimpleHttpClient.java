package cn.lineai.security;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 统一 HTTP 基础设施，自动执行 UrlPolicy 校验、设置标准请求头、保证连接断开。
 *
 * <p>重定向由本类手动跟随（{@code setInstanceFollowRedirects(false)}），每一跳都要
 * 重新通过 {@link UrlPolicy} 校验，避免通过 30x 跳转绕过明文/主机策略；跨主机跳转时
 * 敏感请求头（Authorization、Cookie、API key 等）不会被转发。</p>
 */
public final class SimpleHttpClient {

    /** 手动跟随重定向的最大跳数。 */
    private static final int MAX_REDIRECT_HOPS = 5;

    private SimpleHttpClient() {
    }

    public static String postJson(String url, String jsonBody, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return postJson(url, jsonBody, connectTimeoutMs, readTimeoutMs, Collections.<String, String>emptyMap());
    }

    public static String postJson(String url, String jsonBody, int connectTimeoutMs, int readTimeoutMs,
                                  Map<String, String> headers) throws Exception {
        Request request = new Request(url, "POST", jsonBody);
        request.connectTimeoutMs = connectTimeoutMs;
        request.readTimeoutMs = readTimeoutMs;
        request.headers.put("Content-Type", "application/json");
        request.headers.put("Accept", "application/json");
        if (headers != null) {
            request.headers.putAll(headers);
        }
        Response response = execute(request);
        if (response.code < 200 || response.code >= 300) {
            throw new Exception("HTTP " + response.code + ": " + response.body);
        }
        return response.body;
    }

    public static String get(String url, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return get(url, connectTimeoutMs, readTimeoutMs, Collections.<String, String>emptyMap());
    }

    public static String get(String url, int connectTimeoutMs, int readTimeoutMs,
                             Map<String, String> headers) throws Exception {
        Request request = new Request(url, "GET", null);
        request.connectTimeoutMs = connectTimeoutMs;
        request.readTimeoutMs = readTimeoutMs;
        request.headers.put("Accept", "application/json");
        if (headers != null) {
            request.headers.putAll(headers);
        }
        Response response = execute(request);
        if (response.code < 200 || response.code >= 300) {
            throw new Exception("HTTP " + response.code + ": " + response.body);
        }
        return response.body;
    }

    public static DownloadResult download(String url, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return download(url, connectTimeoutMs, readTimeoutMs, Integer.MAX_VALUE);
    }

    public static DownloadResult download(String url, int connectTimeoutMs, int readTimeoutMs, int maxBytes) throws Exception {
        String currentUrl = url;
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                        UrlPolicy.requireHttpOrLocalCleartextUrl(currentUrl, "URL")
                ).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "LineCode/1.0");
                int code = connection.getResponseCode();
                if (isRedirect(code)) {
                    String location = connection.getHeaderField("Location");
                    if (hop == MAX_REDIRECT_HOPS) {
                        throw new Exception("HTTP download failed: too many redirects");
                    }
                    currentUrl = resolveRedirectUrl(currentUrl, location);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new Exception("HTTP download failed: " + code);
                }
                String mimeType = connection.getContentType();
                if (mimeType == null || mimeType.length() == 0) {
                    mimeType = "application/octet-stream";
                } else {
                    int semicolon = mimeType.indexOf(';');
                    if (semicolon > 0) {
                        mimeType = mimeType.substring(0, semicolon).trim();
                    }
                }
                return new DownloadResult(mimeType, readBytes(connection.getInputStream(), maxBytes));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw new Exception("HTTP download failed: too many redirects");
    }

    public static Response execute(Request request) throws Exception {
        String method = request.method == null ? "GET" : request.method;
        String body = request.body;
        String currentUrl = request.url;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(request.headers);
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            Response response = executeOnce(currentUrl, method, body, request, headers);
            String location = response.getLocation();
            if (!isRedirect(response.code) || location.length() == 0) {
                return response;
            }
            if (hop == MAX_REDIRECT_HOPS) {
                throw new Exception("HTTP " + response.code + ": too many redirects");
            }
            String nextUrl = resolveRedirectUrl(currentUrl, location);
            if (hostChanged(currentUrl, nextUrl)) {
                stripSensitiveHeaders(headers);
            }
            currentUrl = nextUrl;
            if (response.code == 303
                    || ((response.code == 301 || response.code == 302)
                    && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))) {
                // Match HTTP semantics (and HttpURLConnection's behaviour): 303 always
                // becomes GET, and 301/302 downgrade non-GET requests to GET.
                method = "GET";
                body = null;
                headers.remove("Content-Type");
                headers.remove("Content-Length");
            }
        }
        throw new Exception("HTTP redirect loop");
    }

    private static Response executeOnce(
            String url,
            String method,
            String body,
            Request request,
            Map<String, String> headers
    ) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    UrlPolicy.requireHttpOrLocalCleartextUrl(url, "URL")
            ).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(request.connectTimeoutMs);
            connection.setReadTimeout(request.readTimeoutMs);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream output = connection.getOutputStream();
                try {
                    output.write(bytes);
                } finally {
                    output.close();
                }
            }
            int code = connection.getResponseCode();
            // Redirect and error responses are read from the error stream; a redirect
            // does not need its body, only its Location header.
            InputStream stream = code >= 400 || isRedirect(code)
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String contentType = connection.getContentType();
            String message = connection.getResponseMessage();
            String bodyText = readStream(stream);
            return new Response(code, message, contentType, bodyText, connection.getHeaderField("Location"));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /**
     * Resolves a redirect {@code Location} against the current URL and re-validates the
     * result with {@link UrlPolicy}. Fail-closed: an unparseable or non-HTTP(S) target
     * throws instead of being followed.
     */
    private static String resolveRedirectUrl(String currentUrl, String location) throws Exception {
        String target = location == null ? "" : location.trim();
        if (target.length() == 0) {
            throw new Exception("HTTP redirect without a Location header");
        }
        String resolved;
        try {
            resolved = new URI(currentUrl).resolve(target).toString();
        } catch (Exception e) {
            throw new Exception("Invalid redirect location: " + target);
        }
        return UrlPolicy.requireHttpOrLocalCleartextUrl(resolved, "Redirect URL");
    }

    private static boolean hostChanged(String currentUrl, String nextUrl) {
        String currentHost = hostOf(currentUrl);
        String nextHost = hostOf(nextUrl);
        return currentHost.length() > 0 && nextHost.length() > 0 && !currentHost.equalsIgnoreCase(nextHost);
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Drops credentials when a redirect leaves the original host. */
    private static void stripSensitiveHeaders(Map<String, String> headers) {
        headers.remove("Authorization");
        headers.remove("Cookie");
        for (String key : new ArrayList<>(headers.keySet())) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("api-key") || lower.contains("apikey") || lower.contains("x-api")
                    || lower.contains("access-token") || lower.contains("auth")) {
                headers.remove(key);
            }
        }
    }

    public static String readStream(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            input.close();
        }
    }

    public static byte[] readBytes(InputStream input, int maxBytes) throws Exception {
        if (input == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new Exception("Data too large, current limit is " + (maxBytes / 1024 / 1024) + " MB.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    public static final class Request {
        public String url;
        public String method;
        public String body;
        public int connectTimeoutMs = 15000;
        public int readTimeoutMs = 30000;
        public final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

        public Request(String url, String method, String body) {
            this.url = url;
            this.method = method;
            this.body = body;
        }
    }

    public static final class Response {
        public final int code;
        public final String message;
        public final String contentType;
        public final String body;
        /** Raw {@code Location} header of the final response, empty when absent. */
        private final String location;

        public Response(int code, String message, String contentType, String body) {
            this(code, message, contentType, body, "");
        }

        public Response(int code, String message, String contentType, String body, String location) {
            this.code = code;
            this.message = message == null ? "" : message;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body == null ? "" : body;
            this.location = location == null ? "" : location;
        }

        public String getLocation() {
            return location;
        }
    }

    public static final class DownloadResult {
        public final String mimeType;
        public final byte[] bytes;

        public DownloadResult(String mimeType, byte[] bytes) {
            this.mimeType = mimeType == null || mimeType.length() == 0 ? "image/png" : mimeType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }
}
