package com.aeginning.adskip.engine;

import java.util.Locale;
import java.text.Normalizer;

public final class TextMatcher {
    private TextMatcher() { }

    private static final String[] NEGATIVE_SKIP = {
            "don't skip", "don’t skip", "dont skip", "do not skip", "never skip",
            "cannot skip", "can't skip", "can not skip", "not skippable",
            "skip unavailable", "skip disabled", "no skip",
            "不要跳过", "不应跳过", "不可跳过", "不能跳过", "无法跳过",
            "禁止跳过", "无需跳过", "不允许跳过", "不支持跳过",
            "暂不可跳过", "尚不可跳过", "当前广告不可跳过", "此广告不可跳过",
            "不可跳过此广告", "跳过广告不可用", "跳过按钮不可用", "广告暂不可跳过",
            "skip not available", "skip unavailable", "ad is not skippable",
            "this ad is not skippable", "ad cannot be skipped", "skip button disabled",
            "広告をスキップできません", "広告はスキップできません",
            "광고를 건너뛸 수 없습니다"
    };
    private static final String[] AD_CONTEXT = {
            "广告", "赞助内容", "推广内容", "广告倒计时", "广告播放中",
            "赞助广告", "推广广告", "商业推广", "sponsored", "sponsored content",
            "promoted content", "advertisement", "advertising", "ad content", "广告", "広告", "광고", "秒后跳过"
    };

    private static final String[] EXPLICIT_SKIP_AD = {
            "跳过广告", "跳过此广告", "跳过当前广告", "跳过视频广告",
            "跳过当前视频广告", "跳过开屏广告", "跳过片头广告", "广告跳过",
            "skip ad", "skip ads", "skip this ad", "skip current ad",
            "skip video ad", "skip this video ad", "skip current video ad", "skip current video advertisement", "skip advertisement",
            "skip sponsored ad", "skip sponsored content",
            "広告をスキップ", "광고 건너뛰기"
    };

    private static final String[] EXPLICIT_CLOSE_AD = {
            "关闭广告", "关闭此广告", "关闭当前广告", "关闭视频广告",
            "关闭推广", "关闭赞助内容", "关掉广告",
            "close ad", "close this ad", "close current ad", "close video ad",
            "close advertisement", "dismiss ad", "dismiss this ad",
            "広告を閉じる", "광고 닫기"
    };
    private static final String[] DANGEROUS = {
            "购买", "立即购买", "支付", "付款", "订阅", "开通会员", "立即开通", "下载", "立即下载",
            "安装", "允许", "授权", "同意", "确认", "确定", "提交", "领取", "登录", "注册",
            "继续", "下一步", "打开", "启用", "加入", "免费试用", "立即体验", "开始使用",
            "grant", "allow", "buy", "purchase", "subscribe", "install", "download", "pay",
            "checkout", "confirm", "continue", "accept", "sign in", "log in", "sign up",
            "start free trial", "try now", "get started", "join now", "claim", "redeem", "activate",
            "立即报名", "立即查看", "查看详情", "前往查看", "去看看", "加入购物车",
            "领取优惠券", "立即领取", "免费领取", "领取奖励", "确认收货", "提交订单",
            "下单", "去支付", "允许访问", "允许一次", "始终允许", "仅在使用中允许",
            "購入", "支払い", "インストール", "許可", "登録", "申し込む",
            "구매", "결제", "설치", "허용", "가입", "구독"
    };

    public static boolean isExplicitSkipAd(String text) {
        return containsAny(normalize(text), EXPLICIT_SKIP_AD);
    }

    public static boolean isExplicitCloseAd(String text) {
        return containsAny(normalize(text), EXPLICIT_CLOSE_AD);
    }

    /** Exact visual close glyphs commonly used by splash ads. Geometry and ad context
     * are verified separately by SafetyPolicy before any action is allowed. */
    public static boolean isCloseGlyph(String text) {
        String value = normalize(text);
        return value.equals("x") || value.equals("×") || value.equals("✕")
                || value.equals("✖") || value.equals("✗") || value.equals("✘");
    }


    private static boolean containsAny(String value, String[] tokens) {
        for (String token : tokens) {
            if (value.contains(normalize(token))) return true;
        }
        return false;
    }

    public static boolean hasAdContext(String screenText) {
        String text = normalize(screenText);
        for (String token : AD_CONTEXT) if (text.contains(normalize(token))) return true;
        return text.matches(".*\\bad\\b.*");
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
                // Common splash-ad countdown labels: “跳过 3”, “跳过3秒”, “Skip 3”, “Skip 3s”.
                || value.matches(".*(?:跳过|skip)\\s*\\d+\\s*(?:秒|s|sec|secs|second|seconds)?(?:\\s|$|[,，。.!！?？]).*")
                || value.matches(".*\\d+\\s*秒后(?:可)?跳过.*")
                || value.matches(".*あと\\s*\\d+\\s*秒.*")
                || value.matches(".*\\d+\\s*秒後にスキップ.*")
                || value.matches(".*\\d+\\s*초\\s*후\\s*건너뛰기.*")
                || (hasAdContext(value) && value.matches(".*\\d+\\s*(?:s|sec|seconds|秒|초).*"));
    }

    /** True only for a skip control whose visible label includes its countdown number. */
    public static boolean isCountdownSkipLabel(String text) {
        String value = normalize(text);
        return value.matches(".*(?:跳过|skip)\\s*\\d+\\s*(?:秒|s|sec|secs|second|seconds)?(?:\\s|$|[,，。.!！?？]).*");
    }

    /**
     * Similarity used only to compare two observations of the same candidate.
     * Countdown digits are intentionally ignored when both labels are skip countdowns.
     */
    public static int similarityPercent(String first, String second) {
        String a = normalize(first);
        String b = normalize(second);
        if (a.isEmpty() || b.isEmpty()) return a.equals(b) ? 100 : 0;

        if (isCountdownSkipLabel(a) && isCountdownSkipLabel(b)) {
            boolean sameLanguage = (a.contains("跳过") && b.contains("跳过"))
                    || (a.contains("skip") && b.contains("skip"));
            if (sameLanguage) return 100;
        }

        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1,
                        previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        int distance = previous[b.length()];
        int length = Math.max(a.length(), b.length());
        return Math.max(0, Math.min(100, Math.round((1f - distance / (float) length) * 100f)));
    }

    public static String normalize(String input) {
        if (input == null) return "";
        String compatibility = Normalizer.normalize(input, Normalizer.Form.NFKC);
        // Remove zero-width and Unicode formatting controls often injected by ad SDK labels.
        compatibility = compatibility.replaceAll("[\\u200B-\\u200D\\u2060\\uFEFF]", "")
                .replaceAll("\\p{Cf}", "");
        return compatibility.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\u00a0]+", " ").trim();
    }
}
