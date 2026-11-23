package com.aeginning.adskip.engine;

/**
 * Runtime tuning for the opt-in high-frequency recognition mode.
 *
 * The mode changes observation cadence and the candidate score floor only.
 * It does not disable the final risk-word, geometry, app allow-list, window,
 * sensitive-input, click cooldown, or confirmation checks.
 */
public final class ExtremeSkipPolicy {
    private ExtremeSkipPolicy() { }

    public static long eventIntervalMs(boolean extreme) {
        return eventIntervalMs(extreme, false);
    }

    public static long eventIntervalMs(boolean extreme, boolean riskMode) {
        return riskMode ? 30L : extreme ? 70L : 350L;
    }

    public static long adOcrIntervalMs(boolean extreme) {
        return adOcrIntervalMs(extreme, false);
    }

    public static long adOcrIntervalMs(boolean extreme, boolean riskMode) {
        return riskMode ? 85L : extreme ? 170L : 850L;
    }

    public static long visualProbeIntervalMs(boolean extreme) {
        return visualProbeIntervalMs(extreme, false);
    }

    public static long visualProbeIntervalMs(boolean extreme, boolean riskMode) {
        return riskMode ? 180L : extreme ? 450L : 7_000L;
    }

    public static long countdownRescanIntervalMs(boolean extreme) {
        return countdownRescanIntervalMs(extreme, false);
    }

    public static long countdownRescanIntervalMs(boolean extreme, boolean riskMode) {
        return riskMode ? 40L : extreme ? 90L : 700L;
    }

    public static long confirmationDelayMs(boolean extreme) {
        return confirmationDelayMs(extreme, false);
    }

    public static long confirmationDelayMs(boolean extreme, boolean riskMode) {
        return riskMode ? 30L : extreme ? 60L : 260L;
    }

    public static int maxCountdownRescans(boolean extreme) {
        return maxCountdownRescans(extreme, false);
    }

    public static int maxCountdownRescans(boolean extreme, boolean riskMode) {
        return riskMode ? 160 : extreme ? 96 : 48;
    }

    public static int minimumSkipScore(int configuredSensitivity, boolean extreme) {
        return minimumSkipScore(configuredSensitivity, extreme, false);
    }

    public static int minimumSkipScore(int configuredSensitivity, boolean extreme, boolean riskMode) {
        // 50 is the lowest scorer floor; all hard safety gates remain mandatory.
        return riskMode ? 50 : extreme ? 60
                : AdConfidenceScorer.minimumScoreForSensitivity(configuredSensitivity);
    }
}
