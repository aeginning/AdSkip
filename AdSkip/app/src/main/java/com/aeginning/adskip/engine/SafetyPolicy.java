package com.aeginning.adskip.engine;

import android.graphics.Rect;

/** Conservative final gate. Scoring is advisory; this policy remains authoritative. */
public final class SafetyPolicy {
    private SafetyPolicy() { }

    public static boolean isSafeTarget(Rect bounds, int screenWidth, int screenHeight,
                                       boolean clickable, boolean enabled, boolean visible,
                                       String candidateText, String surroundingText) {
        if (bounds == null || screenWidth <= 0 || screenHeight <= 0) return false;
        if (bounds.isEmpty() || bounds.left < 0 || bounds.top < 0
                || bounds.right > screenWidth || bounds.bottom > screenHeight) return false;
        if (bounds.width() < 24 || bounds.height() < 18
                || bounds.width() > screenWidth * 0.65f
                || bounds.height() > screenHeight * 0.25f) return false;
        // Keep coordinate actions away from status/navigation gesture regions.
        if (bounds.top < screenHeight * 0.025f || bounds.bottom > screenHeight * 0.96f) return false;

        AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(
                candidateText, surroundingText, clickable, enabled, visible,
                bounds.width(), bounds.height(), screenWidth, screenHeight);
        return assessment.score >= 75
                && !TextMatcher.isDangerous(candidateText)
                && !isCriticalScreenContext(surroundingText);
    }

    private static boolean isCriticalScreenContext(String text) {
        String value = AdConfidenceScorer.normalize(text);
        String[] critical = {"付款确认", "确认购买", "支付密码", "授权", "权限申请", "支付",
                "购买", "订阅", "下载", "安装", "登录", "注册", "permission", "allow access",
                "install this app", "download now", "checkout", "payment", "purchase", "subscribe",
                "sign in", "sign up", "register"};
        for (String token : critical) if (value.contains(token)) return true;
        return false;
    }
}
