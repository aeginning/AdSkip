package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowSessionPolicyTest {
    @Test public void packageChangeStartsANewSession() {
        assertTrue(WindowSessionPolicy.isNewSession(
                "com.example.old", "com.example.new", "A", "A", 1, 1, true));
    }

    @Test public void samePackageAndWindowDoesNotInvalidatePendingOcr() {
        assertFalse(WindowSessionPolicy.isNewSession(
                "com.example.app", "com.example.app", "MainActivity", "", 7, 7, false));
    }

    @Test public void differentWindowIdStartsANewSessionEvenWithinSamePackage() {
        assertTrue(WindowSessionPolicy.isNewSession(
                "com.example.app", "com.example.app", "MainActivity", "MainActivity", 7, 8, false));
    }

    @Test public void differentActivityClassStartsANewSessionForStateEvents() {
        assertTrue(WindowSessionPolicy.isNewSession(
                "com.example.app", "com.example.app", "MainActivity", "PlayerActivity", 7, 7, true));
    }

    @Test public void incompleteWindowIdentityDoesNotInventASwitch() {
        assertFalse(WindowSessionPolicy.isNewSession(
                "com.example.app", "com.example.app", "MainActivity", "", -1, -1, true));
        assertFalse(WindowSessionPolicy.isNewSession(
                "com.example.app", " ", "MainActivity", "PlayerActivity", 7, 8, true));
    }
}
