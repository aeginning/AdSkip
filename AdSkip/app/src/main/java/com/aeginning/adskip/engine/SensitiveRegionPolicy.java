package com.aeginning.adskip.engine;

/** Pure geometry policy used to keep automation away from sensitive form fields. */
public final class SensitiveRegionPolicy {
    private SensitiveRegionPolicy() { }

    public static boolean overlapsOrNear(
            int targetLeft, int targetTop, int targetRight, int targetBottom,
            int regionLeft, int regionTop, int regionRight, int regionBottom,
            int marginX, int marginY) {
        if (targetRight <= targetLeft || targetBottom <= targetTop
                || regionRight <= regionLeft || regionBottom <= regionTop
                || marginX < 0 || marginY < 0) return true;

        long protectedLeft = (long) regionLeft - marginX;
        long protectedTop = (long) regionTop - marginY;
        long protectedRight = (long) regionRight + marginX;
        long protectedBottom = (long) regionBottom + marginY;
        return targetLeft < protectedRight && targetRight > protectedLeft
                && targetTop < protectedBottom && targetBottom > protectedTop;
    }
}
