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
        return extreme ? 70L : 350L;
    }

    public static long adOcrIntervalMs(boolean extreme) {
        return extreme ? 170L : 850L;
    }

    public static long visualProbeIntervalMs(boolean extreme) {
        return extreme ? 450L : 7_000L;
    }

    public static long countdownRescanIntervalMs(boolean extreme) {
        return extreme ? 90L : 700L;
    }

    public static long confirmationDelayMs(boolean extreme) {
        return extreme ? 60L : 260L;
    }

    public static int maxCountdownRescans(boolean extreme) {
        return extreme ? 96 : 48;
    }

    public static int minimumSkipScore(int configuredSensitivity, boolean extreme) {
        return extreme ? 60 : AdConfidenceScorer.minimumScoreForSensitivity(configuredSensitivity);
    }
}
