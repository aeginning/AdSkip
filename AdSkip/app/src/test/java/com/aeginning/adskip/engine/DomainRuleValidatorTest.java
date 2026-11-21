package com.aeginning.adskip.engine;

import org.junit.Test;

import static org.junit.Assert.*;

public class DomainRuleValidatorTest {
    @Test public void normalizesCaseWildcardAndRootDot() {
        assertEquals("ads.example.com", DomainRuleValidator.normalize(" *.Ads.Example.COM. "));
        assertEquals("ads.example.com", DomainRuleValidator.normalize(".ads.example.com"));
    }

    @Test public void convertsInternationalizedDomainNamesToAscii() {
        String result = DomainRuleValidator.normalize("例子.测试");
        assertTrue(result.startsWith("xn--"));
        assertTrue(result.contains(".xn--"));
    }

    @Test public void rejectsBareTopLevelAndSingleLabelDomains() {
        assertEquals("", DomainRuleValidator.normalize("com"));
        assertEquals("", DomainRuleValidator.normalize("net"));
        assertEquals("", DomainRuleValidator.normalize("intranet"));
    }

    @Test public void rejectsUrlsPortsAndIpAddresses() {
        assertEquals("", DomainRuleValidator.normalize("https://example.com/ads"));
        assertEquals("", DomainRuleValidator.normalize("example.com:443"));
        assertEquals("", DomainRuleValidator.normalize("192.0.2.10"));
        assertEquals("", DomainRuleValidator.normalize("2001:db8::1"));
    }

    @Test public void rejectsInvalidAndOverlongLabels() {
        assertEquals("", DomainRuleValidator.normalize("bad..example.com"));
        assertEquals("", DomainRuleValidator.normalize("-bad.example.com"));
        assertEquals("", DomainRuleValidator.normalize("bad_.example.com"));
        assertEquals("", DomainRuleValidator.normalize("a".repeat(64) + ".example.com"));
        assertEquals("", DomainRuleValidator.normalize(null));
    }

    @Test public void acceptsNormalMultiLabelHosts() {
        assertEquals("adservice.google.com", DomainRuleValidator.normalize("adservice.google.com"));
        assertEquals("tracker.example.co.uk", DomainRuleValidator.normalize("tracker.example.co.uk"));
    }
}
