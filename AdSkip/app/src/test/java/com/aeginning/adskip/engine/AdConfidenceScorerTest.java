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

    @Test public void negatedSkipTextNeverGetsConfidence() {
        assertEquals(0, AdConfidenceScorer.assess(
                "Don't skip this ad", "Sponsored video", true, true, true, 90, 36, 1080, 2400).score);
        assertEquals(0, AdConfidenceScorer.assess(
                "不可跳过", "广告内容", true, true, true, 90, 36, 1080, 2400).score);
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

    @Test public void XGlyphOnlyQualifiesWithAdContext() {
        AdConfidenceScorer.Assessment adX = AdConfidenceScorer.assess(
                "X", "广告来源：趣盟", true, true, true, 40, 36, 1080, 2400);
        AdConfidenceScorer.Assessment normalX = AdConfidenceScorer.assess(
                "X", "Settings and account", true, true, true, 40, 36, 1080, 2400);
        assertTrue(adX.score >= 90);
        assertEquals(0, normalX.score);
    }

    @Test public void higherSensitivityAllowsLowerConfidenceSkipCandidates() {
        AdConfidenceScorer.Assessment strict = AdConfidenceScorer.assess(
                "跳过", "广告来源：趣盟", true, true, true, 80, 34, 1080, 2400,
                false, AdConfidenceScorer.minimumScoreForSensitivity(0));
        AdConfidenceScorer.Assessment permissive = AdConfidenceScorer.assess(
                "跳过", "广告来源：趣盟", true, true, true, 80, 34, 1080, 2400,
                false, AdConfidenceScorer.minimumScoreForSensitivity(100));
        assertEquals(0, strict.score);
        assertTrue(permissive.score >= 60);
    }

    @Test public void smallXGlyphCanPassScoringWithAdContext() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "X", "广告来源：趣盟", true, true, true, 14, 14, 1080, 2400);
        assertTrue(result.score >= 90);
    }

    @Test public void largerCloseGlyphStillFitsTheExpandedBounds() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "×", "Sponsored advertisement", true, true, true, 210, 300, 1080, 2400);
        assertTrue(result.score >= 90);
    }

    @Test public void XGlyphCanBeDetectedWhenCountdownTextAppearsElsewhere() {
        AdConfidenceScorer.Assessment result = AdConfidenceScorer.assess(
                "×", "广告来源：趣盟 · 跳过 4", true, true, true, 40, 36, 1080, 2400,
                false, 75);
        assertTrue(result.score >= 75);
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
