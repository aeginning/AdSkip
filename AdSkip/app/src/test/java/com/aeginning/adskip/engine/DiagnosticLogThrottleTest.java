package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticLogThrottleTest {
    @Test public void sameEventIsThrottledButDifferentEventsAreLogged() {
        DiagnosticLogThrottle throttle = new DiagnosticLogThrottle();
        assertTrue(throttle.shouldLog("pkg|SCAN", 1000, 2000));
        assertFalse(throttle.shouldLog("pkg|SCAN", 1500, 2000));
        assertTrue(throttle.shouldLog("pkg|NO_CANDIDATE", 1500, 2000));
        assertTrue(throttle.shouldLog("pkg|SCAN", 3100, 2000));
    }

    @Test public void clockRollbackDoesNotPermanentlyMuteDiagnostics() {
        DiagnosticLogThrottle throttle = new DiagnosticLogThrottle();
        assertTrue(throttle.shouldLog("pkg|OCR_ERROR", 5000, 1000));
        assertTrue(throttle.shouldLog("pkg|OCR_ERROR", 1000, 1000));
    }

    @Test public void invalidKeysAreRejected() {
        DiagnosticLogThrottle throttle = new DiagnosticLogThrottle();
        assertFalse(throttle.shouldLog("", 0, 1000));
        assertFalse(throttle.shouldLog(null, 0, 1000));
    }
}
