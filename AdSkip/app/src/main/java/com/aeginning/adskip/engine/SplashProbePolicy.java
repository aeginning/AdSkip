package com.aeginning.adskip.engine;

/**
 * Bounded timing policy for the short period immediately after a selected app
 * enters the foreground. It prevents a single early loading-frame screenshot
 * from being the only visual observation of a splash advertisement.
 */
public final class SplashProbePolicy {
    public static final int MAX_ATTEMPTS = 5;
    public static final int EXTREME_MAX_ATTEMPTS = 24;
    public static final long INITIAL_DELAY_MS = 650L;
    public static final long RETRY_DELAY_MS = 900L;
    public static final long OCR_INTERVAL_MS = 1_050L;
    public static final long EXTREME_INITIAL_DELAY_MS = 100L;
    public static final long EXTREME_RETRY_DELAY_MS = 160L;
    public static final long EXTREME_OCR_INTERVAL_MS = 160L;
    public static final long RESUME_GAP_MS = 30_000L;

    private SplashProbePolicy() { }

    public static boolean hasMoreAttempts(int completedAttempts) {
        return hasMoreAttempts(completedAttempts, false);
    }

    public static boolean hasMoreAttempts(int completedAttempts, boolean extreme) {
        return hasMoreAttempts(completedAttempts, extreme, false);
    }

    public static boolean hasMoreAttempts(int completedAttempts, boolean extreme, boolean riskMode) {
        return completedAttempts < (riskMode ? 32 : extreme ? EXTREME_MAX_ATTEMPTS : MAX_ATTEMPTS);
    }

    public static long delayForAttempt(int completedAttempts) {
        return delayForAttempt(completedAttempts, false);
    }

    public static long delayForAttempt(int completedAttempts, boolean extreme) {
        return delayForAttempt(completedAttempts, extreme, false);
    }

    public static long delayForAttempt(int completedAttempts, boolean extreme, boolean riskMode) {
        if (riskMode) return completedAttempts <= 0 ? 55L : 90L;
        if (extreme) return completedAttempts <= 0
                ? EXTREME_INITIAL_DELAY_MS : EXTREME_RETRY_DELAY_MS;
        return completedAttempts <= 0 ? INITIAL_DELAY_MS : RETRY_DELAY_MS;
    }

    public static long ocrIntervalMs(boolean extreme) {
        return ocrIntervalMs(extreme, false);
    }

    public static long ocrIntervalMs(boolean extreme, boolean riskMode) {
        return riskMode ? 100L : extreme ? EXTREME_OCR_INTERVAL_MS : OCR_INTERVAL_MS;
    }

    /**
     * A new splash-probe window is allowed when the selected package changes,
     * has no prior observation, or returns after a meaningful background gap.
     */
    public static boolean isNewLaunch(String previousPackage, String currentPackage,
                                      long previousAtMillis, long nowMillis) {
        String current = currentPackage == null ? "" : currentPackage.trim();
        if (current.isEmpty()) return false;
        String previous = previousPackage == null ? "" : previousPackage.trim();
        if (previous.isEmpty() || !current.equals(previous)) return true;
        if (previousAtMillis <= 0L || nowMillis < previousAtMillis) return true;
        return nowMillis - previousAtMillis >= RESUME_GAP_MS;
    }
}
