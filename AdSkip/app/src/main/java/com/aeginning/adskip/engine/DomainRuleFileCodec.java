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

    public static final int MAX_DIAGNOSTIC_ISSUES = 100;
    public static final int MAX_DIAGNOSTIC_LINE_CHARS = 240;

    public static final class ParseIssue {
        public final int lineNumber;
        public final String type;
        public final String reason;
        public final String rawLine;

        ParseIssue(int lineNumber, String type, String reason, String rawLine) {
            this.lineNumber = lineNumber;
            this.type = type;
            this.reason = reason;
            this.rawLine = rawLine;
        }
    }

    public static final class ParseResult {
        public final List<String> domains;
        public final int invalidCount;
        public final int duplicateCount;
        public final int ignoredLineCount;
        public final int unsupportedCount;
        /** First MAX_DIAGNOSTIC_ISSUES rejected lines, in source order. */
        public final List<ParseIssue> issues;
        /** True when more rejected lines existed than could be retained for diagnostics. */
        public final boolean issuesTruncated;

        ParseResult(List<String> domains, int invalidCount, int duplicateCount,
                    int ignoredLineCount, int unsupportedCount,
                    List<ParseIssue> issues, boolean issuesTruncated) {
            this.domains = domains;
            this.invalidCount = invalidCount;
            this.duplicateCount = duplicateCount;
            this.ignoredLineCount = ignoredLineCount;
            this.unsupportedCount = unsupportedCount;
            this.issues = issues;
            this.issuesTruncated = issuesTruncated;
        }
    }

    public static ParseResult parse(String content) {
        Set<String> domains = new LinkedHashSet<>();
        List<ParseIssue> issues = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return result(domains, 0, 0, 0, 0, issues, false);
        }
        if (content.length() > MAX_IMPORT_CHARS) {
            addIssue(issues, 1, "INVALID", "导入内容超过字符容量上限", content.substring(0,
                    Math.min(content.length(), MAX_DIAGNOSTIC_LINE_CHARS)));
            return result(domains, 1, 0, 0, 0, issues, false);
        }

        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length > MAX_INPUT_LINES) {
            addIssue(issues, 1, "INVALID", "文件行数超过 " + MAX_INPUT_LINES + " 行上限", "");
            return result(domains, 1, 0, 0, 0, issues, false);
        }

        int invalid = 0;
        int duplicates = 0;
        int ignored = 0;
        int unsupported = 0;
        boolean issuesTruncated = false;
        for (int index = 0; index < lines.length; index++) {
            String original = lines[index];
            int lineNumber = index + 1;
            if (original != null && original.length() > MAX_LINE_CHARS) {
                invalid++;
                issuesTruncated |= !addIssue(issues, lineNumber, "INVALID",
                        "单行长度超过 " + MAX_LINE_CHARS + " 字符上限", original);
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
                unsupported++;
                issuesTruncated |= !addIssue(issues, lineNumber, "UNSUPPORTED",
                        "例外/允许规则无法转换为 DNS 拦截规则", original);
                continue;
            }

            List<String> candidates = extractCandidates(line);
            if (candidates.isEmpty()) {
                if (looksLikeConditionalOrCosmeticRule(line)) {
                    unsupported++;
                    issuesTruncated |= !addIssue(issues, lineNumber, "UNSUPPORTED",
                            "条件规则或带过滤条件的规则无法安全转换为 DNS 规则", original);
                } else {
                    invalid++;
                    issuesTruncated |= !addIssue(issues, lineNumber, "INVALID",
                            "未能从该行提取有效域名", original);
                }
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
                    if (lineInvalid) {
                        invalid++;
                        issuesTruncated |= !addIssue(issues, lineNumber, "INVALID",
                                "该行包含无效域名", original);
                    }
                    return result(domains, invalid, duplicates, ignored, unsupported,
                            issues, issuesTruncated);
                }
            }
            if (lineInvalid) {
                invalid++;
                issuesTruncated |= !addIssue(issues, lineNumber, "INVALID",
                        "该行包含无效域名或域名格式不合法", original);
            }
        }
        return result(domains, invalid, duplicates, ignored, unsupported,
                issues, issuesTruncated);
    }

    private static boolean addIssue(List<ParseIssue> issues, int lineNumber,
                                    String type, String reason, String rawLine) {
        if (issues.size() >= MAX_DIAGNOSTIC_ISSUES) return false;
        String safeLine = rawLine == null ? "" : rawLine.replace('\t', ' ');
        if (safeLine.length() > MAX_DIAGNOSTIC_LINE_CHARS) {
            safeLine = safeLine.substring(0, MAX_DIAGNOSTIC_LINE_CHARS) + "…";
        }
        issues.add(new ParseIssue(lineNumber, type, reason, safeLine));
        return true;
    }

    private static ParseResult result(Set<String> domains, int invalid, int duplicates,
                                      int ignored, int unsupported,
                                      List<ParseIssue> issues, boolean issuesTruncated) {
        return new ParseResult(new ArrayList<>(domains), invalid, duplicates, ignored,
                unsupported, new ArrayList<>(issues), issuesTruncated);
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
