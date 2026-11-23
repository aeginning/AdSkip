package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExtremeSkipPolicyTest {
    @Test public void extremeModeScansAndRescansMoreFrequently() {
        assertTrue(ExtremeSkipPolicy.eventIntervalMs(true) < ExtremeSkipPolicy.eventIntervalMs(false));
        assertTrue(ExtremeSkipPolicy.adOcrIntervalMs(true) < ExtremeSkipPolicy.adOcrIntervalMs(false));
        assertTrue(ExtremeSkipPolicy.visualProbeIntervalMs(true) < ExtremeSkipPolicy.visualProbeIntervalMs(false));
        assertTrue(ExtremeSkipPolicy.countdownRescanIntervalMs(true)
                < ExtremeSkipPolicy.countdownRescanIntervalMs(false));
        assertTrue(ExtremeSkipPolicy.confirmationDelayMs(true)
                < ExtremeSkipPolicy.confirmationDelayMs(false));
    }

    @Test public void extremeModeUsesSub100msEventAndRescanIntervals() {
        assertEquals(70L, ExtremeSkipPolicy.eventIntervalMs(true));
        assertEquals(170L, ExtremeSkipPolicy.adOcrIntervalMs(true));
        assertEquals(90L, ExtremeSkipPolicy.countdownRescanIntervalMs(true));
        assertEquals(60L, ExtremeSkipPolicy.confirmationDelayMs(true));
    }

    @Test public void extremeModeHasABoundedCountdownBudget() {
        assertEquals(48, ExtremeSkipPolicy.maxCountdownRescans(false));
        assertEquals(96, ExtremeSkipPolicy.maxCountdownRescans(true));
    }

    @Test public void riskModeUsesAggressiveButBoundedIntervals() {
        assertEquals(30L, ExtremeSkipPolicy.eventIntervalMs(true, true));
        assertEquals(85L, ExtremeSkipPolicy.adOcrIntervalMs(true, true));
        assertEquals(180L, ExtremeSkipPolicy.visualProbeIntervalMs(true, true));
        assertEquals(40L, ExtremeSkipPolicy.countdownRescanIntervalMs(true, true));
        assertEquals(30L, ExtremeSkipPolicy.confirmationDelayMs(true, true));
        assertEquals(160, ExtremeSkipPolicy.maxCountdownRescans(true, true));
        assertEquals(50, ExtremeSkipPolicy.minimumSkipScore(50, true, true));
    }

    @Test public void extremeModeUsesLowerFloorWithoutChangingSavedSensitivity() {
        assertEquals(60, ExtremeSkipPolicy.minimumSkipScore(0, true));
        assertEquals(60, ExtremeSkipPolicy.minimumSkipScore(50, true));
        assertEquals(60, ExtremeSkipPolicy.minimumSkipScore(100, true));
        assertEquals(90, ExtremeSkipPolicy.minimumSkipScore(0, false));
        assertEquals(75, ExtremeSkipPolicy.minimumSkipScore(50, false));
        assertEquals(60, ExtremeSkipPolicy.minimumSkipScore(100, false));
    }
}
