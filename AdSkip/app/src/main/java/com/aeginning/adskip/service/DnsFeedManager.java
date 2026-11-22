package com.aeginning.adskip.service;

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
    private static final int CONNECT_TIMEOUT_MS = 9_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long MAX_DOWNLOAD_BYTES = 12L * 1024L * 1024L;
    private static final long MAX_TOTAL_TIME_MS = 90_000L;
    private static final int MAX_EXTRA_RETRIES = 2;
    private static final long[] RETRY_BACKOFF_MS = {500L, 1_200L};
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
        final IOException cause;
        final boolean retryable;

        AttemptFailure(FailureKind kind, IOException cause, boolean retryable) {
            this.kind = kind;
            this.cause = cause;
            this.retryable = retryable;
        }
    }

    private DnsFeedManager() { }

    public static DownloadedFeed download(Source source) throws IOException {
        return download(source, null);
    }

    public static DownloadedFeed download(Source source, ProgressListener listener) throws IOException {
        return download(source,
                url -> (HttpURLConnection) url.openConnection(),
                Thread::sleep,
                listener);
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

        long remainingMs = remainingMillis(deadlineNanos);
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
            connection.setRequestProperty("User-Agent", "AdSkip-Android/1.3.3");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpStatusException(status);

            URL finalUrl = connection.getURL();
            if (finalUrl == null || !"https".equalsIgnoreCase(finalUrl.getProtocol())) {
                throw new IOException("规则源重定向离开 HTTPS");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_DOWNLOAD_BYTES) throw new SizeLimitException();

            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
                throw new IOException("服务器返回 HTML 页面而不是规则文件");
            }

            try (InputStream input = new BoundedInputStream(connection.getInputStream(), MAX_DOWNLOAD_BYTES)) {
                return DnsFeedParser.parse(input, source.format);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static AttemptFailure classify(IOException failure) {
        if (failure instanceof SizeLimitException) {
            return new AttemptFailure(FailureKind.SIZE_LIMIT, failure, false);
        }
        if (failure instanceof HttpStatusException) {
            int status = ((HttpStatusException) failure).status;
            boolean retryable = status == 408 || status == 425 || status == 429
                    || status == 500 || status == 502 || status == 503 || status == 504;
            return new AttemptFailure(FailureKind.HTTP, failure, retryable);
        }

        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("重定向") || message.contains("https")) {
            return new AttemptFailure(FailureKind.REDIRECT, failure, false);
        }
        if (message.contains("exceeds 12 mib") || message.contains("12 mib")) {
            return new AttemptFailure(FailureKind.SIZE_LIMIT, failure, false);
        }
        if (failure instanceof SocketTimeoutException || failure instanceof SocketException
                || failure instanceof UnknownHostException
                || failure instanceof java.io.EOFException) {
            return new AttemptFailure(FailureKind.CONNECTION, failure, true);
        }
        if (message.contains("only ") && message.contains("domains found")
                || message.contains("validation failed")
                || message.contains("html")
                || message.contains("invalid domain")) {
            return new AttemptFailure(FailureKind.VALIDATION, failure, false);
        }
        // A connection reset may be wrapped by an implementation-specific IOException.
        if (message.contains("connection reset") || message.contains("timed out")
                || message.contains("failed to connect")) {
            return new AttemptFailure(FailureKind.CONNECTION, failure, true);
        }
        return new AttemptFailure(FailureKind.UNKNOWN, failure, false);
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
        private long bytesRead;

        BoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
                if (bytesRead > maxBytes) throw new SizeLimitException();
            }
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
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
