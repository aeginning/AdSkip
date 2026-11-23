package com.aeginning.adskip.engine;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class SafetyPolicyTest {
    private static final int SCREEN_WIDTH = 1080;
    private static final int SCREEN_HEIGHT = 2400;

    @Test public void reusesAssessmentForTheSameExplicitSkipCandidate() {
        AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(
                "跳过广告", "正在播放广告", true, true, true,
                100, 40, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertTrue(assessment.score >= 90);

        assertTrue(SafetyPolicy.isSafeTarget(
                new Rect(760, 100, 900, 150), SCREEN_WIDTH, SCREEN_HEIGHT,
                true, true, true, "跳过广告", "正在播放广告",
                false, 75, Collections.emptyList(), false, assessment));
    }

    @Test public void precomputedHighScoreCannotBypassDangerousCandidateGate() {
        AdConfidenceScorer.Assessment forged = new AdConfidenceScorer.Assessment(
                100, AdConfidenceScorer.Level.HIGH, "test-only assessment");
        assertFalse(SafetyPolicy.isSafeTarget(
                new Rect(760, 100, 900, 150), SCREEN_WIDTH, SCREEN_HEIGHT,
                true, true, true, "立即购买", "广告",
                false, 50, Collections.emptyList(), false, forged));
    }

    @Test public void precomputedHighScoreCannotBypassCriticalPageContext() {
        AdConfidenceScorer.Assessment forged = new AdConfidenceScorer.Assessment(
                100, AdConfidenceScorer.Level.HIGH, "test-only assessment");
        assertFalse(SafetyPolicy.isSafeTarget(
                new Rect(760, 100, 900, 150), SCREEN_WIDTH, SCREEN_HEIGHT,
                true, true, true, "跳过广告", "广告 · 确认购买",
                false, 50, Collections.emptyList(), false, forged));
    }

    @Test public void precomputedHighScoreCannotBypassScreenGeometry() {
        AdConfidenceScorer.Assessment forged = new AdConfidenceScorer.Assessment(
                100, AdConfidenceScorer.Level.HIGH, "test-only assessment");
        assertFalse(SafetyPolicy.isSafeTarget(
                new Rect(760, 2350, 900, 2390), SCREEN_WIDTH, SCREEN_HEIGHT,
                true, true, true, "跳过广告", "正在播放广告",
                false, 50, Collections.emptyList(), false, forged));
    }
}
