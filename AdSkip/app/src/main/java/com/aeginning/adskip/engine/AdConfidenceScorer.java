package com.aeginning.adskip.engine;


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
        String candidate = normalize(label);
        String context = normalize(screenText);
        if (candidate.isEmpty()) return result(0, "空标签");
        if (TextMatcher.isNegativeSkip(candidate)) return result(0, "目标明确表示不可跳过");
        if (TextMatcher.isDangerous(candidate)) return result(0, "目标包含敏感操作词");
        if (TextMatcher.isCountdown(candidate) || TextMatcher.isCountdown(context)) {
            return result(0, "页面仍处于广告倒计时");
        }
        if (!clickable || !enabled || !visible) return result(0, "目标不可点击或不可见");
        if (screenWidth <= 0 || screenHeight <= 0 || targetWidth < 24 || targetHeight < 18
                || targetWidth > screenWidth * 0.65f || targetHeight > screenHeight * 0.25f) {
            return result(0, "目标尺寸不合理");
        }

        int score;
        if (containsAny(candidate, "跳过广告", "跳过视频广告", "skip ad", "skip ads", "skip this ad", "skip advertisement")) {
            score = 70;
        } else if (containsAny(candidate, "关闭广告", "关闭推广", "close ad", "close advertisement")) {
            score = 66;
        } else if (containsAny(candidate, "跳过", "skip")) {
            if (!TextMatcher.hasAdContext(context)) return result(0, "普通跳过词缺少广告上下文");
            score = 48;
        } else if (containsAny(candidate, "关闭", "close")) {
            if (!TextMatcher.hasAdContext(context)) return result(0, "普通关闭词缺少广告上下文");
            score = 45;
        } else {
            return result(0, "未匹配到允许的跳过词");
        }

        if (TextMatcher.hasAdContext(context)) score += 15;
        if (clickable && enabled && visible) score += 8;
        score += 7; // Geometry is already bounded and considered plausible.
        if (containsAny(context, "付款确认", "确认购买", "支付密码", "授权", "权限申请", "支付",
                "购买", "订阅", "下载", "安装", "登录", "注册", "permission", "allow access",
                "install this app", "download now", "checkout", "payment", "purchase", "subscribe",
                "sign in", "sign up", "register")) {
            return result(0, "页面包含高风险操作上下文");
        }
        score = Math.min(100, score);
        if (score >= 90) return result(score, "高置信度明确跳过按钮");
        if (score >= 75) return result(score, "需要二次识别确认");
        return result(score, "置信度不足，不自动点击");
    }

    public static boolean canProceedAfterSecondObservation(Assessment first, Assessment second) {
        return first != null && second != null
                && first.score >= 75 && second.score >= 75
                && Math.abs(first.score - second.score) <= 15
                && first.level != Level.REJECTED && second.level != Level.REJECTED;
    }

    private static Assessment result(int score, String reason) {
        Level level = score >= 90 ? Level.HIGH : score >= 75 ? Level.REVIEW
                : score > 0 ? Level.LOW : Level.REJECTED;
        return new Assessment(score, level, reason);
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(normalize(token))) return true;
        return false;
    }

    public static String normalize(String value) {
        return TextMatcher.normalize(value);
    }
}
