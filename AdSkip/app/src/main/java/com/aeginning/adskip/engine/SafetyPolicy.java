package com.aeginning.adskip.engine;

import android.graphics.Rect;

import java.util.Collections;
import java.util.List;

/** Conservative final gate. Scoring is advisory; this policy remains authoritative. */
public final class SafetyPolicy {
    private SafetyPolicy() { }

    public static boolean isSafeTarget(Rect bounds, int screenWidth, int screenHeight,
                                       boolean clickable, boolean enabled, boolean visible,
                                       String candidateText, String surroundingText) {
        return isSafeTarget(bounds, screenWidth, screenHeight, clickable, enabled, visible,
                candidateText, surroundingText, false);
    }

    public static boolean isSafeTarget(Rect bounds, int screenWidth, int screenHeight,
                                       boolean clickable, boolean enabled, boolean visible,
                                       String candidateText, String surroundingText,
                                       boolean allowCountdownSkip) {
        return isSafeTarget(bounds, screenWidth, screenHeight, clickable, enabled, visible,
                candidateText, surroundingText, allowCountdownSkip, 75);
    }

    public static boolean isSafeTarget(Rect bounds, int screenWidth, int screenHeight,
                                       boolean clickable, boolean enabled, boolean visible,
                                       String candidateText, String surroundingText,
                                       boolean allowCountdownSkip, int minimumScore) {
        return isSafeTarget(bounds, screenWidth, screenHeight, clickable, enabled, visible,
                candidateText, surroundingText, allowCountdownSkip, minimumScore,
                Collections.emptyList());
    }

    public static boolean isSafeTarget(Rect bounds, int screenWidth, int screenHeight,
                                       boolean clickable, boolean enabled, boolean visible,
                                       String candidateText, String surroundingText,
                                       boolean allowCountdownSkip, int minimumScore,
                                       List<String> customSkipWords) {
        return isSafeTarget(bounds, screenWidth, screenHeight, clickable, enabled, visible,
                candidateText, surroundingText, allowCountdownSkip, minimumScore,
                customSkipWords, false);
    }

    public static boolean isSafeTarget(Rect bounds, int screenWidth, int screenHeight,
                                       boolean clickable, boolean enabled, boolean visible,
                                       String candidateText, String surroundingText,
                                       boolean allowCountdownSkip, int minimumScore,
                                       List<String> customSkipWords, boolean allowTopRightSkipFallback) {
        return isSafeTarget(bounds, screenWidth, screenHeight, clickable, enabled, visible,
                candidateText, surroundingText, allowCountdownSkip, minimumScore,
                customSkipWords, allowTopRightSkipFallback, null);
    }

    /**
     * Reuses a score already computed for this exact candidate/context pair.
     * All final geometry and hard-risk checks still run here on every call.
     */
    public static boolean isSafeTarget(Rect bounds, int screenWidth, int screenHeight,
                                       boolean clickable, boolean enabled, boolean visible,
                                       String candidateText, String surroundingText,
                                       boolean allowCountdownSkip, int minimumScore,
                                       List<String> customSkipWords, boolean allowTopRightSkipFallback,
                                       AdConfidenceScorer.Assessment precomputedAssessment) {
        if (bounds == null) return false;
        return isSafeTargetBounds(bounds.left, bounds.top, bounds.right, bounds.bottom,
                screenWidth, screenHeight, clickable, enabled, visible, candidateText,
                surroundingText, allowCountdownSkip, minimumScore, customSkipWords,
                allowTopRightSkipFallback, precomputedAssessment);
    }

    /** Pure geometry entry point shared by production and deterministic JVM tests. */
    static boolean isSafeTargetBounds(int left, int top, int right, int bottom,
                                      int screenWidth, int screenHeight,
                                      boolean clickable, boolean enabled, boolean visible,
                                      String candidateText, String surroundingText,
                                      boolean allowCountdownSkip, int minimumScore,
                                      List<String> customSkipWords, boolean allowTopRightSkipFallback,
                                      AdConfidenceScorer.Assessment precomputedAssessment) {
        if (screenWidth <= 0 || screenHeight <= 0) return false;
        if (right <= left || bottom <= top || left < 0 || top < 0
                || right > screenWidth || bottom > screenHeight) return false;
        int boundsWidth = right - left;
        int boundsHeight = bottom - top;
        String normalizedCandidate = TextMatcher.normalize(candidateText);
        String normalizedContext = TextMatcher.normalize(surroundingText);
        boolean closeGlyphCandidate = TextMatcher.isCloseGlyphNormalized(normalizedCandidate);
        int minWidth = closeGlyphCandidate ? 12 : 24;
        int minHeight = closeGlyphCandidate ? 12 : 18;
        if (boundsWidth < minWidth || boundsHeight < minHeight
                || boundsWidth > screenWidth * (closeGlyphCandidate ? 0.22f : 0.65f)
                || boundsHeight > screenHeight * (closeGlyphCandidate ? 0.16f : 0.25f)) return false;
        // Keep coordinate actions away from system gesture regions. An explicitly
        // enabled, OCR-confirmed skip countdown may live in the top-right splash-ad slot.
        boolean adContext = TextMatcher.hasAdContextNormalized(normalizedContext);
        boolean topRightCountdownControl = allowCountdownSkip
                && TextMatcher.isCountdownSkipLabelNormalized(normalizedCandidate)
                && adContext
                && left >= screenWidth * 0.60f
                && top >= screenHeight * 0.01f
                && bottom <= screenHeight * 0.12f;
        // Allow common OEM/ad-SDK close-button layouts beyond the very narrow
        // top-right corner, while retaining a strict ad-context and upper-right gate.
        boolean topRightSkipPill = allowTopRightSkipFallback
                && !adContext
                && TextMatcher.isGenericSkipLabel(candidateText)
                && SkipLayoutPolicy.isTopRightSkipPill(
                        left, top, right, bottom, screenWidth, screenHeight);
        int centerX = left + boundsWidth / 2;
        int centerY = top + boundsHeight / 2;
        boolean topRightCloseGlyph = closeGlyphCandidate
                && adContext
                && left >= screenWidth * 0.55f
                && centerX >= screenWidth * 0.74f
                && right >= screenWidth * 0.70f
                && top >= screenHeight * 0.005f
                && centerY <= screenHeight * 0.26f
                && bottom <= screenHeight * 0.36f
                && boundsWidth <= screenWidth * 0.22f
                && boundsHeight <= screenHeight * 0.16f;
        if ((top < screenHeight * 0.025f
                    && !topRightCountdownControl && !topRightCloseGlyph && !topRightSkipPill)
                || bottom > screenHeight * 0.96f) return false;
        // A lone X is accepted only as a small, top-right ad close affordance.
        if (closeGlyphCandidate && !topRightCloseGlyph) return false;

        AdConfidenceScorer.Assessment assessment = precomputedAssessment != null
                ? precomputedAssessment
                : AdConfidenceScorer.assess(candidateText, surroundingText, clickable, enabled, visible,
                        boundsWidth, boundsHeight, screenWidth, screenHeight,
                        allowCountdownSkip, minimumScore, customSkipWords, topRightSkipPill);
        return assessment.score >= Math.max(50, Math.min(95, minimumScore))
                && !TextMatcher.isDangerousNormalized(normalizedCandidate)
                && !TextMatcher.hasCriticalActionContextNormalized(normalizedContext);
    }
}
