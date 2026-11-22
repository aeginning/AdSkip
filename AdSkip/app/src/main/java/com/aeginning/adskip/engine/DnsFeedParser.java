package com.aeginning.adskip.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict, memory-bounded parser for DNS-only domain feeds.
 *
 * Supported formats:
 *  - dnsmasq: local=/example.com/ and server=/example.com/...
 *  - hosts: 0.0.0.0 example.com / 127.0.0.1 example.com
 *
 * Adblock exception rules, regexes, IP rules and arbitrary directives are never
 * converted into block rules. This avoids turning allowlists into blocklists.
 */
public final class DnsFeedParser {
    public enum Format { DNSMASQ, HOSTS }

    public static final int MAX_DOMAINS = 120_000;
    private static final int MIN_DOMAINS = 1_000;
    private static final int MAX_TEXT_CHARS = 12 * 1024 * 1024;

    private DnsFeedParser() { }

    public static final class ParsedFeed {
        public final List<String> domains;
        public final String version;
        public final int sourceLines;

        ParsedFeed(List<String> domains, String version, int sourceLines) {
            this.domains = Collections.unmodifiableList(domains);
            this.version = version;
            this.sourceLines = sourceLines;
        }
    }

    public static ParsedFeed parse(InputStream input, Format format) throws IOException {
        if (input == null || format == null) throw new IllegalArgumentException("input/format required");
        Set<String> domains = new LinkedHashSet<>();
        String version = "";
        int sourceLines = 0;
        int charsRead = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 32 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                sourceLines++;
                charsRead += line.length() + 1;
                if (charsRead > MAX_TEXT_CHARS) throw new IOException("DNS feed exceeds 12 MiB text limit");

                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("#") || trimmed.startsWith("!") || trimmed.startsWith("[")) {
                    if (version.isEmpty() && trimmed.regionMatches(true, 0, "# Version:", 0, 10)) {
                        version = trimmed.substring(10).trim();
                    } else if (version.isEmpty() && trimmed.regionMatches(true, 0, "# Date:", 0, 7)) {
                        version = trimmed.substring(7).trim();
                    }
                    continue;
                }

                if (format == Format.DNSMASQ) {
                    addDnsmasqLine(trimmed, domains);
                } else {
                    addHostsLine(trimmed, domains);
                }

                if (domains.size() > MAX_DOMAINS) {
                    throw new IOException("DNS feed exceeds " + MAX_DOMAINS + " unique domains");
                }
            }
        }

        if (domains.size() < MIN_DOMAINS) {
            throw new IOException("DNS feed validation failed: only " + domains.size()
                    + " domains found; expected at least " + MIN_DOMAINS);
        }
        List<String> sorted = new ArrayList<>(domains);
        Collections.sort(sorted);
        return new ParsedFeed(sorted, version, sourceLines);
    }

    private static void addDnsmasqLine(String line, Set<String> output) {
        // Only import explicit domain-scoped records. Ignore #, IP and catch-all rules.
        int marker = line.indexOf("=/");
        if (marker < 0) return;
        String value = line.substring(marker + 2);
        int end = value.indexOf('/');
        if (end <= 0) return;
        String domain = value.substring(0, end).trim();
        addDomain(domain, output);
    }

    private static void addHostsLine(String line, Set<String> output) {
        int comment = line.indexOf('#');
        if (comment >= 0) line = line.substring(0, comment).trim();
        if (line.isEmpty()) return;

        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            addDomain(parts[0], output);
            return;
        }

        // A hosts record begins with an address. Never treat the address as a hostname.
        if (looksLikeAddress(parts[0])) {
            for (int i = 1; i < parts.length; i++) addDomain(parts[i], output);
        } else {
            // Also tolerate plain one-domain-per-line data in hosts feeds.
            addDomain(parts[0], output);
        }
    }

    private static boolean looksLikeAddress(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.indexOf(':') >= 0
                || v.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    }

    private static void addDomain(String raw, Set<String> output) {
        String domain = DomainRuleValidator.normalize(raw);
        if (domain.isEmpty()) return;
        // Avoid local host aliases and reverse-DNS zones even if a source includes them.
        if ("localhost.localdomain".equals(domain)
                || domain.endsWith(".localhost")
                || domain.endsWith(".local")
                || domain.endsWith(".in-addr.arpa")
                || domain.endsWith(".ip6.arpa")) return;
        output.add(domain);
    }
}
