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

    @Test public void authorizationIsRevokedWhenAppLosesForeground() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("YouTube", 1_000L);
        assertTrue(tracker.onForegroundPackage("com.example.video", "YouTube", 2_000L));
        assertTrue(tracker.isUserLaunched("com.example.video"));

        tracker.onForegroundChanged("com.android.settings");
        assertFalse(tracker.isUserLaunched("com.example.video"));
        assertFalse(tracker.isUserLaunched("com.android.settings"));
    }

    @Test public void launcherClickKeepsPendingIntentWhileClearingPreviousAuthorization() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("YouTube", 1_000L);
        assertTrue(tracker.onForegroundPackage("com.example.video", "YouTube", 2_000L));

        tracker.onForegroundChanged("com.android.launcher");
        assertFalse(tracker.isUserLaunched("com.example.video"));

        tracker.recordLauncherClick("Maps", 3_000L);
        tracker.onForegroundChanged("com.android.launcher");
        assertTrue(tracker.onForegroundPackage("com.example.maps", "Maps", 4_000L));
        assertTrue(tracker.isUserLaunched("com.example.maps"));
        assertFalse(tracker.isUserLaunched("com.example.video"));
    }

    @Test public void emptyLauncherLabelsFailClosed() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick(" ", 1_000L);
        assertFalse(tracker.onForegroundPackage("com.example.video", "YouTube", 1_100L));
    }
}
