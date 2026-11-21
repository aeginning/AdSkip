package com.aeginning.adskip.engine;

import java.net.IDN;
import java.util.Locale;

/**
 * Strict normalization for user-supplied DNS block rules.
 * Rules must be fully qualified hostnames, never URL fragments, IP literals or bare TLDs.
 */
public final class DomainRuleValidator {
    private DomainRuleValidator() { }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";

        // Accept common hosts-file notation, but never silently turn a URL into a hostname.
        if (value.startsWith("*.")) {
            value = value.substring(2);
        } else if (value.startsWith(".")) {
            value = value.substring(1);
        }
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty() || value.length() > 253
                || value.contains("://") || value.indexOf('/') >= 0
                || value.indexOf(':') >= 0 || value.indexOf('*') >= 0
                || value.matches("[0-9]+(?:\\.[0-9]+){3}")) {
            return "";
        }

        final String ascii;
        try {
            ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalidIdn) {
            return "";
        }
        if (ascii.length() > 253) return "";
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < 2) return ""; // Prevent catastrophic rules such as "com" or "net".
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return "";
        }
        String tld = labels[labels.length - 1];
        if (tld.length() < 2 || !tld.matches("(?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})")) return "";
        return ascii;
    }
}
