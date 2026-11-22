package com.aeginning.adskip.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.aeginning.adskip.engine.DnsFeedParser;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resilient, bounded HTTPS downloader for maintained DNS domain feeds.
 *
 * Each source has multiple HTTPS mirrors. The downloader tries each mirror once
 * before spending a small retry budget on transient failures. Parsing and database
 * replacement remain separate: callers import only a fully downloaded, validated feed.
 */
public final class DnsFeedManager {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 7_000;
    private static final long MAX_MIRROR_ATTEMPT_MS = 12_000L;
    private static final long MAX_DOWNLOAD_BYTES = 12L * 1024L * 1024L;
    private static final long MAX_TOTAL_TIME_MS = 90_000L;
    private static final int MAX_EXTRA_RETRIES = 3;
    private static final long[] RETRY_BACKOFF_MS = {1_000L, 2_500L, 5_000L};
    private static final int MAX_DIAGNOSTICS = 8;

    public enum Source {
        HAGEZI_LIGHT(
                "hagezi-light",
                "HaGeZi Multi LIGHT",
                DnsFeedParser.Format.DNSMASQ,
                "轻量广告与追踪域名规则，优先考虑兼容性；建议先启用",
                new String[]{
                        "https://hagezi-mirror.dnsbunker.org/dnsmasq/light.txt",
                        "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/dnsmasq/light.txt",
                        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/dnsmasq/light.txt",
                        "https://gitlab.com/hagezi/mirror/-/raw/main/dns-blocklists/dnsmasq/light.txt",
                        "https://codeberg.org/hagezi/mirror2/raw/branch/main/dns-blocklists/dnsmasq/light.txt"
                }),
        STEVENBLACK_BASE(
                "stevenblack-base",
                "StevenBlack Unified Hosts",
                DnsFeedParser.Format.HOSTS,
                "广告、恶意软件与追踪域名聚合，覆盖更广；误拦截风险高于轻量名单",
                new String[]{
                        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                        "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
                        "https://fastly.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
                        "https://gcore.jsdelivr.net/gh/StevenBlack/hosts@master/hosts"
                }),
        ADAWAY_HOSTS(
                "adaway-hosts",
                "AdAway Official Hosts",
                DnsFeedParser.Format.HOSTS,
                "AdAway 维护的移动广告与跟踪域名名单；与 HaGeZi、StevenBlack 使用不同维护项目",
                new String[]{
                        "https://adaway.org/hosts.txt",
                        "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
                        "https://cdn.jsdelivr.net/gh/AdAway/adaway.github.io@master/hosts.txt"
                }),
        ONEHOSTS_LITE(
                "1hosts-lite",
                "1Hosts Lite",
                DnsFeedParser.Format.DNSMASQ,
                "偏兼容性的轻量广告、跟踪与恶意域名名单；使用约 10 万条的 dnsmasq 格式，优先选择 Lite 而非高误拦截的 Xtra",
                new String[]{
                        "https://badmojr.github.io/1Hosts/Lite/dnsmasq.conf",
                        "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/dnsmasq.conf",
                        "https://cdn.jsdelivr.net/gh/badmojr/1Hosts@master/Lite/dnsmasq.conf"
                }),
        DAN_POLLOCK_HOSTS(
                "dan-pollock-hosts",
                "Dan Pollock Hosts",
                DnsFeedParser.Format.HOSTS,
                "独立维护的广告与隐私 hosts 名单；仅使用官方来源",
                new String[]{
                        "https://someonewhocares.org/hosts/zero/hosts"
                });

        public final String id;
        public final String displayName;
        /** Primary endpoint, retained for compatibility with existing UI/integrations. */
        public final String url;
        public final DnsFeedParser.Format format;
        public final String description;
        public final List<String> mirrorUrls;

        Source(String id, String displayName, DnsFeedParser.Format format,
               String description, String[] urls) {
            this.id = id;
            this.displayName = displayName;
            this.format = format;
            this.description = description;
            List<String> safeUrls = new ArrayList<>();
            for (String candidate : urls) {
                if (candidate == null || !candidate.startsWith("https://")) {
                    throw new IllegalArgumentException("DNS feed mirrors must use HTTPS");
                }
                safeUrls.add(candidate);
            }
            if (safeUrls.isEmpty()) throw new IllegalArgumentException("DNS feed requires a mirror");
            this.mirrorUrls = Collections.unmodifiableList(safeUrls);
            this.url = safeUrls.get(0);
        }
    }

    public interface ProgressListener {
        void onProgress(String message);
    }

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public enum FailureKind {
        CONNECTION,
        HTTP,
        VALIDATION,
        SIZE_LIMIT,
        REDIRECT,
        TIME_BUDGET,
        INTERRUPTED,
        UNKNOWN
    }

    public static final class DownloadException extends IOException {
        public final FailureKind kind;
        public final int attempts;
        public final String diagnostics;

        DownloadException(FailureKind kind, int attempts, String diagnostics, Throwable cause) {
            super(buildMessage(kind, attempts, diagnostics), cause);
            this.kind = kind;
            this.attempts = attempts;
            this.diagnostics = diagnostics == null ? "" : diagnostics;
        }

        public String toUserMessage() {
            String detail;
            switch (kind) {
                case CONNECTION:
                    detail = "所有可用镜像均连接失败，可能是连接重置、超时或 DNS 解析失败。请检查当前网络、系统私有 DNS、代理或其他 VPN，也可以切换 Wi-Fi / 移动数据后重试。";
                    break;
                case HTTP:
                    detail = "规则服务器返回了异常 HTTP 状态。已自动尝试其他镜像；请稍后重试。";
                    break;
                case VALIDATION:
                    detail = "服务器返回的文件未通过规则格式或域名数量校验，可能拿到的是错误页面或不完整文件。为保护现有规则，本次没有导入。";
                    break;
                case SIZE_LIMIT:
                    detail = "下载文件超过 12 MiB 安全上限，已停止导入，现有订阅未修改。";
                    break;
                case REDIRECT:
                    detail = "规则源重定向到了非 HTTPS 地址，出于安全原因已拒绝该连接。";
                    break;
                case TIME_BUDGET:
                    detail = "本次更新达到 90 秒网络时间上限。已停止继续请求，原有订阅保持不变。";
                    break;
                case INTERRUPTED:
                    detail = "更新任务被系统中断，原有订阅保持不变。";
                    break;
                default:
                    detail = "DNS 订阅更新失败，原有订阅保持不变。";
                    break;
            }
            return detail + "\n尝试次数：" + attempts
                    + (diagnostics.isEmpty() ? "" : "\n诊断：" + diagnostics);
        }

        private static String buildMessage(FailureKind kind, int attempts, String diagnostics) {
            return "DNS 订阅下载失败（" + kind + "，尝试 " + attempts + " 次）"
                    + (diagnostics == null || diagnostics.isEmpty() ? "" : "：" + diagnostics);
        }
    }

    public static final class DownloadedFeed {
        public final Source source;
        public final DnsFeedParser.ParsedFeed parsed;
        public final String downloadedFrom;
        public final int attempts;

        DownloadedFeed(Source source, DnsFeedParser.ParsedFeed parsed,
                       String downloadedFrom, int attempts) {
            this.source = source;
            this.parsed = parsed;
            this.downloadedFrom = downloadedFrom;
            this.attempts = attempts;
        }
    }

    private static final class RetryCandidate {
        final String url;
        final String label;

        RetryCandidate(String url, String label) {
            this.url = url;
            this.label = label;
        }
    }

    private static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private static final class SizeLimitException extends IOException {
        SizeLimitException() { super("下载内容超过 12 MiB"); }
    }

    private static final class AttemptFailure {
        final FailureKind kind;
        final boolean retryable;

        AttemptFailure(FailureKind kind, boolean retryable) {
            this.kind = kind;
            this.retryable = retryable;
        }
    }

    private DnsFeedManager() { }

    public static DownloadedFeed download(Source source) throws IOException {
        return download(source, (ProgressListener) null);
    }

    public static DownloadedFeed download(Source source, ProgressListener listener) throws IOException {
        return download(source,
                url -> (HttpURLConnection) url.openConnection(),
                Thread::sleep,
                listener);
    }

    /** Uses the current Android default network on every attempt, including after network handover. */
    public static DownloadedFeed download(Source source, Context context,
                                          ProgressListener listener) throws IOException {
        if (context == null) throw new IllegalArgumentException("Android context required");
        Context appContext = context.getApplicationContext();
        String networkLabel = describeActiveNetwork(appContext);
        notifyProgress(listener, "当前网络：" + networkLabel + "；准备连接 DNS 订阅源…");
        return download(source,
                url -> openOnActiveNetwork(appContext, url),
                Thread::sleep,
                listener);
    }

    /** No SSID, IP address, or other identifying network details are exposed. */
    public static String describeActiveNetwork(Context context) {
        if (context == null) return "网络状态未知";
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return "网络状态未知";
            Network active = manager.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
            if (caps == null) return "未检测到可用网络";
            String type;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type = "VPN";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi-Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "移动网络";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "以太网";
            else type = "其他网络";
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return type + "（无互联网能力）";
            }
            return type + (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? "（互联网已验证）" : "（互联网尚未验证）");
        } catch (RuntimeException unavailable) {
            return "网络状态暂不可读取";
        }
    }

    private static HttpURLConnection openOnActiveNetwork(Context context, URL url) throws IOException {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager != null) {
            Network active = manager.getActiveNetwork();
            if (active != null) {
                try {
                    java.net.URLConnection opened = active.openConnection(url);
                    if (!(opened instanceof HttpURLConnection)) {
                        throw new IOException("当前网络未提供 HTTP 连接");
                    }
                    return (HttpURLConnection) opened;
                } catch (RuntimeException networkChanged) {
                    // The default network can disappear between getActiveNetwork() and openConnection().
                    // Re-resolve through the system default route; the next mirror re-reads it again.
                }
            }
        }
        return (HttpURLConnection) url.openConnection();
    }

    static DownloadedFeed download(Source source, ConnectionFactory connections,
                                   Sleeper sleeper, ProgressListener listener) throws IOException {
        if (source == null) throw new IllegalArgumentException("DNS feed source required");
        if (connections == null || sleeper == null) throw new IllegalArgumentException("Downloader dependencies required");

        final long deadlineNanos = System.nanoTime()
                + MAX_TOTAL_TIME_MS * 1_000_000L;
        final List<String> diagnostics = new ArrayList<>();
        final List<RetryCandidate> retryQueue = new ArrayList<>();
        int attempts = 0;
        FailureKind lastKind = FailureKind.UNKNOWN;
        IOException lastFailure = null;

        // Pass one: try every mirror once before retrying a slow or unstable endpoint.
        for (int index = 0; index < source.mirrorUrls.size(); index++) {
            String endpoint = source.mirrorUrls.get(index);
            attempts++;
            notifyProgress(listener, "正在尝试镜像 " + (index + 1) + "/"
                    + source.mirrorUrls.size() + "：" + mirrorHost(endpoint) + "…");

            try {
                DnsFeedParser.ParsedFeed parsed = downloadOne(
                        source, endpoint, connections, deadlineNanos);
                notifyProgress(listener, "规则文件校验通过，正在保存 " + parsed.domains.size() + " 条域名…");
                return new DownloadedFeed(source, parsed, endpoint, attempts);
            } catch (IOException failure) {
                AttemptFailure classified = classify(failure);
                lastKind = classified.kind;
                lastFailure = failure;
                addDiagnostic(diagnostics, mirrorHost(endpoint) + "：" + compactMessage(failure));
                if (classified.retryable) retryQueue.add(new RetryCandidate(endpoint, mirrorHost(endpoint)));
                if (isDeadlineReached(deadlineNanos)) {
                    lastKind = FailureKind.TIME_BUDGET;
                    break;
                }
                if (index + 1 < source.mirrorUrls.size()) {
                    notifyProgress(listener, mirrorHost(endpoint) + " 失败，自动切换备用镜像…");
                }
            }
        }

        // Pass two: only transient failures receive another request. Keep the retry
        // budget small so a broken network cannot hold the UI task indefinitely.
        int extraRetries = 0;
        for (RetryCandidate candidate : retryQueue) {
            if (extraRetries >= MAX_EXTRA_RETRIES) break;
            if (isDeadlineReached(deadlineNanos)) {
                lastKind = FailureKind.TIME_BUDGET;
                break;
            }

            long waitMs = RETRY_BACKOFF_MS[Math.min(extraRetries, RETRY_BACKOFF_MS.length - 1)];
            try {
                notifyProgress(listener, candidate.label + " 暂时连接失败，" + waitMs
                        + " 毫秒后进行限次重试…");
                sleeper.sleep(waitMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DownloadException(FailureKind.INTERRUPTED, attempts,
                        joinDiagnostics(diagnostics), interrupted);
            }

            if (isDeadlineReached(deadlineNanos)) {
                lastKind = FailureKind.TIME_BUDGET;
                break;
            }
            attempts++;
            extraRetries++;
            try {
                DnsFeedParser.ParsedFeed parsed = downloadOne(
                        source, candidate.url, connections, deadlineNanos);
                notifyProgress(listener, "备用重试校验通过，正在保存 "
                        + parsed.domains.size() + " 条域名…");
                return new DownloadedFeed(source, parsed, candidate.url, attempts);
            } catch (IOException failure) {
                AttemptFailure classified = classify(failure);
                lastKind = classified.kind;
                lastFailure = failure;
                addDiagnostic(diagnostics, candidate.label + " 重试：" + compactMessage(failure));
            }
        }

        if (isDeadlineReached(deadlineNanos)) lastKind = FailureKind.TIME_BUDGET;
        throw new DownloadException(lastKind, attempts, joinDiagnostics(diagnostics), lastFailure);
    }

    private static DnsFeedParser.ParsedFeed downloadOne(
            Source source, String endpoint, ConnectionFactory connections,
            long deadlineNanos) throws IOException {
        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("只允许 HTTPS DNS 订阅源");
        }

        long attemptDeadlineNanos = Math.min(deadlineNanos,
                System.nanoTime() + MAX_MIRROR_ATTEMPT_MS * 1_000_000L);
        long remainingMs = remainingMillis(attemptDeadlineNanos);
        if (remainingMs <= 0) throw new IOException("下载总时间达到上限");
        HttpURLConnection connection = null;
        try {
            connection = connections.open(url);
            connection.setConnectTimeout((int) Math.max(1,
                    Math.min(CONNECT_TIMEOUT_MS, remainingMs)));
            connection.setReadTimeout((int) Math.max(1,
                    Math.min(READ_TIMEOUT_MS, remainingMs)));
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/plain, application/octet-stream;q=0.9, */*;q=0.5");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "AdSkip-Android/1.3.5");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpStatusException(status);

            URL finalUrl = connection.getURL();
            if (finalUrl == null || !"https".equalsIgnoreCase(finalUrl.getProtocol())) {
                throw new IOException("规则源重定向离开 HTTPS");
            }
            if (!isAllowedRedirectHost(source, finalUrl.getHost())) {
                throw new IOException("规则源重定向到未授权镜像主机");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_DOWNLOAD_BYTES) throw new SizeLimitException();

            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
                throw new IOException("服务器返回 HTML 页面而不是规则文件");
            }

            try (InputStream input = new BoundedInputStream(
                    connection.getInputStream(), MAX_DOWNLOAD_BYTES, attemptDeadlineNanos)) {
                return DnsFeedParser.parse(input, source.format);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isAllowedRedirectHost(Source source, String finalHost) {
        if (finalHost == null) return false;
        for (String mirror : source.mirrorUrls) {
            try {
                if (finalHost.equalsIgnoreCase(new URL(mirror).getHost())) return true;
            } catch (Exception ignored) {
                // Source URLs are validated as HTTPS during enum initialization.
            }
        }
        return false;
    }

    private static AttemptFailure classify(IOException failure) {
        if (failure instanceof SizeLimitException) {
            return new AttemptFailure(FailureKind.SIZE_LIMIT, false);
        }
        if (failure instanceof HttpStatusException) {
            int status = ((HttpStatusException) failure).status;
            // Respect 429 rate limits: fail over to another mirror, but do not
            // immediately hammer the throttling host again.
            boolean retryable = status == 408 || status == 425
                    || status == 500 || status == 502 || status == 503 || status == 504;
            return new AttemptFailure(FailureKind.HTTP, retryable);
        }

        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("重定向") || message.contains("https")) {
            return new AttemptFailure(FailureKind.REDIRECT, false);
        }
        if (message.contains("exceeds 12 mib") || message.contains("12 mib")) {
            return new AttemptFailure(FailureKind.SIZE_LIMIT, false);
        }
        if (failure instanceof SocketTimeoutException || failure instanceof SocketException
                || failure instanceof UnknownHostException
                || failure instanceof java.io.EOFException) {
            return new AttemptFailure(FailureKind.CONNECTION, true);
        }
        if (message.contains("only ") && message.contains("domains found")
                || message.contains("validation failed")
                || message.contains("html")
                || message.contains("invalid domain")) {
            return new AttemptFailure(FailureKind.VALIDATION, false);
        }
        // A connection reset may be wrapped by an implementation-specific IOException.
        if (message.contains("connection reset") || message.contains("timed out")
                || message.contains("failed to connect")) {
            return new AttemptFailure(FailureKind.CONNECTION, true);
        }
        return new AttemptFailure(FailureKind.UNKNOWN, false);
    }

    private static void notifyProgress(ProgressListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onProgress(message);
        } catch (RuntimeException ignored) {
            // Progress UI must never break the download.
        }
    }

    private static void addDiagnostic(List<String> diagnostics, String value) {
        if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(value);
    }

    private static String joinDiagnostics(List<String> diagnostics) {
        if (diagnostics.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String item : diagnostics) {
            if (result.length() > 0) result.append("；");
            result.append(item);
        }
        return result.toString();
    }

    private static String compactMessage(IOException error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 140 ? message.substring(0, 140) + "…" : message;
    }

    private static String mirrorHost(String endpoint) {
        try {
            return new URL(endpoint).getHost();
        } catch (Exception ignored) {
            return "未知镜像";
        }
    }

    private static boolean isDeadlineReached(long deadlineNanos) {
        return System.nanoTime() >= deadlineNanos;
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, remaining / 1_000_000L);
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private final long deadlineNanos;
        private long bytesRead;

        BoundedInputStream(InputStream input, long maxBytes, long deadlineNanos) {
            super(input);
            this.maxBytes = maxBytes;
            this.deadlineNanos = deadlineNanos;
        }

        private void checkDeadline() throws IOException {
            if (isDeadlineReached(deadlineNanos)) {
                throw new SocketTimeoutException("DNS feed download exceeded total time budget");
            }
        }

        @Override public int read() throws IOException {
            checkDeadline();
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
                if (bytesRead > maxBytes) throw new SizeLimitException();
            }
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeadline();
            if (length == 0) return 0;
            long remainingWithProbe = maxBytes - bytesRead + 1;
            if (remainingWithProbe <= 0) throw new SizeLimitException();
            int allowed = (int) Math.min((long) length, remainingWithProbe);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) {
                bytesRead += count;
                if (bytesRead > maxBytes) throw new SizeLimitException();
            }
            return count;
        }
    }
}
