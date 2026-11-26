package com.aeginning.adskip.engine;

import org.junit.Test;

import static org.junit.Assert.*;

public class DomainRuleFileCodecTest {
    @Test public void parsesPlainDomainListAndNormalizesCase() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "Ads.Example.com\ntracker.example.net\n");
        assertEquals(2, result.domains.size());
        assertEquals("ads.example.com", result.domains.get(0));
        assertEquals("tracker.example.net", result.domains.get(1));
        assertEquals(0, result.invalidCount);
    }

    @Test public void parsesHostsFileRowsAndIgnoresComments() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "# block list\n127.0.0.1 ads.example.com tracker.example.net # local sink\n"
                        + "0.0.0.0 metrics.example.org\n"
                        + "0.0.0.1 telemetry.example.org\n");
        assertTrue(result.domains.contains("ads.example.com"));
        assertTrue(result.domains.contains("tracker.example.net"));
        assertTrue(result.domains.contains("metrics.example.org"));
        assertTrue(result.domains.contains("telemetry.example.org"));
        assertEquals(0, result.invalidCount);
        assertTrue(result.ignoredLineCount >= 1);
    }

    @Test public void parsesSimpleAdblockDomainRules() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "||ads.example.com^\n||tracker.example.net^\n");
        assertEquals(2, result.domains.size());
        assertTrue(result.domains.contains("ads.example.com"));
        assertTrue(result.domains.contains("tracker.example.net"));
    }

    @Test public void rejectsConditionalAdblockRulesRatherThanBroadeningThem() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "||ads.example.com^$third-party\n");
        assertTrue(result.domains.isEmpty());
        assertEquals(1, result.invalidCount);
    }

    @Test public void deduplicatesAcrossInputFormats() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "ads.example.com\n0.0.0.0 ads.example.com\n||ads.example.com^\n");
        assertEquals(1, result.domains.size());
        assertEquals(2, result.duplicateCount);
    }

    @Test public void invalidUrlsIpsAndAllowlistRulesAreNotImported() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "https://ads.example.com/path\n192.0.2.10\n@@||allow.example.com^\n");
        assertTrue(result.domains.isEmpty());
        assertEquals(3, result.invalidCount);
    }

    @Test public void exportsUtf8CompatiblePlainDomainListWithScopeHeader() {
        String output = DomainRuleFileCodec.exportPlainText(
                java.util.Arrays.asList("Ads.Example.com", "bad", "tracker.example.net"));
        assertTrue(output.startsWith("# AdSkip DNS domain rules\n"));
        assertTrue(output.contains("# Export contains enabled manual rules only"));
        assertTrue(output.contains("\nads.example.com\n"));
        assertTrue(output.contains("tracker.example.net\n"));
        assertFalse(output.contains("\nbad\n"));
    }

    @Test public void oversizedSingleLineIsRejectedWithoutTokenizingIt() {
        String oversizedLine = "a".repeat(DomainRuleFileCodec.MAX_LINE_CHARS + 1);
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(oversizedLine);
        assertTrue(result.domains.isEmpty());
        assertEquals(1, result.invalidCount);
    }

    @Test public void oversizedInputIsRejected() {
        String oversized = "a".repeat(DomainRuleFileCodec.MAX_IMPORT_CHARS + 1);
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(oversized);
        assertTrue(result.domains.isEmpty());
        assertEquals(1, result.invalidCount);
    }
    @Test public void parsesAdguardAndUnconditionalImportantRules() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "||ads.example.com^\\n||tracker.example.net^$important\\n");
        assertEquals(2, result.domains.size());
        assertTrue(result.domains.contains("ads.example.com"));
        assertTrue(result.domains.contains("tracker.example.net"));
    }

    @Test public void parsesDnsmasqAddressServerAndLocalRules() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "address=/ads.example.com/0.0.0.0\n"
                        + "server=/tracker.example.net/\n"
                        + "local=/metrics.example.org/\n");
        assertEquals(3, result.domains.size());
        assertEquals(0, result.invalidCount);
    }

    @Test public void parsesWildcardAndInternationalizedDomains() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "*.ads.example.com\n广告.例子.中国\n");
        assertEquals(2, result.domains.size());
        assertTrue(result.domains.contains("ads.example.com"));
        assertTrue(result.domains.get(1).startsWith("xn--"));
    }

    @Test public void reportsConditionalExceptionsAndIgnoresCosmeticRules() {
        DomainRuleFileCodec.ParseResult result = DomainRuleFileCodec.parse(
                "@@||allow.example.com^\n"
                        + "||conditional.example.com^$third-party\n"
                        + "example.com##.ad-banner\n");
        assertEquals(0, result.domains.size());
        assertEquals(2, result.unsupportedCount);
        assertEquals(1, result.ignoredLineCount);
    }

    @Test public void acceptsLargerImportCapacity() {
        assertEquals(20 * 1024 * 1024, DomainRuleFileCodec.MAX_IMPORT_CHARS);
        assertEquals(100_000, DomainRuleFileCodec.MAX_PARSED_DOMAINS);
    }

}
