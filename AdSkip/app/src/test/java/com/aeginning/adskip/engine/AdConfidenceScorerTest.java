package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdConfidenceScorerTest {
    @Test public void explicitSkipAdGetsHighConfidence() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "Skip Ad", "Sponsored video", true, true, true, 90, 36, 1080, 2400);
        assertTrue(result.score >= 90);
        assertEquals(AdConfidenceScorer.Level.HIGH, result.level);
    }

    @Test public void genericSkipRequiresAdContext() {
        AdConfidenceScorer.Assessment withoutContext = AdConfidenceScorer.assess(
                "Skip", "Settings and account", true, true, true, 80, 34, 1080, 2400);
        assertEquals(0, withoutContext.score);
        AdConfidenceScorer.Assessment withContext = AdConfidenceScorer.assess(
                "Skip", "Sponsored content", true, true, true, 80, 34, 1080, 2400);
        assertTrue(withContext.score >= 75);
    }

    @Test public void sensitiveActionIsAlwaysRejected() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "Install", "Sponsored content", true, true, true, 90, 34, 1080, 2400);
        assertEquals(0, result.score);
    }

    @Test public void countdownIsAlwaysRejected() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "Skip in 5 seconds", "Sponsored video", true, true, true, 90, 34, 1080, 2400);
        assertEquals(0, result.score);
    }

    @Test public void countdownInSurroundingContextPreventsSkipClick() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "Skip", "Sponsored video · 5 seconds to skip", true, true, true, 80, 34, 1080, 2400);
        assertEquals(0, result.score);
    }

    @Test public void adCountdownContextWithoutSkipLabelIsStillBlocked() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "Skip", "Advertisement · 5 seconds remaining", true, true, true, 80, 34, 1080, 2400);
        assertEquals(0, result.score);
    }

    @Test public void twoObservationsMustBeConsistent() {
        AdConfidenceScorer.Assessment first = new AdConfidenceScorer.Assessment(
                94, AdConfidenceScorer.Level.HIGH, "first");
        AdConfidenceScorer.Assessment valid = new AdConfidenceScorer.Assessment(
                88, AdConfidenceScorer.Level.REVIEW, "second");
        AdConfidenceScorer.Assessment inconsistent = new AdConfidenceScorer.Assessment(
                60, AdConfidenceScorer.Level.LOW, "second");
        assertTrue(AdConfidenceScorer.canProceedAfterSecondObservation(first, valid));
        assertFalse(AdConfidenceScorer.canProceedAfterSecondObservation(first, inconsistent));
    }
}
