package com.aeginning.adskip.engine;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DnsFeedParserTest {
    @Test public void parsesDnsmasqDomainScopedRulesAndVersion() throws Exception {
        StringBuilder data = new StringBuilder("# Version: 2026.11.22\\n")
                .append("local=/ads.example.com/\\n")
                .append("server=/tracker.example.net/1.1.1.1\\n")
                .append("local=/localhost/\\n")
                .append("local=/#/\\n")
                .append("address=/bad.example.org/0.0.0.0\\n");
        for (int i = 0; i < 1_000; i++) data.append("local=/host").append(i).append(".example.net/\\n");
        DnsFeedParser.ParsedFeed result = DnsFeedParser.parse(
                new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8)),
                DnsFeedParser.Format.DNSMASQ);
        assertTrue(result.domains.contains("ads.example.com"));
        assertTrue(result.domains.contains("tracker.example.net"));
        assertTrue(result.domains.contains("bad.example.org"));
        assertFalse(result.domains.contains("localhost"));
        assertEquals("2026.11.22", result.version);
    }

    @Test public void parsesHostsFormatAndIgnoresAddressesAndLocalAliases() throws Exception {
        StringBuilder data = new StringBuilder("# Date: 22 Nov 2026\\n")
                .append("0.0.0.0 ads.example.com tracker.example.net\\n")
                .append("127.0.0.1 localhost\\n")
                .append("::1 ip6-localhost\\n");
        for (int i = 0; i < 1_000; i++) data.append("0.0.0.0 host").append(i).append(".example.net\\n");
        DnsFeedParser.ParsedFeed result = DnsFeedParser.parse(
                new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8)),
                DnsFeedParser.Format.HOSTS);
        assertTrue(result.domains.contains("ads.example.com"));
        assertTrue(result.domains.contains("tracker.example.net"));
        assertFalse(result.domains.contains("0.0.0.0"));
        assertEquals("22 Nov 2026", result.version);
    }

    @Test public void rejectsSmallOrInvalidFeedsInsteadOfReplacingExistingData() throws Exception {
        try {
            DnsFeedParser.parse(new ByteArrayInputStream(
                    "local=/ads.example.com/\n".getBytes(StandardCharsets.UTF_8)),
                    DnsFeedParser.Format.DNSMASQ);
            fail("Expected minimum-entry validation failure");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("at least"));
        }
    }

    @Test public void deduplicatesAndNormalizesDomains() throws Exception {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            data.append("local=/ADS").append(i).append(".Example.COM/\n");
            data.append("local=/ads").append(i).append(".example.com/\n");
        }
        DnsFeedParser.ParsedFeed result = DnsFeedParser.parse(
                new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8)),
                DnsFeedParser.Format.DNSMASQ);
        assertEquals(1000, result.domains.size());
        assertTrue(result.domains.contains("ads1.example.com"));
    }

    private String repeatToMinimum(String row) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            if (row.startsWith("#")) result.append(row.replace("\n", "\n"));
            else {
                // Add a unique hostname in the expected file syntax.
                if (row.startsWith("local=")) result.append("local=/host").append(i).append(".example.com/\n");
                else result.append("0.0.0.0 host").append(i).append(".example.com\n");
            }
        }
        return result.toString();
    }
}
