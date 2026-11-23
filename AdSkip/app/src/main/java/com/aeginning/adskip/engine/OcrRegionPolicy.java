package com.aeginning.adskip.engine;

/**
 * Bounds the first OCR pass to the common upper-right skip/close area.
 * A miss always falls back to the original full-frame screenshot.
 */
public final class OcrRegionPolicy {
    private static final float LEFT_RATIO = 0.42f;
    private static final float BOTTOM_RATIO = 0.48f;

    private OcrRegionPolicy() { }

    public static boolean shouldTryTopRightFirst(boolean adContext, boolean splashProbe) {
        return adContext || splashProbe;
    }

    public static boolean canCrop(int width, int height) {
        return width >= 320 && height >= 320;
    }

    public static int left(int width) {
        return Math.max(0, Math.min(width - 1, Math.round(width * LEFT_RATIO)));
    }

    public static int top(int height) {
        return 0;
    }

    public static int right(int width) {
        return Math.max(1, width);
    }

    public static int bottom(int height) {
        return Math.max(1, Math.min(height, Math.round(height * BOTTOM_RATIO)));
    }
}
