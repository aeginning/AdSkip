package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class UserLaunchTrackerTest {
    @Test public void exactPackageFromLauncherIntentAuthorizesForegroundPackage() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("com.example.video", 1_000L);
        assertTrue(tracker.onForegroundPackage("com.example.video", 2_000L));
        assertTrue(tracker.isUserLaunched("com.example.video"));
    }

    @Test public void mismatchedPackageCannotUseAnotherAppsLauncherIntent() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("com.example.maps", 1_000L);
        assertFalse(tracker.onForegroundPackage("com.example.video", 1_500L));
        assertFalse(tracker.isUserLaunched("com.example.video"));
        assertFalse(tracker.isUserLaunched("com.example.maps"));
    }

    @Test public void expiredLauncherIntentCannotAuthorizeApp() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("com.example.video", 1_000L);
        assertFalse(tracker.onForegroundPackage("com.example.video",
                1_000L + UserLaunchTracker.LAUNCH_INTENT_TIMEOUT_MS + 1L));
    }

    @Test public void authorizationIsRevokedWhenAppLosesForeground() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("com.example.video", 1_000L);
        assertTrue(tracker.onForegroundPackage("com.example.video", 2_000L));
        assertTrue(tracker.isUserLaunched("com.example.video"));

        tracker.onForegroundChanged("com.android.settings");
        assertFalse(tracker.isUserLaunched("com.example.video"));
        assertFalse(tracker.isUserLaunched("com.android.settings"));
    }

    @Test public void foregroundChangePreservesPendingLauncherPackageIntent() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("com.example.maps", 1_000L);
        tracker.onForegroundChanged("com.android.launcher");
        assertTrue(tracker.onForegroundPackage("com.example.maps", 2_000L));
    }

    @Test public void newLauncherClickClearsOldAuthorization() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick("com.example.video", 1_000L);
        assertTrue(tracker.onForegroundPackage("com.example.video", 2_000L));
        tracker.recordLauncherClick("com.example.maps", 3_000L);

        assertFalse(tracker.isUserLaunched("com.example.video"));
        assertTrue(tracker.onForegroundPackage("com.example.maps", 4_000L));
    }

    @Test public void launcherLabelNormalizationIgnoresCaseSpacingAndCompatibilityWidth() {
        assertEquals("youtube",
                UserLaunchTracker.normalizeLauncherLabel("  ＹｏｕＴｕｂｅ  "));
        assertEquals("maps", UserLaunchTracker.normalizeLauncherLabel("Maps"));
        assertEquals("", UserLaunchTracker.normalizeLauncherLabel(" -- "));
    }

    @Test public void emptyLauncherPackageFailsClosed() {
        UserLaunchTracker tracker = new UserLaunchTracker();
        tracker.recordLauncherClick(" ", 1_000L);
        assertFalse(tracker.onForegroundPackage("com.example.video", 1_100L));
    }
}
