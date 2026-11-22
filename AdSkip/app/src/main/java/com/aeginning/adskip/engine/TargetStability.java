package com.aeginning.adskip.engine;

/** Position stability for two observations of a moving/resizing UI target. */
public final class TargetStability {
    private TargetStability() { }

    public static boolean areBoundsConsistent(
            int firstLeft, int firstTop, int firstRight, int firstBottom,
            int secondLeft, int secondTop, int secondRight, int secondBottom,
            int screenWidth, int screenHeight, int minTolerancePx, int maxTolerancePx) {
        if (screenWidth <= 0 || screenHeight <= 0 || minTolerancePx < 0
                || maxTolerancePx < minTolerancePx) return false;
        if (firstRight <= firstLeft || firstBottom <= firstTop
                || secondRight <= secondLeft || secondBottom <= secondTop) return false;

        int toleranceX = Math.max(minTolerancePx,
                Math.min(maxTolerancePx, Math.round(screenWidth * 0.045f)));
        int toleranceY = Math.max(minTolerancePx,
                Math.min(maxTolerancePx, Math.round(screenHeight * 0.035f)));
        int firstCenterX = (firstLeft + firstRight) / 2;
        int firstCenterY = (firstTop + firstBottom) / 2;
        int secondCenterX = (secondLeft + secondRight) / 2;
        int secondCenterY = (secondTop + secondBottom) / 2;
        if (Math.abs(firstCenterX - secondCenterX) > toleranceX
                || Math.abs(firstCenterY - secondCenterY) > toleranceY) return false;

        double firstArea = (double) (firstRight - firstLeft) * (firstBottom - firstTop);
        double secondArea = (double) (secondRight - secondLeft) * (secondBottom - secondTop);
        double ratio = Math.max(firstArea, secondArea) / Math.max(1d, Math.min(firstArea, secondArea));
        return ratio <= 4.0d;
    }
}
