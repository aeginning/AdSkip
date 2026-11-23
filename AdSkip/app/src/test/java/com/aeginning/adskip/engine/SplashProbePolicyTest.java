package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SplashProbePolicyTest {
    @Test public void packageChangeStartsANewProbeWindow() {
        assertTrue(SplashProbePolicy.isNewLaunch("com.example.old",
                "com.example.new", 10_000L, 10_500L));
    }

    @Test public void sameAppWindowChangesDoNotRestartProbesImmediately() {
        assertFalse(SplashProbePolicy.isNewLaunch("com.example.app",
                "com.example.app", 10_000L, 10_500L));
    }

    @Test public void returningAfterBackgroundGapStartsNewProbeWindow() {
        assertTrue(SplashProbePolicy.isNewLaunch("com.example.app",
                "com.example.app", 10_000L, 10_000L + SplashProbePolicy.RESUME_GAP_MS));
    }

    @Test public void missingCurrentPackageNeverStartsAProbe() {
        assertFalse(SplashProbePolicy.isNewLaunch("com.example.app", null, 10_000L, 50_000L));
        assertFalse(SplashProbePolicy.isNewLaunch("com.example.app", "  ", 10_000L, 50_000L));
    }

    @Test public void missingOrReversedTimestampFailsTowardANewSession() {
        assertTrue(SplashProbePolicy.isNewLaunch("com.example.app",
                "com.example.app", 0L, 5_000L));
        assertTrue(SplashProbePolicy.isNewLaunch("com.example.app",
                "com.example.app", 8_000L, 7_000L));
    }

    @Test public void probeScheduleIsShortAndBounded() {
        assertEquals(650L, SplashProbePolicy.delayForAttempt(0));
        assertEquals(900L, SplashProbePolicy.delayForAttempt(1));
        assertEquals(900L, SplashProbePolicy.delayForAttempt(4));
        assertTrue(SplashProbePolicy.hasMoreAttempts(0));
        assertTrue(SplashProbePolicy.hasMoreAttempts(4));
        assertFalse(SplashProbePolicy.hasMoreAttempts(5));
        assertFalse(SplashProbePolicy.hasMoreAttempts(6));
    }

    @Test public void extremeProbeScheduleIsFasterButStillBounded() {
        assertEquals(100L, SplashProbePolicy.delayForAttempt(0, true));
        assertEquals(160L, SplashProbePolicy.delayForAttempt(1, true));
        assertEquals(160L, SplashProbePolicy.ocrIntervalMs(true));
        assertEquals(1_050L, SplashProbePolicy.ocrIntervalMs(false));
        assertTrue(SplashProbePolicy.hasMoreAttempts(23, true));
        assertFalse(SplashProbePolicy.hasMoreAttempts(24, true));
    }
}
