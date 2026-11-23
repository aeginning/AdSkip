package com.aeginning.adskip.engine;

/**
 * Very narrow geometry fallback for a conventional top-right splash-ad Skip pill.
 * Only exact generic skip labels are eligible, and callers must separately enable
 * this fallback (the extreme mode). All other safety checks remain authoritative.
 */
public final class SkipLayoutPolicy {
    private SkipLayoutPolicy() { }

    public static boolean isTopRightSkipPill(int left, int top, int right, int bottom,
                                             int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) return false;
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return false;
        float centerX = (left + right) / 2f;
        float aspect = width / (float) height;
        return left >= screenWidth * 0.66f
                && centerX >= screenWidth * 0.78f
                && top >= screenHeight * 0.025f
                && top <= screenHeight * 0.22f
                && bottom <= screenHeight * 0.28f
                && width >= screenWidth * 0.12f
                && width <= screenWidth * 0.32f
                && height >= screenHeight * 0.025f
                && height <= screenHeight * 0.095f
                && aspect >= 1.65f
                && aspect <= 4.2f;
    }
}
