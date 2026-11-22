package com.aeginning.adskip.service;

import com.aeginning.adskip.engine.DnsFeedParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** User-triggered HTTPS download of maintained, domain-oriented DNS feeds. */
public final class DnsFeedManager {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 18_000;

    public enum Source {
        HAGEZI_LIGHT(
                "hagezi-light",
                "HaGeZi Multi LIGHT",
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/dnsmasq/light.txt",
                DnsFeedParser.Format.DNSMASQ,
                "约 9.3 万条；轻量、兼容性优先，建议先启用"),
        STEVENBLACK_BASE(
                "stevenblack-base",
                "StevenBlack Unified Hosts",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                DnsFeedParser.Format.HOSTS,
                "约 7.6 万条；广告与恶意域名聚合，覆盖更广，误拦截风险较高");

        public final String id;
        public final String displayName;
        public final String url;
        public final DnsFeedParser.Format format;
        public final String description;

        Source(String id, String displayName, String url,
               DnsFeedParser.Format format, String description) {
            this.id = id;
            this.displayName = displayName;
            this.url = url;
            this.format = format;
            this.description = description;
        }
    }

    public static final class DownloadedFeed {
        public final Source source;
        public final DnsFeedParser.ParsedFeed parsed;

        DownloadedFeed(Source source, DnsFeedParser.ParsedFeed parsed) {
            this.source = source;
            this.parsed = parsed;
        }
    }

    private DnsFeedManager() { }

    public static DownloadedFeed download(Source source) throws IOException {
        if (source == null) throw new IllegalArgumentException("DNS feed source required");
        URL url = new URL(source.url);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Only HTTPS DNS feed sources are permitted");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/plain");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "AdSkip-Android/1.3.0");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("DNS feed server returned HTTP " + status);
            }
            long length = connection.getContentLengthLong();
            if (length > 12L * 1024L * 1024L) {
                throw new IOException("DNS feed download exceeds 12 MiB");
            }
            try (InputStream input = connection.getInputStream()) {
                DnsFeedParser.ParsedFeed parsed = DnsFeedParser.parse(input, source.format);
                return new DownloadedFeed(source, parsed);
            }
        } finally {
            connection.disconnect();
        }
    }
}
