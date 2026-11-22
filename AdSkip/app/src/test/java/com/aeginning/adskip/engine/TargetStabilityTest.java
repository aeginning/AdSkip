package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetStabilityTest {
    @Test public void toleratesDensityAndSmallLayoutShifts() {
        assertTrue(TargetStability.areBoundsConsistent(
                900, 120, 980, 170, 970, 132, 1050, 182,
                1080, 2400, 80, 210));
    }

    @Test public void rejectsLargeJumpsAndUnrelatedTargetSizes() {
        assertFalse(TargetStability.areBoundsConsistent(
                900, 120, 980, 170, 300, 900, 380, 950,
                1080, 2400, 80, 210));
        assertFalse(TargetStability.areBoundsConsistent(
                900, 120, 920, 140, 900, 120, 1080, 400,
                1080, 2400, 80, 210));
    }

    @Test public void rejectsInvalidGeometry() {
        assertFalse(TargetStability.areBoundsConsistent(
                20, 20, 20, 40, 20, 20, 40, 40,
                1080, 2400, 80, 210));
    }
}
