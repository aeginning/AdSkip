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
        if (bounds == null || screenWidth <= 0 || screenHeight <= 0) return false;
        if (bounds.isEmpty() || bounds.left < 0 || bounds.top < 0
                || bounds.right > screenWidth || bounds.bottom > screenHeight) return false;
        String normalizedCandidate = TextMatcher.normalize(candidateText);
        String normalizedContext = TextMatcher.normalize(surroundingText);
        boolean closeGlyphCandidate = TextMatcher.isCloseGlyphNormalized(normalizedCandidate);
        int minWidth = closeGlyphCandidate ? 12 : 24;
        int minHeight = closeGlyphCandidate ? 12 : 18;
        if (bounds.width() < minWidth || bounds.height() < minHeight
                || bounds.width() > screenWidth * (closeGlyphCandidate ? 0.22f : 0.65f)
                || bounds.height() > screenHeight * (closeGlyphCandidate ? 0.16f : 0.25f)) return false;
        // Keep coordinate actions away from system gesture regions. An explicitly
        // enabled, OCR-confirmed skip countdown may live in the top-right splash-ad slot.
        boolean adContext = TextMatcher.hasAdContextNormalized(normalizedContext);
        boolean topRightCountdownControl = allowCountdownSkip
                && TextMatcher.isCountdownSkipLabelNormalized(normalizedCandidate)
                && adContext
                && bounds.left >= screenWidth * 0.60f
                && bounds.top >= screenHeight * 0.01f
                && bounds.bottom <= screenHeight * 0.12f;
        // Allow common OEM/ad-SDK close-button layouts beyond the very narrow
        // top-right corner, while retaining a strict ad-context and upper-right gate.
        boolean topRightSkipPill = allowTopRightSkipFallback
                && !adContext
                && TextMatcher.isGenericSkipLabel(candidateText)
                && SkipLayoutPolicy.isTopRightSkipPill(
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                        screenWidth, screenHeight);
        boolean topRightCloseGlyph = closeGlyphCandidate
                && adContext
                && bounds.left >= screenWidth * 0.55f
                && bounds.centerX() >= screenWidth * 0.74f
                && bounds.right >= screenWidth * 0.70f
                && bounds.top >= screenHeight * 0.005f
                && bounds.centerY() <= screenHeight * 0.26f
                && bounds.bottom <= screenHeight * 0.36f
                && bounds.width() <= screenWidth * 0.22f
                && bounds.height() <= screenHeight * 0.16f;
        if ((bounds.top < screenHeight * 0.025f
                    && !topRightCountdownControl && !topRightCloseGlyph && !topRightSkipPill)
                || bounds.bottom > screenHeight * 0.96f) return false;
        // A lone X is accepted only as a small, top-right ad close affordance.
        if (closeGlyphCandidate && !topRightCloseGlyph) return false;

        AdConfidenceScorer.Assessment assessment = precomputedAssessment != null
                ? precomputedAssessment
                : AdConfidenceScorer.assess(candidateText, surroundingText, clickable, enabled, visible,
                        bounds.width(), bounds.height(), screenWidth, screenHeight,
                        allowCountdownSkip, minimumScore, customSkipWords, topRightSkipPill);
        return assessment.score >= Math.max(50, Math.min(95, minimumScore))
                && !TextMatcher.isDangerousNormalized(normalizedCandidate)
                && !TextMatcher.hasCriticalActionContextNormalized(normalizedContext);
    }
}
