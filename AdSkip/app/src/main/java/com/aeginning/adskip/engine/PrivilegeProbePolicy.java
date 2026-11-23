package com.aeginning.adskip.engine;

/** Bounded Shizuku foreground-process polling intervals. */
public final class PrivilegeProbePolicy {
    private PrivilegeProbePolicy() { }

    public static long intervalMs(boolean extreme, boolean riskMode) {
        if (riskMode) return 300L;
        if (extreme) return 650L;
        return 1_500L;
    }

    public static long retryWhenUnavailableMs() {
        return 2_500L;
    }
}
