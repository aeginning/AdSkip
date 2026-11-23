package com.aeginning.adskip.engine;

/** Bounded Shizuku foreground-process polling intervals. */
public final class PrivilegeProbePolicy {
    private PrivilegeProbePolicy() { }

    public static long intervalMs(boolean extreme, boolean riskMode) {
        if (riskMode) return 300L;
        if (extreme) return 650L;
        return 1_500L;
    }

    /** Use the fast cadence only after a valid foreground package was resolved. */
    public static long nextIntervalMs(boolean foregroundPackageResolved,
                                      boolean extreme, boolean riskMode) {
        return foregroundPackageResolved
                ? intervalMs(extreme, riskMode) : retryWhenUnavailableMs();
    }

    /**
     * Re-probe when the package changes or when an already-foreground app becomes
     * newly enabled. This lets users enable the current app without reopening it.
     */
    public static boolean shouldProbeForeground(String previousPackage, boolean previousEnabled,
                                                String currentPackage, boolean currentEnabled,
                                                String ownPackage) {
        String current = normalize(currentPackage);
        if (current.isEmpty()) return false;
        // The caller also records the AdSkip package as the latest observation,
        // but never treats it as a user target. This is necessary to notice a
        // target app when the user returns after briefly opening AdSkip.
        String previous = normalize(previousPackage);
        // Report both package transitions and allow-list state transitions. The caller
        // records disabled states without probing, so a later false -> true change is seen.
        return !current.equals(previous) || previousEnabled != currentEnabled;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static long retryWhenUnavailableMs() {
        return 2_500L;
    }
}
