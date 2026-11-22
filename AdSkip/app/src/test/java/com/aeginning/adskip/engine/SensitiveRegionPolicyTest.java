package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SensitiveRegionPolicyTest {
    @Test public void rejectsTargetsInsideOrNearSensitiveField() {
        assertTrue(SensitiveRegionPolicy.overlapsOrNear(
                110, 110, 180, 150, 100, 100, 200, 160, 24, 18));
        assertTrue(SensitiveRegionPolicy.overlapsOrNear(
                205, 110, 250, 150, 100, 100, 200, 160, 24, 18));
        assertTrue(SensitiveRegionPolicy.overlapsOrNear(
                120, 180, 180, 220, 100, 100, 200, 160, 24, 24));
    }

    @Test public void allowsTargetsClearlyOutsideSensitiveField() {
        assertFalse(SensitiveRegionPolicy.overlapsOrNear(
                400, 800, 500, 850, 100, 100, 200, 160, 24, 18));
    }

    @Test public void invalidGeometryFailsClosed() {
        assertTrue(SensitiveRegionPolicy.overlapsOrNear(
                10, 10, 10, 20, 100, 100, 200, 160, 24, 18));
        assertTrue(SensitiveRegionPolicy.overlapsOrNear(
                10, 10, 20, 20, 100, 100, 90, 160, 24, 18));
    }
}
