package com.aeginning.adskip.engine;

import java.util.Locale;

public final class TextMatcher {
    private TextMatcher() { }

    private static final String[] STRONG_SKIP = {
            "跳过广告", "跳过视频广告", "skip ad", "skip ads", "skip this ad", "skip advertisement"
    };
    private static final String[] NORMAL_SKIP = {"跳过", "skip"};
    private static final String[] NEGATIVE_SKIP = {
            "don't skip", "don’t skip", "dont skip", "do not skip", "never skip",
            "cannot skip", "can't skip", "can not skip", "not skippable",
            "skip unavailable", "skip disabled", "no skip",
            "不要跳过", "不应跳过", "不可跳过", "不能跳过", "无法跳过",
            "禁止跳过", "无需跳过", "不允许跳过", "不支持跳过"
    };
    private static final String[] CLOSE_AD = {"关闭广告", "关闭推广", "close ad", "close advertisement"};
    private static final String[] AD_CONTEXT = {
            "广告", "赞助内容", "推广内容", "广告倒计时", "sponsored", "advertisement", "advertising", "秒后跳过"
    };
    private static final String[] DANGEROUS = {
            "购买", "立即购买", "支付", "付款", "订阅", "开通会员", "立即开通", "下载", "立即下载",
            "安装", "允许", "授权", "同意", "确认", "确定", "提交", "领取", "登录", "注册",
            "继续", "下一步", "打开", "启用", "加入", "免费试用", "立即体验", "开始使用",
            "grant", "allow", "buy", "purchase", "subscribe", "install", "download", "pay",
            "checkout", "confirm", "continue", "accept", "sign in", "log in", "sign up",
            "start free trial", "try now", "get started", "join now", "claim", "redeem", "activate"
    };

    public static boolean hasAdContext(String screenText) {
        String text = normalize(screenText);
        for (String token : AD_CONTEXT) if (text.contains(normalize(token))) return true;
        return text.matches(".*\\bad\\b.*");
    }

    public static int score(String candidateText, boolean adContext) {
        String text = normalize(candidateText);
        if (text.isEmpty() || isDangerous(text) || isNegativeSkip(text) || isCountdown(text)) return 0;
        for (String token : STRONG_SKIP) if (text.equals(normalize(token)) || text.contains(normalize(token))) return 100;
        for (String token : CLOSE_AD) if (text.equals(normalize(token)) || text.contains(normalize(token))) return 90;
        for (String token : NORMAL_SKIP) {
            if (adContext && (text.equals(normalize(token)) || text.contains(normalize(token)))) return 85;
        }
        // A generic close label is only accepted when the surrounding screen is clearly an ad.
        if (adContext && (text.equals("关闭") || text.equals("close"))) return 65;
        return 0;
    }

    /** Reject negated or unavailable skip labels before substring matching can authorize them. */
    public static boolean isNegativeSkip(String text) {
        String value = normalize(text);
        for (String token : NEGATIVE_SKIP) {
            if (value.contains(normalize(token))) return true;
        }
        return false;
    }

    public static boolean isDangerous(String text) {
        String value = normalize(text);
        for (String token : DANGEROUS) if (value.contains(normalize(token))) return true;
        return false;
    }

    public static boolean isCountdown(String text) {
        String value = normalize(text);
        return value.matches(".*\\bskip\\s+in\\s+\\d+(?:\\s*(?:s|sec|second|seconds))?.*")
                || value.matches(".*\\d+\\s*(?:秒|s|sec|second|seconds)\\s*后?(?:可)?\\s*跳过.*")
                || value.matches(".*\\d+\\s*(?:s|sec|seconds)\\s+(?:to\\s+)?skip.*")
                || value.matches(".*skip\\s+after\\s+\\d+\\s*(?:s|sec|seconds).*")
                || (hasAdContext(value) && value.matches(".*\\d+\\s*(?:s|sec|seconds|秒).*"));
    }

    public static String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00a0]+", " ").trim();
    }
}
