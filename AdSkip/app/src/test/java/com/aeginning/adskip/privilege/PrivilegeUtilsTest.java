package com.aeginning.adskip.privilege;

import org.junit.Test;

import static org.junit.Assert.*;

public class PrivilegeUtilsTest {
    @Test public void packageNameValidationAcceptsNormalPackages() {
        assertTrue(PrivilegeUtils.isValidPackageName("com.example"));
        assertTrue(PrivilegeUtils.isValidPackageName("com.example.app"));
        assertTrue(PrivilegeUtils.isValidPackageName("com.aeginning.adskip"));
    }

    @Test public void packageNameValidationRejectsCommandInjectionAndInvalidNames() {
        assertFalse(PrivilegeUtils.isValidPackageName("com.example.app;settings put secure x y"));
        assertFalse(PrivilegeUtils.isValidPackageName("com"));
        assertFalse(PrivilegeUtils.isValidPackageName("../example.app"));
        assertFalse(PrivilegeUtils.isValidPackageName(null));
    }

    @Test public void identityLabelsDistinguishRootAndAdbShell() {
        assertEquals("Root (UID 0)", PrivilegeUtils.identityLabel(0));
        assertEquals("ADB Shell (UID 2000)", PrivilegeUtils.identityLabel(2000));
        assertTrue(PrivilegeUtils.identityLabel(1234).contains("1234"));
        assertEquals("未知 / 尚未授权", PrivilegeUtils.identityLabel(-1));
    }

    @Test public void parseUidReadsOnlyUidField() {
        assertEquals(2000, PrivilegeUtils.parseUid("uid=2000;role=ADB/SHELL;android=35"));
        assertEquals(0, PrivilegeUtils.parseUid("role=ROOT;uid=0;android=35"));
        assertEquals(-1, PrivilegeUtils.parseUid("uid=bad"));
        assertEquals(-1, PrivilegeUtils.parseUid(null));
    }

    @Test public void foregroundPackageParserReadsTopResumedComponentOnly() {
        assertEquals("com.example.player", PrivilegeUtils.extractForegroundPackage(
                "topResumedActivity=ActivityRecord{12 abcd u0 com.example.player/.MainActivity t42}"));
        assertEquals("com.example.other", PrivilegeUtils.extractForegroundPackage(
                "mResumedActivity: ActivityRecord{9 x u0 com.example.other/com.example.other.HomeActivity}"));
        assertEquals("com.example.app", PrivilegeUtils.extractForegroundPackage("com.example.app"));
    }

    @Test public void topResumedParserIgnoresPausedActivities() {
        assertEquals("", PrivilegeUtils.extractTopResumedPackage(
                "mLastPausedActivity: ActivityRecord{12 x u0 com.example.paused/.MainActivity}"));
        assertEquals("com.example.foreground", PrivilegeUtils.extractTopResumedPackage(
                "mLastPausedActivity: ActivityRecord{9 x u0 com.example.old/.OldActivity}\n"
                        + "topResumedActivity=ActivityRecord{12 y u0 com.example.foreground/.HomeActivity}"));
    }

    @Test public void topResumedActivityTakesPriorityOverOtherResumedActivities() {
        assertEquals("com.example.top", PrivilegeUtils.extractTopResumedPackage(
                "mResumedActivity: ActivityRecord{8 x u0 com.example.other/.OtherActivity}\n"
                        + "topResumedActivity=ActivityRecord{12 y u0 com.example.top/.HomeActivity}"));
    }

    @Test public void foregroundPackageParserNeverGuessesFromArbitraryText() {
        assertEquals("", PrivilegeUtils.extractForegroundPackage(null));
        assertEquals("", PrivilegeUtils.extractForegroundPackage("no resumed activity"));
        assertEquals("", PrivilegeUtils.extractForegroundPackage("com"));
        assertEquals("", PrivilegeUtils.extractForegroundPackage("pm list packages: com.example.app"));
    }

    @Test public void packageListIsFilteredAndBounded() {
        String raw = "package:com.example.one\npackage:com.example.two\nnot a package\npackage:com.example.one";
        String result = PrivilegeUtils.sanitizePackageList(raw, 10);
        assertTrue(result.contains("com.example.one"));
        assertTrue(result.contains("com.example.two"));
        assertFalse(result.contains("not a package"));
        assertEquals(1, count(result, "com.example.one"));
    }

    private int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }
}
