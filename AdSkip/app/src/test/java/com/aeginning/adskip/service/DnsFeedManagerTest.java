package com.aeginning.adskip.service;

import com.aeginning.adskip.engine.DnsFeedParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.*;

public class DnsFeedManagerTest {

    @Test public void sourcesExposeOnlyHttpsMirrors() {
        for (DnsFeedManager.Source source : DnsFeedManager.Source.values()) {
            assertFalse(source.mirrorUrls.isEmpty());
            for (String mirror : source.mirrorUrls) {
                assertTrue("Expected HTTPS: " + mirror, mirror.startsWith("https://"));
            }
            assertEquals(source.url, source.mirrorUrls.get(0));
        }
        assertTrue(DnsFeedManager.Source.HAGEZI_LIGHT.mirrorUrls.size() >= 4);
        assertTrue(DnsFeedManager.Source.STEVENBLACK_BASE.mirrorUrls.size() >= 3);
        assertTrue(DnsFeedManager.Source.ADAWAY_HOSTS.mirrorUrls.size() >= 3);
        assertTrue(DnsFeedManager.Source.ONEHOSTS_LITE.mirrorUrls.size() >= 3);
        assertEquals(1, DnsFeedManager.Source.DAN_POLLOCK_HOSTS.mirrorUrls.size());
    }

    @Test public void newIndependentHostsFeedsParseWithoutChangingExistingFormats() throws Exception {
        for (DnsFeedManager.Source source : Arrays.asList(
                DnsFeedManager.Source.ADAWAY_HOSTS,
                DnsFeedManager.Source.ONEHOSTS_LITE,
                DnsFeedManager.Source.DAN_POLLOCK_HOSTS)) {
            DnsFeedManager.DownloadedFeed result = DnsFeedManager.download(source,
                    url -> new FakeConnection(url, 200, validHosts(), null, -1),
                    millis -> { }, null);
            assertEquals(source, result.source);
            assertEquals(DnsFeedParser.Format.HOSTS, result.source.format);
            assertEquals(1_100, result.parsed.domains.size());
            assertEquals(source.mirrorUrls.get(0), result.downloadedFrom);
        }
    }

    @Test public void switchesMirrorImmediatelyAfterConnectionReset() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.HAGEZI_LIGHT;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(0)), 200, null,
                new SocketException("Connection reset"), -1));
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(1)), 200,
                validDnsmasq(), null, -1));

        List<String> requested = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        DnsFeedManager.DownloadedFeed result = DnsFeedManager.download(source,
                url -> {
                    requested.add(url.toString());
                    return replies.removeFirst();
                },
                waits::add,
                null);

        assertEquals(2, result.attempts);
        assertEquals(source.mirrorUrls.get(1), result.downloadedFrom);
        assertEquals(1_100, result.parsed.domains.size());
        assertEquals(2, requested.size());
        assertTrue("Successful failover should not wait for a same-mirror retry", waits.isEmpty());
    }

    @Test public void retriesTransientFailureAfterTryingAllMirrors() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.HAGEZI_LIGHT;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        for (String mirror : source.mirrorUrls) {
            replies.add(new FakeConnection(new URL(mirror), 200, null,
                    new SocketException("Connection reset"), -1));
        }
        // The retry budget is used only after the first pass has visited each mirror.
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(0)), 200,
                validDnsmasq(), null, -1));

        List<Long> waits = new ArrayList<>();
        DnsFeedManager.DownloadedFeed result = DnsFeedManager.download(source,
                url -> replies.removeFirst(),
                waits::add,
                null);

        assertEquals(source.mirrorUrls.size() + 1, result.attempts);
        assertEquals(source.mirrorUrls.get(0), result.downloadedFrom);
        assertEquals(Arrays.asList(1_000L), waits);
    }

    @Test public void rejectsHtmlAndContinuesToAnotherMirror() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.STEVENBLACK_BASE;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(0)), 200,
                "<html><body>temporary edge error</body></html>".getBytes(StandardCharsets.UTF_8),
                null, -1, "text/html"));
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(1)), 200,
                validHosts(), null, -1));

        DnsFeedManager.DownloadedFeed result = DnsFeedManager.download(source,
                url -> replies.removeFirst(), millis -> { }, null);

        assertEquals(source.mirrorUrls.get(1), result.downloadedFrom);
        assertEquals(1_100, result.parsed.domains.size());
    }

    @Test public void rejectsUntrustedHttpsRedirectAndFallsBack() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.HAGEZI_LIGHT;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(0)),
                new URL("https://unexpected.example/list.txt"), 200,
                validDnsmasq(), null, -1));
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(1)), 200,
                validDnsmasq(), null, -1));

        DnsFeedManager.DownloadedFeed result = DnsFeedManager.download(source,
                url -> replies.removeFirst(), millis -> { }, null);

        assertEquals(source.mirrorUrls.get(1), result.downloadedFrom);
    }

    @Test public void rejectsOversizedResponseBeforeParsingAndFallsBack() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.STEVENBLACK_BASE;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(0)), 200,
                new byte[0], null, 12L * 1024L * 1024L + 1L));
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(1)), 200,
                validHosts(), null, -1));

        DnsFeedManager.DownloadedFeed result = DnsFeedManager.download(source,
                url -> replies.removeFirst(), millis -> { }, null);

        assertEquals(source.mirrorUrls.get(1), result.downloadedFrom);
    }

    @Test public void doesNotRetryPermanentHttpErrorsOnSameMirror() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.STEVENBLACK_BASE;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(0)), 404,
                new byte[0], null, 0));
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(1)), 200,
                validHosts(), null, -1));

        List<Long> waits = new ArrayList<>();
        DnsFeedManager.DownloadedFeed result = DnsFeedManager.download(source,
                url -> replies.removeFirst(), waits::add, null);

        assertEquals(source.mirrorUrls.get(1), result.downloadedFrom);
        assertTrue(waits.isEmpty());
    }

    @Test public void doesNotRetryRateLimitedMirrorAndUsesFailoverOnly() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.HAGEZI_LIGHT;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        for (String mirror : source.mirrorUrls) {
            replies.add(new FakeConnection(new URL(mirror), 429, new byte[0], null, 0));
        }
        List<Long> waits = new ArrayList<>();

        try {
            DnsFeedManager.download(source, url -> replies.removeFirst(), waits::add, null);
            fail("Expected rate-limit failure");
        } catch (DnsFeedManager.DownloadException expected) {
            assertEquals(DnsFeedManager.FailureKind.HTTP, expected.kind);
            assertEquals(source.mirrorUrls.size(), expected.attempts);
            assertTrue("HTTP 429 should switch mirrors without re-hitting a throttled host",
                    waits.isEmpty());
        }
    }

    @Test public void reportsUsefulFailureAfterAllMirrorsFail() throws Exception {
        DnsFeedManager.Source source = DnsFeedManager.Source.STEVENBLACK_BASE;
        Deque<HttpURLConnection> replies = new ArrayDeque<>();
        for (String mirror : source.mirrorUrls) {
            replies.add(new FakeConnection(new URL(mirror), 503, new byte[0], null, 0));
        }
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(0)), 503, new byte[0], null, 0));
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(1)), 503, new byte[0], null, 0));
        replies.add(new FakeConnection(new URL(source.mirrorUrls.get(2)), 503, new byte[0], null, 0));
        List<Long> waits = new ArrayList<>();

        try {
            DnsFeedManager.download(source, url -> replies.removeFirst(), waits::add, null);
            fail("Expected DownloadException");
        } catch (DnsFeedManager.DownloadException expected) {
            assertEquals(DnsFeedManager.FailureKind.HTTP, expected.kind);
            assertEquals(source.mirrorUrls.size() + 3, expected.attempts);
            assertEquals(Arrays.asList(1_000L, 2_500L, 5_000L), waits);
            assertTrue(expected.toUserMessage().contains("HTTP"));
            assertTrue(expected.toUserMessage().contains("尝试次数"));
        }
    }

    private static byte[] validDnsmasq() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1_100; i++) {
            text.append("local=/ad").append(i).append(".example.test/\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] validHosts() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1_100; i++) {
            text.append("0.0.0.0 host").append(i).append(".example.test\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        private final IOException failure;
        private final long contentLength;
        private final String contentType;

        FakeConnection(URL url, int status, byte[] body, IOException failure, long declaredLength) {
            this(url, url, status, body, failure, declaredLength, "text/plain");
        }

        FakeConnection(URL url, int status, byte[] body, IOException failure,
                       long declaredLength, String contentType) {
            this(url, url, status, body, failure, declaredLength, contentType);
        }

        FakeConnection(URL requestUrl, URL resolvedUrl, int status, byte[] body,
                       IOException failure, long declaredLength) {
            this(requestUrl, resolvedUrl, status, body, failure, declaredLength, "text/plain");
        }

        FakeConnection(URL requestUrl, URL resolvedUrl, int status, byte[] body,
                       IOException failure, long declaredLength, String contentType) {
            super(requestUrl);
            this.url = resolvedUrl;
            this.status = status;
            this.body = body == null ? new byte[0] : body;
            this.failure = failure;
            this.contentLength = declaredLength >= 0 ? declaredLength : this.body.length;
            this.contentType = contentType;
        }

        @Override public int getResponseCode() throws IOException {
            if (failure != null) throw failure;
            return status;
        }

        @Override public java.io.InputStream getInputStream() throws IOException {
            if (failure != null) throw failure;
            return new ByteArrayInputStream(body);
        }

        @Override public long getContentLengthLong() { return contentLength; }

        @Override public String getContentType() { return contentType; }

        @Override public void disconnect() { }

        @Override public boolean usingProxy() { return false; }

        @Override public void connect() { }
    }
}
