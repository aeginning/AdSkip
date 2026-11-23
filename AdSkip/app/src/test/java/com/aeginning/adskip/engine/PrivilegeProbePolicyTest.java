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
    }
}
