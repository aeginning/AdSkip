package com.aeginning.adskip.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser/exporter for DNS blocklists from common formats:
 * plain domain lists, hosts, Adblock/AdGuard hostname rules and dnsmasq rules.
 *
 * Context-dependent allow/exception rules are intentionally ignored because a DNS
 * blocklist cannot preserve their page/party/type scope without changing semantics.
 */
public final class DomainRuleFileCodec {
    public static final int MAX_IMPORT_CHARS = 20 * 1024 * 1024;
    public static final int MAX_INPUT_LINES = 500_000;
    public static final int MAX_LINE_CHARS = 16_384;
    public static final int MAX_PARSED_DOMAINS = 100_000;

    private DomainRuleFileCodec() { }

    public static final class ParseResult {
        public final List<String> domains;
        public final int invalidCount;
        public final int duplicateCount;
        public final int ignoredLineCount;
        public final int unsupportedCount;

        ParseResult(List<String> domains, int invalidCount, int duplicateCount,
                    int ignoredLineCount, int unsupportedCount) {
            this.domains = domains;
            this.invalidCount = invalidCount;
            this.duplicateCount = duplicateCount;
            this.ignoredLineCount = ignoredLineCount;
            this.unsupportedCount = unsupportedCount;
        }
    }

    public static ParseResult parse(String content) {
        Set<String> domains = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) {
            return result(domains, 0, 0, 0, 0);
        }
        if (content.length() > MAX_IMPORT_CHARS) {
            return result(domains, 1, 0, 0, 0);
        }

        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length > MAX_INPUT_LINES) {
            return result(domains, 1, 0, 0, 0);
        }

        int invalid = 0;
        int duplicates = 0;
        int ignored = 0;
        int unsupported = 0;
        for (String original : lines) {
            if (original != null && original.length() > MAX_LINE_CHARS) {
                invalid++;
                continue;
            }
            String line = original == null ? "" : original.trim();
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")
                    || line.startsWith(";") || line.startsWith("//")) {
                ignored++;
                continue;
            }

            // Hosts-file inline comments. Do not strip '#' from Adblock cosmetic syntax.
            if (!line.startsWith("||") && !line.startsWith("@@")) {
                int inlineComment = line.indexOf(" #");
                if (inlineComment >= 0) line = line.substring(0, inlineComment).trim();
            }
            if (line.isEmpty()) {
                ignored++;
                continue;
            }

            if (isNonDnsFilter(line)) {
                ignored++;
                continue;
            }
            if (line.startsWith("@@")) {
                // Exception/allow rules cannot be represented by a DNS block entry.
                unsupported++;
                continue;
            }

            List<String> candidates = extractCandidates(line);
            if (candidates.isEmpty()) {
                if (looksLikeConditionalOrCosmeticRule(line)) unsupported++;
                else invalid++;
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
                if (domains.size() >= MAX_PARSED_DOMAINS) {
                    return result(domains, invalid + (lineInvalid ? 1 : 0),
                            duplicates, ignored, unsupported);
                }
            }
            if (lineInvalid) invalid++;
        }
        return result(domains, invalid, duplicates, ignored, unsupported);
    }

    private static ParseResult result(Set<String> domains, int invalid, int duplicates,
                                      int ignored, int unsupported) {
        return new ParseResult(new ArrayList<>(domains), invalid, duplicates, ignored, unsupported);
    }

    private static boolean isNonDnsFilter(String line) {
        // Cosmetic rules, scriptlet rules and procedural selectors are not DNS host rules.
        return line.contains("##") || line.contains("#@#") || line.contains("#?#")
                || line.startsWith("[$") || line.startsWith("[Adblock");
    }

    private static boolean looksLikeConditionalOrCosmeticRule(String line) {
        return line.startsWith("||") || line.startsWith("@@") || line.contains("$")
                || line.contains("##") || line.contains("#@#") || line.contains("#?#");
    }

    private static List<String> extractCandidates(String line) {
        List<String> result = new ArrayList<>();

        // Adblock Plus / uBlock / AdGuard network rules. Only unconditional hostname
        // rules (or the semantics-preserving $important modifier) can map to DNS.
        if (line.startsWith("||")) {
            int end = line.indexOf('^', 2);
            if (end < 0) return result;
            String suffix = line.substring(end + 1);
            if (!suffix.isEmpty() && !"$important".equalsIgnoreCase(suffix)) return result;
            String host = line.substring(2, end);
            if (!host.isEmpty()) result.add(stripWildcard(host));
            return result;
        }

        // dnsmasq: address=/example.com/0.0.0.0, server=/example.com/,
        // local=/example.com/. Only the hostname segment is imported.
        if (line.startsWith("address=/") || line.startsWith("server=/")
                || line.startsWith("local=/")) {
            int first = line.indexOf('/');
            int second = line.indexOf('/', first + 1);
            int third = line.indexOf('/', second + 1);
            if (second > first && (third > second || line.startsWith("address=/") || line.startsWith("server=/") || line.startsWith("local=/"))) {
                String host = line.substring(first + 1, second).trim();
                if (!host.isEmpty()) result.add(stripWildcard(host));
            }
            return result;
        }

        String[] tokens = line.split("[\\s,;]+");
        if (tokens.length == 0) return result;
        if (isHostsAddress(tokens[0])) {
            for (int i = 1; i < tokens.length; i++) {
                if (!tokens[i].isEmpty()) result.add(stripWildcard(tokens[i]));
            }
            return result;
        }
        if (tokens.length == 1) {
            result.add(stripWildcard(tokens[0]));
            return result;
        }
        // Also accept whitespace-separated domain exports from common blocklist tools.
        for (String token : tokens) {
            if (!token.isEmpty()) result.add(stripWildcard(token));
        }
        return result;
    }

    private static String stripWildcard(String value) {
        String host = value == null ? "" : value.trim();
        if (host.startsWith("*.")) return host.substring(2);
        return host;
    }

    private static boolean isHostsAddress(String value) {
        if (value == null) return false;
        if ("::".equals(value) || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(value)) return true;
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            try {
                int number = Integer.parseInt(octet);
                if (number < 0 || number > 255) return false;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        return true;
    }

    public static String exportPlainText(List<String> domains) {
        StringBuilder output = new StringBuilder();
        output.append("# AdSkip DNS domain rules\n")
                .append("# One hostname per line; enabled manual and subscription rules.\n");
        if (domains != null) {
            Set<String> unique = new LinkedHashSet<>();
            for (String domain : domains) {
                String normalized = DomainRuleValidator.normalize(domain);
                if (!normalized.isEmpty()) unique.add(normalized);
            }
            for (String domain : unique) output.append(domain).append('\n');
        }
        return output.toString();
    }
}
