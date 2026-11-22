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
        if (bounds == null || screenWidth <= 0 || screenHeight <= 0) return false;
        if (bounds.isEmpty() || bounds.left < 0 || bounds.top < 0
                || bounds.right > screenWidth || bounds.bottom > screenHeight) return false;
        boolean closeGlyphCandidate = TextMatcher.isCloseGlyph(candidateText);
        int minWidth = closeGlyphCandidate ? 12 : 24;
        int minHeight = closeGlyphCandidate ? 12 : 18;
        if (bounds.width() < minWidth || bounds.height() < minHeight
                || bounds.width() > screenWidth * (closeGlyphCandidate ? 0.22f : 0.65f)
                || bounds.height() > screenHeight * (closeGlyphCandidate ? 0.16f : 0.25f)) return false;
        // Keep coordinate actions away from system gesture regions. An explicitly
        // enabled, OCR-confirmed skip countdown may live in the top-right splash-ad slot.
        boolean adContext = TextMatcher.hasAdContext(surroundingText);
        boolean topRightCountdownControl = allowCountdownSkip
                && TextMatcher.isCountdownSkipLabel(candidateText)
                && adContext
                && bounds.left >= screenWidth * 0.60f
                && bounds.top >= screenHeight * 0.01f
                && bounds.bottom <= screenHeight * 0.12f;
        // Allow common OEM/ad-SDK close-button layouts beyond the very narrow
        // top-right corner, while retaining a strict ad-context and upper-right gate.
        boolean topRightCloseGlyph = TextMatcher.isCloseGlyph(candidateText)
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
                    && !topRightCountdownControl && !topRightCloseGlyph)
                || bounds.bottom > screenHeight * 0.96f) return false;
        // A lone X is accepted only as a small, top-right ad close affordance.
        if (TextMatcher.isCloseGlyph(candidateText) && !topRightCloseGlyph) return false;

        AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(
                candidateText, surroundingText, clickable, enabled, visible,
                bounds.width(), bounds.height(), screenWidth, screenHeight,
                allowCountdownSkip, minimumScore, customSkipWords);
        return assessment.score >= Math.max(50, Math.min(95, minimumScore))
                && !TextMatcher.isDangerous(candidateText)
                && !TextMatcher.hasCriticalActionContext(surroundingText);
    }
}
