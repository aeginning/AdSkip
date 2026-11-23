package com.aeginning.adskip.engine;

import java.util.Collections;
import java.util.List;

/** Pure, deterministic confidence model. It never authorizes risky controls. */
public final class AdConfidenceScorer {
    public enum Level { HIGH, REVIEW, LOW, REJECTED }

    public static final class Assessment {
        public final int score;
        public final Level level;
        public final String reason;

        public Assessment(int score, Level level, String reason) {
            this.score = score;
            this.level = level;
            this.reason = reason;
        }
    }

    private AdConfidenceScorer() { }

    public static Assessment assess(String label, String screenText, boolean clickable,
                                    boolean enabled, boolean visible, int targetWidth,
                                    int targetHeight, int screenWidth, int screenHeight) {
        return assess(label, screenText, clickable, enabled, visible, targetWidth,
                targetHeight, screenWidth, screenHeight, false);
    }

    public static Assessment assess(String label, String screenText, boolean clickable,
                                    boolean enabled, boolean visible, int targetWidth,
                                    int targetHeight, int screenWidth, int screenHeight,
                                    boolean allowCountdownSkip) {
        return assess(label, screenText, clickable, enabled, visible, targetWidth, targetHeight,
                screenWidth, screenHeight, allowCountdownSkip, 75);
    }

    public static Assessment assess(String label, String screenText, boolean clickable,
                                    boolean enabled, boolean visible, int targetWidth,
                                    int targetHeight, int screenWidth, int screenHeight,
                                    boolean allowCountdownSkip, int minimumScore) {
        return assess(label, screenText, clickable, enabled, visible, targetWidth, targetHeight,
                screenWidth, screenHeight, allowCountdownSkip, minimumScore, Collections.emptyList());
    }

    public static Assessment assess(String label, String screenText, boolean clickable,
                                    boolean enabled, boolean visible, int targetWidth,
                                    int targetHeight, int screenWidth, int screenHeight,
                                    boolean allowCountdownSkip, int minimumScore,
                                    List<String> customSkipWords) {
        return assess(label, screenText, clickable, enabled, visible, targetWidth, targetHeight,
                screenWidth, screenHeight, allowCountdownSkip, minimumScore,
                customSkipWords, false);
    }

    public static Assessment assess(String label, String screenText, boolean clickable,
                                    boolean enabled, boolean visible, int targetWidth,
                                    int targetHeight, int screenWidth, int screenHeight,
                                    boolean allowCountdownSkip, int minimumScore,
                                    List<String> customSkipWords, boolean allowTopRightSkipFallback) {
        String candidate = normalize(label);
        String context = normalize(screenText);
        if (candidate.isEmpty()) return result(0, "空标签");
        if (TextMatcher.isNegativeSkipNormalized(candidate)) return result(0, "目标明确表示不可跳过");
        if (TextMatcher.isDangerousNormalized(candidate)) return result(0, "目标包含敏感操作词");
        boolean closeGlyph = TextMatcher.isCloseGlyphNormalized(candidate);
        boolean countdownSkipLabel = TextMatcher.isCountdownSkipLabelNormalized(candidate);
        boolean adContext = TextMatcher.hasAdContextNormalized(context);
        boolean allowedCountdownTarget = allowCountdownSkip && countdownSkipLabel && adContext;
        boolean allowedCloseGlyph = closeGlyph && adContext;
        if ((TextMatcher.isCountdownNormalized(candidate) || TextMatcher.isCountdownNormalized(context))
                && !allowedCountdownTarget && !allowedCloseGlyph) {
            return result(0, "页面仍处于广告倒计时，倒计时点击未启用或目标不明确");
        }
        if (!clickable || !enabled || !visible) return result(0, "目标不可点击或不可见");
        int minimumWidth = closeGlyph ? 12 : 24;
        int minimumHeight = closeGlyph ? 12 : 18;
        if (screenWidth <= 0 || screenHeight <= 0
                || targetWidth < minimumWidth || targetHeight < minimumHeight
                || targetWidth > screenWidth * (closeGlyph ? 0.22f : 0.65f)
                || targetHeight > screenHeight * (closeGlyph ? 0.16f : 0.25f)) {
            return result(0, "目标尺寸不合理");
        }

        int score;
        if (closeGlyph) {
            if (!TextMatcher.hasAdContextNormalized(context)) return result(0, "X 关闭符号缺少广告上下文");
            score = 70;
        } else if (allowedCountdownTarget) {
            score = 70;
        } else if (TextMatcher.isExplicitSkipAdNormalized(candidate)) {
            score = 70;
        } else if (TextMatcher.isExplicitCloseAdNormalized(candidate)) {
            score = 66;
        } else if (allowTopRightSkipFallback && !adContext
                && TextMatcher.isGenericSkipLabel(candidate)) {
            // Layout was independently checked by SkipLayoutPolicy in the caller.
            // This remains below the default score floor and requires extreme mode.
            score = 54;
        } else if (TextMatcher.isCustomSkipPhraseNormalized(candidate, customSkipWords)) {
            if (!adContext) return result(0, "自定义跳过词缺少广告上下文");
            // Custom terms can enter review, never the highest-confidence built-in tier.
            score = 54;
        } else if (containsAny(candidate, "跳过", "skip")) {
            if (!TextMatcher.hasAdContextNormalized(context)) return result(0, "普通跳过词缺少广告上下文");
            score = 48;
        } else if (containsAny(candidate, "关闭", "close")) {
            if (!TextMatcher.hasAdContextNormalized(context)) return result(0, "普通关闭词缺少广告上下文");
            score = 45;
        } else {
            return result(0, "未匹配到允许的跳过词");
        }

        if (adContext) score += 15;
        if (clickable && enabled && visible) score += 8;
        score += 7; // Geometry is already bounded and considered plausible.
        if (TextMatcher.hasCriticalActionContextNormalized(context)) {
            return result(0, "页面包含高风险操作上下文");
        }
        score = Math.min(100, score);
        if (score < Math.max(50, Math.min(95, minimumScore))) {
            return result(0, "低于当前跳过按钮灵敏度门槛");
        }
        if (score >= 90) return result(score, "高置信度明确跳过按钮");
        if (score >= 75) return result(score, "需要二次识别确认");
        return result(score, "置信度不足，不自动点击");
    }

    /** Map user sensitivity (0=strict, 100=permissive) to a minimum score. */
    public static int minimumScoreForSensitivity(int sensitivity) {
        int bounded = Math.max(0, Math.min(100, sensitivity));
        return 90 - Math.round(bounded * 0.30f);
    }

    public static boolean canProceedAfterSecondObservation(Assessment first, Assessment second) {
        return canProceedAfterSecondObservation(first, second, 75);
    }

    public static boolean canProceedAfterSecondObservation(Assessment first, Assessment second,
                                                           int minimumScore) {
        int threshold = Math.max(50, Math.min(95, minimumScore));
        return first != null && second != null
                && first.score >= threshold && second.score >= threshold
                && Math.abs(first.score - second.score) <= 15
                && first.level != Level.REJECTED && second.level != Level.REJECTED;
    }

    private static Assessment result(int score, String reason) {
        Level level = score >= 90 ? Level.HIGH : score >= 75 ? Level.REVIEW
                : score > 0 ? Level.LOW : Level.REJECTED;
        return new Assessment(score, level, reason);
    }

    private static boolean containsAny(String value, String... tokens) {
        // All scorer-owned tokens are source-normalized ASCII or CJK literals.
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    public static String normalize(String value) {
        return TextMatcher.normalize(value);
    }
}
