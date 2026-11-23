package com.aeginning.adskip.engine;

import java.util.Locale;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

public final class TextMatcher {
    private TextMatcher() { }

    private static final Pattern ZERO_WIDTH_PATTERN = Pattern.compile(
            "[\\u200B-\\u200D\\u2060\\uFEFF\\uFE0E\\uFE0F]");
    private static final Pattern FORMAT_CONTROL_PATTERN = Pattern.compile("\\p{Cf}");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\s\\u00a0]+");
    private static final Pattern CLOSE_GLYPH_DECORATION_PATTERN = Pattern.compile("[\\p{Punct}\\s]");
    private static final Pattern AD_WORD_PATTERN = Pattern.compile("\\bad\\b");
    private static final String COUNTDOWN_UNITS =
            "(?:seconds?|secs?|s|segundos?|seg|seconde?s?|sekunden?|sekund(?:e|en)?|"
                    + "секунд(?:а|ы|у)?|ثواني|ثانية|秒|초)";
    private static final Pattern COUNTDOWN_CLOCK_PATTERN =
            Pattern.compile("\\d{1,2}\\s*[:：]\\s*\\d{1,2}");
    private static final Pattern COUNTDOWN_PREFIX_PATTERN = Pattern.compile(
            "(?i)(skip(?:\\s+(?:in|after))?|跳过|スキップ|건너뛰기"
                    + "|omitir(?:\\s+en)?|saltar(?:\\s+en)?|ignorer(?:\\s+dans)?"
                    + "|überspringen(?:\\s+nach)?|пропустить(?:\\s+через)?|تخطي(?:\\s+بعد)?)"
                    + "\\s*\\d+\\s*" + COUNTDOWN_UNITS + "?");
    private static final Pattern COUNTDOWN_NUMBER_SUFFIX_PATTERN =
            Pattern.compile("\\d+\\s*" + COUNTDOWN_UNITS);
    private static final Pattern COUNTDOWN_WITH_DURATION_PATTERN =
            Pattern.compile(".*\\d+\\s*(?:s|sec|seconds|秒|초).*");

    private static final Pattern[] COUNTDOWN_PATTERNS = {
            Pattern.compile(".*\\bskip\\s+(?:in|after)\\s+\\d+.*"),
            Pattern.compile(".*skip\\s+(?:in|after)\\s+\\d{1,2}[:：]\\d{2}.*"),
            Pattern.compile(".*\\b\\d+\\s*(?:s|sec|secs|second|seconds)\\s+(?:to\\s+)?skip.*"),
            Pattern.compile(".*\\d+\\s*(?:秒|s|sec|second|seconds)\\s*后?(?:可)?\\s*跳过.*"),
            Pattern.compile(".*(?:跳过|skip)\\s*\\d+\\s*(?:秒|s|sec|secs|second|seconds)?(?:\\s|$|[,，。.!！?？]).*"),
            Pattern.compile(".*\\d+\\s*秒后(?:可)?跳过.*"),
            Pattern.compile(".*(?:あと\\s*\\d+\\s*秒|\\d+\\s*秒後にスキップ|スキップ\\s*\\d+).*"),
            Pattern.compile(".*(?:\\d+\\s*초\\s*후\\s*건너뛰기|건너뛰기\\s*\\d+).*"),
            Pattern.compile(".*(?:omitir|saltar|ignorer|überspringen|пропустить|تخطي)\\s*(?:en|dans|nach|через|after|بعد)?\\s*\\d+.*")
    };

    private static final Pattern[] COUNTDOWN_SKIP_LABEL_PATTERNS = {
            Pattern.compile(".*(?:跳过|skip(?:\\s+(?:in|after))?|スキップ|건너뛰기|omitir|saltar|ignorer|überspringen|пропустить|تخطي)(?:\\s+(?:en|dans|nach|через|بعد))?\\s*\\d+.*"),
            Pattern.compile(".*skip\\s+(?:in|after)\\s+\\d{1,2}[:：]\\d{2}.*"),
            Pattern.compile(".*\\bskip\\s+(?:in|after)\\s+\\d+.*"),
            Pattern.compile(".*\\d+\\s*(?:秒|s|sec|secs|second|seconds)\\s*(?:后|to)?\\s*(?:跳过|skip).*"),
            Pattern.compile(".*あと\\s*\\d+\\s*秒.*"),
            Pattern.compile(".*\\d+\\s*秒後にスキップ.*"),
            Pattern.compile(".*\\d+\\s*초\\s*후\\s*건너뛰기.*")
    };

    private static final String[] NEGATIVE_SKIP = {
            "don't skip", "don’t skip", "dont skip", "do not skip", "never skip",
            "cannot skip", "can't skip", "can not skip", "not skippable",
            "skip unavailable", "skip disabled", "no skip",
            "不要跳过", "不应跳过", "不可跳过", "不能跳过", "无法跳过",
            "禁止跳过", "无需跳过", "不允许跳过", "不支持跳过",
            "暂不可跳过", "尚不可跳过", "当前广告不可跳过", "此广告不可跳过",
            "不可跳过此广告", "跳过广告不可用", "跳过按钮不可用", "广告暂不可跳过",
            "skip not available", "ad is not skippable",
            "this ad is not skippable", "ad cannot be skipped", "skip button disabled",
            "広告をスキップできません", "広告はスキップできません",
            "광고를 건너뛸 수 없습니다"
    };
    private static final String[] AD_CONTEXT = {
            "广告", "赞助内容", "推广内容", "广告倒计时", "广告播放中",
            "赞助广告", "推广广告", "商业推广", "sponsored", "sponsored content",
            "promoted content", "advertisement", "advertising", "ad content", "広告", "광고", "秒后跳过"
    };

    private static final String[] EXPLICIT_SKIP_AD = {
            "跳过广告", "跳过此广告", "跳过当前广告", "跳过视频广告",
            "跳过当前视频广告", "跳过开屏广告", "跳过片头广告", "广告跳过",
            "skip ad", "skip ads", "skip this ad", "skip current ad",
            "skip video ad", "skip this video ad", "skip current video ad", "skip current video advertisement", "skip advertisement",
            "skip sponsored ad", "skip sponsored content",
            "広告をスキップ", "광고 건너뛰기"
    };

    /** Shared high-risk screen context used by both scoring and the final click gate. */
    private static final String[] CRITICAL_ACTION_CONTEXT = {
            "付款确认", "确认购买", "支付密码", "授权", "权限申请", "支付",
            "购买", "订阅", "下载", "安装", "登录", "注册", "permission", "allow access",
            "install this app", "download now", "checkout", "payment", "purchase", "subscribe",
            "sign in", "sign up", "register"
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

    /** Read-only default vocabulary for the settings UI. */
    public static String[] getDefaultExplicitSkipAdWords() { return EXPLICIT_SKIP_AD.clone(); }

    public static String[] getDefaultExplicitCloseAdWords() { return EXPLICIT_CLOSE_AD.clone(); }

    public static String[] getDefaultGenericSkipWords() { return new String[]{"跳过", "skip"}; }

    public static String[] getDefaultGenericCloseWords() { return new String[]{"关闭", "close"}; }

    public static String[] getDefaultCloseGlyphWords() {
        return new String[]{"X", "×", "✕", "✖", "✗", "✘", "╳", "⨯", "☓", "❎", "❌"};
    }

    /** True when a proposed custom term is already covered by built-in matching. */
    public static boolean isDefaultSkipTerm(String text) {
        String value = normalize(text);
        return value.equals("跳过") || value.equals("skip")
                || value.equals("关闭") || value.equals("close")
                || containsAny(value, EXPLICIT_SKIP_AD)
                || containsAny(value, EXPLICIT_CLOSE_AD)
                || isCloseGlyph(value);
    }

    /** Custom phrases are positive hints only; negative and dangerous labels can never match. */
    public static boolean isCustomSkipPhrase(String text, List<String> customWords) {
        return isCustomSkipPhraseNormalized(normalize(text), customWords);
    }

    static boolean isCustomSkipPhraseNormalized(String value, List<String> customWords) {
        if (value.isEmpty() || isNegativeSkipNormalized(value)
                || isDangerousNormalized(value) || isCloseGlyphNormalized(value)) return false;
        return SkipWordLibrary.matches(value, customWords);
    }

    /** Exact generic skip labels only; unlike substring matching this cannot accept CTA sentences. */
    public static boolean isGenericSkipLabel(String text) {
        String value = normalize(text);
        return value.equals("跳过") || value.equals("略过") || value.equals("跳過")
                || value.equals("skip") || value.equals("スキップ")
                || value.equals("건너뛰기") || value.equals("omitir")
                || value.equals("saltar") || value.equals("ignorer")
                || value.equals("überspringen") || value.equals("пропустить")
                || value.equals("تخطي");
    }

    public static boolean isExplicitSkipAd(String text) {
        return isExplicitSkipAdNormalized(normalize(text));
    }

    static boolean isExplicitSkipAdNormalized(String value) {
        return containsAny(value, EXPLICIT_SKIP_AD);
    }

    public static boolean isExplicitCloseAd(String text) {
        return isExplicitCloseAdNormalized(normalize(text));
    }

    static boolean isExplicitCloseAdNormalized(String value) {
        return containsAny(value, EXPLICIT_CLOSE_AD);
    }

    /** Exact visual close glyphs commonly used by splash ads. Geometry and ad context
     * are verified separately by SafetyPolicy before any action is allowed. */
    public static boolean isCloseGlyph(String text) {
        return isCloseGlyphNormalized(normalize(text));
    }

    static boolean isCloseGlyphNormalized(String value) {
        String compact = CLOSE_GLYPH_DECORATION_PATTERN.matcher(value).replaceAll("");
        return compact.equals("x") || compact.equals("×") || compact.equals("✕")
                || compact.equals("✖") || compact.equals("✗") || compact.equals("✘")
                || compact.equals("╳") || compact.equals("⨯") || compact.equals("☓")
                || compact.equals("❎") || compact.equals("❌");
    }


    private static boolean containsAny(String value, String[] tokens) {
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    public static boolean hasAdContext(String screenText) {
        return hasAdContextNormalized(normalize(screenText));
    }

    static boolean hasAdContextNormalized(String text) {
        for (String token : AD_CONTEXT) if (text.contains(token)) return true;
        return AD_WORD_PATTERN.matcher(text).find();
    }


    /** Reject negated or unavailable skip labels before substring matching can authorize them. */
    public static boolean isNegativeSkip(String text) {
        return isNegativeSkipNormalized(normalize(text));
    }

    static boolean isNegativeSkipNormalized(String value) {
        for (String token : NEGATIVE_SKIP) {
            if (value.contains(token)) return true;
        }
        return false;
    }


    public static boolean isDangerous(String text) {
        return isDangerousNormalized(normalize(text));
    }

    static boolean isDangerousNormalized(String value) {
        for (String token : DANGEROUS) if (value.contains(token)) return true;
        return false;
    }


    /** Returns true when the surrounding screen contains any purchase, permission or account flow. */
    public static boolean hasCriticalActionContext(String text) {
        return hasCriticalActionContextNormalized(normalize(text));
    }

    static boolean hasCriticalActionContextNormalized(String value) {
        return containsAny(value, CRITICAL_ACTION_CONTEXT);
    }

    public static boolean isCountdown(String text) {
        return isCountdownNormalized(normalize(text));
    }

    static boolean isCountdownNormalized(String value) {
        for (Pattern pattern : COUNTDOWN_PATTERNS) {
            if (pattern.matcher(value).matches()) return true;
        }
        return hasAdContextNormalized(value) && COUNTDOWN_WITH_DURATION_PATTERN.matcher(value).matches();
    }


    /** True only for a visible skip control whose label contains a changing countdown value. */
    public static boolean isCountdownSkipLabel(String text) {
        return isCountdownSkipLabelNormalized(normalize(text));
    }

    static boolean isCountdownSkipLabelNormalized(String value) {
        for (Pattern pattern : COUNTDOWN_SKIP_LABEL_PATTERNS) {
            if (pattern.matcher(value).matches()) return true;
        }
        return false;
    }


    /**
     * Similarity used only to compare two observations of the same candidate.
     * Countdown digits are intentionally ignored when both labels are skip countdowns.
     */
    public static int similarityPercent(String first, String second) {
        String rawA = normalize(first);
        String rawB = normalize(second);
        if (rawA.isEmpty() || rawB.isEmpty()) return rawA.equals(rawB) ? 100 : 0;
        if (isCloseGlyph(rawA) && isCloseGlyph(rawB)) return 100;

        // Countdown digits and units change every second; compare the stable control
        // phrase rather than treating "Skip 4" -> "Skip" as a different button.
        String a = stripDynamicCountdown(rawA);
        String b = stripDynamicCountdown(rawB);
        if (a.isEmpty() || b.isEmpty()) return rawA.equals(rawB) ? 100 : 0;

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
        int editSimilarity = Math.max(0, Math.min(100,
                Math.round((1f - distance / (float) length) * 100f)));

        // OCR may insert/remove harmless whitespace or punctuation around short controls.
        String compactA = a.replaceAll("[\\s\\p{Punct}，。！？、；：]", "");
        String compactB = b.replaceAll("[\\s\\p{Punct}，。！？、；：]", "");
        if (!compactA.isEmpty() && compactA.equals(compactB)) return 100;
        return editSimilarity;
    }

    private static String stripDynamicCountdown(String value) {
        if (!isCountdownNormalized(value) && !isCountdownSkipLabelNormalized(value)) return value;
        String normalized = COUNTDOWN_CLOCK_PATTERN.matcher(value).replaceAll(" ");
        normalized = COUNTDOWN_PREFIX_PATTERN.matcher(normalized).replaceAll("$1");
        normalized = COUNTDOWN_NUMBER_SUFFIX_PATTERN.matcher(normalized).replaceAll(" ");
        return WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
    }


    public static String normalize(String input) {
        if (input == null) return "";
        String compatibility = Normalizer.normalize(input, Normalizer.Form.NFKC);
        compatibility = ZERO_WIDTH_PATTERN.matcher(compatibility).replaceAll("");
        compatibility = FORMAT_CONTROL_PATTERN.matcher(compatibility).replaceAll("");
        return WHITESPACE_PATTERN.matcher(compatibility.toLowerCase(Locale.ROOT))
                .replaceAll(" ").trim();
    }

}
