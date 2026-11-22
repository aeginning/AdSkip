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

    @Test public void extremeModeHasABoundedCountdownBudget() {
        assertEquals(48, ExtremeSkipPolicy.maxCountdownRescans(false));
        assertEquals(96, ExtremeSkipPolicy.maxCountdownRescans(true));
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
