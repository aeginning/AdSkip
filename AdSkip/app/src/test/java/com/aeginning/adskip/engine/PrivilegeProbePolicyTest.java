package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class PrivilegeProbePolicyTest {
    @Test public void aggressiveModePollsMoreFrequentlyButRemainsBounded() {
        assertEquals(1_500L, PrivilegeProbePolicy.intervalMs(false, false));
        assertEquals(650L, PrivilegeProbePolicy.intervalMs(true, false));
        assertEquals(200L, PrivilegeProbePolicy.intervalMs(true, true));
    }

    @Test public void unavailableShizukuUsesSlowRetry() {
        assertEquals(2_500L, PrivilegeProbePolicy.retryWhenUnavailableMs());
        assertEquals(2_500L, PrivilegeProbePolicy.nextIntervalMs(false, true, true));
        assertEquals(300L, PrivilegeProbePolicy.nextIntervalMs(true, true, true));
    }

    @Test public void newlyEnabledForegroundAppIsProbedWithoutReopeningIt() {
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", false, "com.example.app", true));
    }

    @Test public void unchangedEnabledForegroundAppIsNotRepeatedlyReprobed() {
        assertFalse(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", true, "com.example.app", true));
    }

    @Test public void packageSwitchToEnabledAppStartsAProbe() {
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.old", false, "com.example.new", true));
    }

    @Test public void disabledStateTransitionIsRememberedWithoutStartingRecognition() {
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", true, "com.example.app", false));
        assertFalse(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", false, "com.example.app", false));
    }

    @Test public void ownAppTransitionIsRecordedSoReturnToTargetCanBeDetected() {
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", true, "com.aeginning.adskip", false));
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.aeginning.adskip", false, "com.example.app", true));
    }
}
