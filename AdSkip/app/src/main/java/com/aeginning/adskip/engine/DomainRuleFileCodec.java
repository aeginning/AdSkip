package com.aeginning.adskip.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser/exporter for portable DNS block domain lists.
 *
 * Accepted input: one hostname per line, hosts-file rows, and common Adblock
 * Network rules such as ||ads.example.com^ or ||ads.example.com^$third-party.
 * URLs, IP addresses, allowlist rules, and arbitrary filter syntax are rejected.
 */
public final class DomainRuleFileCodec {
    public static final int MAX_IMPORT_CHARS = 5 * 1024 * 1024;
    public static final int MAX_INPUT_LINES = 100_000;

    private DomainRuleFileCodec() { }

    public static final class ParseResult {
        public final List<String> domains;
        public final int invalidCount;
        public final int duplicateCount;
        public final int ignoredLineCount;

        ParseResult(List<String> domains, int invalidCount, int duplicateCount,
                    int ignoredLineCount) {
            this.domains = domains;
            this.invalidCount = invalidCount;
            this.duplicateCount = duplicateCount;
            this.ignoredLineCount = ignoredLineCount;
        }
    }

    public static ParseResult parse(String content) {
        Set<String> domains = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) {
            return new ParseResult(new ArrayList<>(), 0, 0, 0);
        }
        if (content.length() > MAX_IMPORT_CHARS) {
            return new ParseResult(new ArrayList<>(), 1, 0, 0);
        }

        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length > MAX_INPUT_LINES) {
            return new ParseResult(new ArrayList<>(), 1, 0, 0);
        }

        int invalid = 0;
        int duplicates = 0;
        int ignored = 0;
        for (String original : lines) {
            String line = original == null ? "" : original.trim();
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                ignored++;
                continue;
            }

            int inlineComment = line.indexOf('#');
            if (inlineComment >= 0) line = line.substring(0, inlineComment).trim();
            if (line.isEmpty()) {
                ignored++;
                continue;
            }

            List<String> candidates = extractCandidates(line);
            if (candidates.isEmpty()) {
                invalid++;
                continue;
            }
            boolean lineInvalid = false;
            for (String candidate : candidates) {
                String normalized = DomainRuleValidator.normalize(candidate);
                if (normalized.isEmpty()) {
                    lineInvalid = true;
                } else if (!domains.add(normalized)) {
                    duplicates++;
                }
            }
            if (lineInvalid) invalid++;
            if (domains.size() > 5_000) {
                return new ParseResult(new ArrayList<>(domains), invalid + 1,
                        duplicates, ignored);
            }
        }
        return new ParseResult(new ArrayList<>(domains), invalid, duplicates, ignored);
    }

    private static List<String> extractCandidates(String line) {
        List<String> result = new ArrayList<>();
        if (line.startsWith("@@") || line.startsWith("[")) return result;

        // Adblock Plus / uBlock network hostname rule. Reject exception rules and
        // non-host filter syntax rather than trying to approximate their semantics.
        if (line.startsWith("||")) {
            int end = line.indexOf('^', 2);
            if (end < 0) return result;
            String suffix = line.substring(end + 1);
            // Options such as $third-party or $domain=... change rule scope.
            // Reject them instead of silently broadening a conditional filter into
            // a global DNS block.
            if (!suffix.isEmpty()) return result;
            String host = line.substring(2, end);
            if (!host.isEmpty()) result.add(host);
            return result;
        }

        String[] tokens = line.split("[\\s,;]+");
        if (tokens.length == 0) return result;
        if (isHostsAddress(tokens[0])) {
            for (int i = 1; i < tokens.length; i++) {
                if (!tokens[i].isEmpty()) result.add(tokens[i]);
            }
            return result;
        }
        if (tokens.length == 1) {
            result.add(tokens[0]);
            return result;
        }
        // A plain whitespace-separated list is accepted only when every token
        // is a hostname; hosts-file IP mappings are handled above.
        for (String token : tokens) if (!token.isEmpty()) result.add(token);
        return result;
    }

    private static boolean isHostsAddress(String value) {
        if (value == null) return false;
        return value.matches("(?i)(?:0\\.0\\.0\\.0|127(?:\\.\\d{1,3}){3}|::1|::|0:0:0:0:0:0:0:1)");
    }

    public static String exportPlainText(List<String> domains) {
        StringBuilder output = new StringBuilder();
        output.append("# AdSkip DNS domain rules\n")
                .append("# Export contains enabled manual rules only; DNS subscriptions are managed separately.\n");
        if (domains != null) {
            for (String domain : domains) {
                String normalized = DomainRuleValidator.normalize(domain);
                if (!normalized.isEmpty()) output.append(normalized).append('\n');
            }
        }
        return output.toString();
    }
}
