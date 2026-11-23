package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class PrivilegeProbePolicyTest {
    @Test public void aggressiveModePollsMoreFrequentlyButRemainsBounded() {
        assertEquals(1_500L, PrivilegeProbePolicy.intervalMs(false, false));
        assertEquals(650L, PrivilegeProbePolicy.intervalMs(true, false));
        assertEquals(300L, PrivilegeProbePolicy.intervalMs(true, true));
    }

    @Test public void unavailableShizukuUsesSlowRetry() {
        assertEquals(2_500L, PrivilegeProbePolicy.retryWhenUnavailableMs());
        assertEquals(2_500L, PrivilegeProbePolicy.nextIntervalMs(false, true, true));
        assertEquals(300L, PrivilegeProbePolicy.nextIntervalMs(true, true, true));
    }

    @Test public void newlyEnabledForegroundAppIsProbedWithoutReopeningIt() {
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", false, "com.example.app", true, "com.aeginning.adskip"));
    }

    @Test public void unchangedEnabledForegroundAppIsNotRepeatedlyReprobed() {
        assertFalse(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", true, "com.example.app", true, "com.aeginning.adskip"));
    }

    @Test public void packageSwitchToEnabledAppStartsAProbe() {
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.old", false, "com.example.new", true, "com.aeginning.adskip"));
    }

    @Test public void disabledStateTransitionIsRememberedWithoutStartingRecognition() {
        assertTrue(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", true, "com.example.app", false, "com.aeginning.adskip"));
        assertFalse(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.app", false, "com.example.app", false, "com.aeginning.adskip"));
    }

    @Test public void ownAppIsNeverAUserForegroundTarget() {
        assertFalse(PrivilegeProbePolicy.shouldProbeForeground(
                "com.example.old", false, "com.aeginning.adskip", false, "com.aeginning.adskip"));
    }
}
