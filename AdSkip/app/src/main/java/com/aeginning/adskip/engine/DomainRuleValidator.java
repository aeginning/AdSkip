package com.aeginning.adskip.engine;

import java.net.IDN;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict normalization for user-supplied DNS block rules.
 * Rules must be fully qualified hostnames, never URL fragments, IP literals or bare TLDs.
 */
public final class DomainRuleValidator {
    private static final Set<String> COMMON_PUBLIC_SUFFIXES = new HashSet<>(Arrays.asList(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "sch.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au", "asn.au", "id.au",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "ed.jp",
            "co.kr", "ne.kr", "or.kr", "re.kr", "pe.kr", "go.kr",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "com.sg", "net.sg", "org.sg", "gov.sg", "edu.sg",
            "com.hk", "net.hk", "org.hk", "edu.hk", "gov.hk", "idv.hk",
            "com.tw", "net.tw", "org.tw", "edu.tw", "gov.tw",
            "com.br", "net.br", "org.br", "gov.br", "edu.br",
            "com.mx", "org.mx", "gob.mx", "edu.mx",
            "com.tr", "net.tr", "org.tr", "gov.tr", "edu.tr",
            "com.my", "net.my", "org.my", "gov.my", "edu.my",
            "com.ph", "net.ph", "org.ph", "gov.ph", "edu.ph",
            "co.in", "firm.in", "net.in", "org.in", "gen.in", "ind.in",
            "co.za", "org.za", "net.za", "gov.za", "ac.za",
            "com.ar", "net.ar", "org.ar", "gov.ar", "edu.ar",
            "com.pl", "net.pl", "org.pl", "gov.pl", "edu.pl",
            "com.ua", "net.ua", "org.ua", "gov.ua", "edu.ua",
            "co.id", "or.id", "web.id", "ac.id", "go.id",
            "co.nz", "org.nz", "net.nz", "ac.nz", "govt.nz"
    ));

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
        // A public suffix is not a host rule: blocking it would disable entire TLD branches.
        if (COMMON_PUBLIC_SUFFIXES.contains(ascii)) return "";
        return ascii;
    }
}
