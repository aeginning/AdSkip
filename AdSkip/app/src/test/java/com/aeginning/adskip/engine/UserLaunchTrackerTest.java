package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class UserLaunchTrackerTest {
    @Test public void exactLauncherLabelAuthorizesForegroundPackage() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("YouTube", 1_000L);
        assertTrue(tracker.onForegroundPackage("com.example.video", "YouTube", 2_000L));
        assertTrue(tracker.isUserLaunched("com.example.video"));
    }

    @Test public void labelNormalizationIgnoresCaseSpacingAndCompatibilityWidth() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("  ＹｏｕＴｕｂｅ  ", 100L);
        assertTrue(tracker.onForegroundPackage("com.example.video", "youtube", 200L));
    }

    @Test public void mismatchedLauncherLabelCannotAuthorizeAnotherApp() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("Maps", 1_000L);
        assertFalse(tracker.onForegroundPackage("com.example.video", "YouTube", 1_500L));
        assertFalse(tracker.isUserLaunched("com.example.video"));
    }

    @Test public void expiredLauncherIntentCannotAuthorizeApp() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("YouTube", 1_000L);
        assertFalse(tracker.onForegroundPackage("com.example.video", "YouTube",
                1_000L + UserLaunchTracker.LAUNCH_INTENT_TIMEOUT_MS + 1L));
    }

    @Test public void authorizationSurvivesForegroundSwitchButCanBeReset() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("YouTube", 1_000L);
        assertTrue(tracker.onForegroundPackage("com.example.video", "YouTube", 2_000L));
        assertTrue(tracker.isUserLaunched("com.example.video"));
        assertFalse(tracker.isUserLaunched("com.example.music"));
        tracker.clear();
        assertFalse(tracker.isUserLaunched("com.example.video"));
    }

    @Test public void emptyLauncherLabelsFailClosed() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick(" ", 1_000L);
        assertFalse(tracker.onForegroundPackage("com.example.video", "YouTube", 1_100L));
    }
}
