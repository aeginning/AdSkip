package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppScanPolicyTest {
    @Test public void systemAndUpdatedSystemAppsAreAlwaysExcluded() {
        assertTrue(AppScanPolicy.isSystemAppFlags(AppScanPolicy.FLAG_SYSTEM));
        assertTrue(AppScanPolicy.isSystemAppFlags(AppScanPolicy.FLAG_UPDATED_SYSTEM_APP));
        assertTrue(AppScanPolicy.isSystemAppFlags(
                AppScanPolicy.FLAG_SYSTEM | AppScanPolicy.FLAG_UPDATED_SYSTEM_APP));
        assertFalse(AppScanPolicy.isSystemAppFlags(0));
    }

    @Test public void foregroundAndAppAllowListAreAlwaysRequired() {
        assertFalse(AppScanPolicy.shouldScan(0, false, true, false, false));
        assertFalse(AppScanPolicy.shouldScan(0, true, false, false, false));
        assertTrue(AppScanPolicy.shouldScan(0, true, true, false, false));
    }

    @Test public void userLaunchModeRequiresExplicitLaunchProof() {
        assertFalse(AppScanPolicy.shouldScan(0, true, true, true, false));
        assertTrue(AppScanPolicy.shouldScan(0, true, true, true, true));
    }

    @Test public void systemAppsStayExcludedEvenWhenWhitelistedAndUserLaunched() {
        assertFalse(AppScanPolicy.shouldScan(
                AppScanPolicy.FLAG_SYSTEM, true, true, true, true));
    }
}
