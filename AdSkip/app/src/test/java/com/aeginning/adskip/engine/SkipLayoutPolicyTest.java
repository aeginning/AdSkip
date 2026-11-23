package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkipLayoutPolicyTest {
    @Test public void acceptsThePillLayoutShownInTheSplashScreenshot() {
        assertTrue(SkipLayoutPolicy.isTopRightSkipPill(
                500, 171, 652, 237, 691, 1536));
    }

    @Test public void rejectsTopLeftCircularSkipBadgesAndOrdinaryButtons() {
        assertFalse(SkipLayoutPolicy.isTopRightSkipPill(
                30, 228, 96, 286, 691, 1536));
        assertFalse(SkipLayoutPolicy.isTopRightSkipPill(
                500, 171, 560, 230, 691, 1536));
        assertFalse(SkipLayoutPolicy.isTopRightSkipPill(
                500, 20, 652, 86, 691, 1536));
    }

    @Test public void rejectsInvalidScreenAndBounds() {
        assertFalse(SkipLayoutPolicy.isTopRightSkipPill(1, 1, 0, 0, 691, 1536));
        assertFalse(SkipLayoutPolicy.isTopRightSkipPill(1, 1, 100, 100, 0, 1536));
    }
}
