package com.aeginning.adskip.engine;

import org.junit.Test;

import static org.junit.Assert.*;

public class OcrRegionPolicyTest {
    @Test public void adContextAndSplashProbeEnableFastRegion() {
        assertTrue(OcrRegionPolicy.shouldTryTopRightFirst(true, false));
        assertTrue(OcrRegionPolicy.shouldTryTopRightFirst(false, true));
        assertTrue(OcrRegionPolicy.shouldTryTopRightFirst(true, true));
        assertFalse(OcrRegionPolicy.shouldTryTopRightFirst(false, false));
    }

    @Test public void regionIsBoundedInsideTheOriginalFrame() {
        int width = 1080;
        int height = 2400;
        int left = OcrRegionPolicy.left(width);
        int top = OcrRegionPolicy.top(height);
        int right = OcrRegionPolicy.right(width);
        int bottom = OcrRegionPolicy.bottom(height);

        assertTrue(left >= 0);
        assertTrue(top >= 0);
        assertTrue(right <= width);
        assertTrue(bottom <= height);
        assertTrue(right > left);
        assertTrue(bottom > top);
        assertTrue((right - left) < width);
        assertTrue((bottom - top) < height);
    }

    @Test public void tooSmallFramesSkipRegionCropping() {
        assertFalse(OcrRegionPolicy.canCrop(319, 1080));
        assertFalse(OcrRegionPolicy.canCrop(1080, 319));
        assertTrue(OcrRegionPolicy.canCrop(320, 320));
    }

    @Test public void calculatedRegionCoversUpperRightHalfWithoutClipping() {
        int width = 720;
        int height = 1600;
        assertEquals(302, OcrRegionPolicy.left(width));
        assertEquals(0, OcrRegionPolicy.top(height));
        assertEquals(720, OcrRegionPolicy.right(width));
        assertEquals(768, OcrRegionPolicy.bottom(height));
    }
}
