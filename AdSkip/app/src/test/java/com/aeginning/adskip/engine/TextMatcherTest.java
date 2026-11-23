package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextMatcherTest {
    private int confidence(String label, String context) {
        return AdConfidenceScorer.assess(label, context, true, true, true,
                96, 36, 1080, 2400).score;
    }

    @Test public void explicitSkipLabelsScoreHighly() {
        assertTrue(confidence("跳过广告", "正在播放广告") >= 90);
        assertTrue(confidence("Skip Ad", "Video content") >= 75);
    }

    @Test public void genericSkipRequiresAdContext() {
        assertTrue(confidence("跳过", "正在播放广告") >= 75);
        assertEquals(0, confidence("跳过", "普通视频播放"));
        assertEquals(0, confidence("Skip", "regular video"));
    }

    @Test public void genericCloseRequiresAdContext() {
        assertEquals(0, confidence("关闭", "普通页面"));
        assertTrue(confidence("关闭", "广告 sponsored content") >= 75);
        assertTrue(confidence("关闭广告", "普通页面") >= 75);
    }

    @Test public void dangerousActionsAreNeverCandidates() {
        assertEquals(0, confidence("立即下载", "广告"));
        assertEquals(0, confidence("Allow", "sponsored content"));
        assertEquals(0, confidence("Subscribe", "advertisement"));
        assertEquals(0, confidence("确认购买", "广告"));
    }

    @Test public void countdownLabelsAndContextsAreRejected() {
        assertEquals(0, confidence("跳过", "广告 3秒后跳过"));
        assertEquals(0, confidence("Skip in 5 seconds", "Advertisement"));
        assertEquals(0, confidence("跳过", "广告 3 seconds"));
    }

    @Test public void negativeSkipLabelsAreRejectedBeforeSubstringMatching() {
        assertEquals(0, confidence("Don't skip this ad", "advertisement"));
        assertEquals(0, confidence("不可跳过", "广告"));
        assertTrue(TextMatcher.isNegativeSkip("Do not skip this advertisement"));
        assertTrue(TextMatcher.isNegativeSkip("不允许跳过"));
    }

    @Test public void additionalExplicitAdLabelsAreRecognized() {
        assertTrue(confidence("跳过此广告", "广告播放中") >= 90);
        assertTrue(confidence("跳过当前视频广告", "正在播放") >= 75);
        assertTrue(confidence("关闭此广告", "普通页面") >= 75);
        assertTrue(confidence("Dismiss this ad", "sponsored video") >= 90);
        assertTrue(confidence("広告をスキップ", "広告") >= 90);
        assertTrue(confidence("광고 건너뛰기", "sponsored content") >= 90);
    }

    @Test public void newNegativeAndCommercialActionsRemainBlocked() {
        assertEquals(0, confidence("当前广告不可跳过", "广告"));
        assertEquals(0, confidence("Skip unavailable", "advertisement"));
        assertEquals(0, confidence("立即领取优惠券", "广告"));
        assertEquals(0, confidence("查看详情", "sponsored content"));
        assertEquals(0, confidence("구매", "광고"));
    }

    @Test public void vagueSkipNowStillRequiresAdContext() {
        assertTrue(confidence("Skip now", "Sponsored advertisement") >= 75);
        assertEquals(0, confidence("Skip now", "video settings"));
    }

    @Test public void splashSkipCountdownFormatsAreRejectedUntilReady() {
        assertTrue(TextMatcher.isCountdown("跳过 3"));
        assertTrue(TextMatcher.isCountdown("跳过3秒"));
        assertTrue(TextMatcher.isCountdown("Skip 3"));
        assertTrue(TextMatcher.isCountdown("Skip 3s"));
        assertTrue(TextMatcher.isCountdown("广告 3秒后跳过"));
        assertTrue(TextMatcher.isCountdown("あと 3 秒"));
        assertTrue(TextMatcher.isCountdown("3초 후 건너뛰기"));
        assertFalse(TextMatcher.isCountdown("跳过"));
        assertTrue(confidence("跳过", "广告来源：趣盟") >= 75);
    }

    @Test public void countdownSkipRequiresExplicitOptInAndAdContext() {
        assertEquals(0, AdConfidenceScorer.assess("跳过 4", "广告来源：趣盟",
                true, true, true, 80, 34, 1080, 2400).score);
        assertTrue(AdConfidenceScorer.assess("跳过 4", "广告来源：趣盟",
                true, true, true, 80, 34, 1080, 2400, true).score >= 75);
        assertEquals(0, AdConfidenceScorer.assess("摇一摇 4", "广告来源：趣盟",
                true, true, true, 80, 34, 1080, 2400, true).score);
        assertEquals(0, AdConfidenceScorer.assess("跳过 4", "普通视频播放",
                true, true, true, 80, 34, 1080, 2400, true).score);
        assertEquals(0, AdConfidenceScorer.assess("跳过 4", "广告：立即购买",
                true, true, true, 80, 34, 1080, 2400, true).score);
    }

    @Test public void similarityToleratesCountdownNumberChanges() {
        assertTrue(TextMatcher.isCountdownSkipLabel("跳过 4"));
        assertTrue(TextMatcher.isCountdownSkipLabel("Skip 3s"));
        assertEquals(100, TextMatcher.similarityPercent("跳过 4", "跳过 3"));
        assertEquals(100, TextMatcher.similarityPercent("Skip 4", "Skip 3s"));
        assertTrue(TextMatcher.similarityPercent("Skip Ad", "Skip Ads") >= 85);
        assertTrue(TextMatcher.similarityPercent("跳过广告", "跳过广吿") < 100);
    }

    @Test public void closeGlyphsAreExactAndNotGeneralLetterMatches() {
        assertTrue(TextMatcher.isCloseGlyph("X"));
        assertTrue(TextMatcher.isCloseGlyph("x"));
        assertTrue(TextMatcher.isCloseGlyph("×"));
        assertTrue(TextMatcher.isCloseGlyph("✕"));
        assertFalse(TextMatcher.isCloseGlyph("X广告"));
        assertFalse(TextMatcher.isCloseGlyph("下一步 X"));
        assertFalse(TextMatcher.isCloseGlyph("关闭"));
    }

    @Test public void closeGlyphCompatibilityIncludesMoreUnicodeVariants() {
        assertTrue(TextMatcher.isCloseGlyph("╳"));
        assertTrue(TextMatcher.isCloseGlyph("⨯"));
        assertTrue(TextMatcher.isCloseGlyph("☓"));
        assertTrue(TextMatcher.isCloseGlyph("❎"));
        assertTrue(TextMatcher.isCloseGlyph("✖️"));
        assertFalse(TextMatcher.isCloseGlyph("X install"));
    }

    @Test public void dynamicCountdownAndOcrSpacingAreTolerated() {
        assertTrue(TextMatcher.isCountdown("Skip in 00:05"));
        assertTrue(TextMatcher.isCountdown("omitir en 5 segundos"));
        assertTrue(TextMatcher.isCountdown("スキップ 3"));
        assertTrue(TextMatcher.isCountdown("건너뛰기 3"));
        assertTrue(TextMatcher.isCountdownSkipLabel("omitir en 5 segundos"));
        assertTrue(TextMatcher.isCountdown("تخطي بعد 3 ثواني"));
        assertEquals(100, TextMatcher.similarityPercent("Skip in 5 seconds", "Skip in 2 sec"));
        assertEquals(100, TextMatcher.similarityPercent("omitir en 5 segundos", "omitir en 2 segundos"));
        assertEquals(100, TextMatcher.similarityPercent("ignorer dans 5 secondes", "ignorer dans 2 secondes"));
        assertEquals(100, TextMatcher.similarityPercent("пропустить через 5 секунд", "пропустить через 2 секунды"));
        assertEquals(100, TextMatcher.similarityPercent("تخطي بعد 3 ثواني", "تخطي بعد 1 ثانية"));
        assertEquals(100, TextMatcher.similarityPercent("跳过 3 秒", "跳过"));
        assertTrue(TextMatcher.similarityPercent("Skip Ad", "Skip  Ad") >= 95);
    }

    @Test public void sensitivityMappingIsMonotonic() {
        assertEquals(90, AdConfidenceScorer.minimumScoreForSensitivity(0));
        assertEquals(75, AdConfidenceScorer.minimumScoreForSensitivity(50));
        assertEquals(60, AdConfidenceScorer.minimumScoreForSensitivity(100));
        assertEquals(90, AdConfidenceScorer.minimumScoreForSensitivity(-20));
        assertEquals(60, AdConfidenceScorer.minimumScoreForSensitivity(120));
    }

    @Test public void criticalActionContextIsSharedAndConservative() {
        assertTrue(TextMatcher.hasCriticalActionContext("广告页面 · 确认购买"));
        assertTrue(TextMatcher.hasCriticalActionContext("Sponsored content · Allow access"));
        assertTrue(TextMatcher.hasCriticalActionContext("视频广告 · 支付密码"));
        assertFalse(TextMatcher.hasCriticalActionContext("广告播放中 · 跳过"));
        assertFalse(TextMatcher.hasCriticalActionContext("普通视频"));
    }

    @Test public void customSkipWordsRequireAdContextAndStillRespectRiskGates() {
        java.util.List<String> custom = SkipWordLibrary.parse("稍后略过");
        AdConfidenceScorer.Assessment accepted = AdConfidenceScorer.assess(
                "稍后略过", "正在播放广告", true, true, true,
                96, 36, 1080, 2400, false, 75, custom);
        assertTrue(accepted.score >= 75);
        assertTrue(accepted.score < 90);
        assertEquals(0, AdConfidenceScorer.assess(
                "稍后略过", "普通视频播放", true, true, true,
                96, 36, 1080, 2400, false, 75, custom).score);
        assertEquals(0, AdConfidenceScorer.assess(
                "稍后略过", "广告页面 · 确认购买", true, true, true,
                96, 36, 1080, 2400, false, 75, custom).score);
        assertEquals(0, AdConfidenceScorer.assess(
                "不可跳过", "广告播放中", true, true, true,
                96, 36, 1080, 2400, false, 75, custom).score);
    }

    @Test public void customSkipWordsNeverOverrideDangerousCandidateLabels() {
        java.util.List<String> custom = SkipWordLibrary.parse("稍后略过");
        assertEquals(0, AdConfidenceScorer.assess(
                "立即购买稍后略过", "广告播放中", true, true, true,
                96, 36, 1080, 2400, false, 60, custom).score);
        assertEquals(0, AdConfidenceScorer.assess(
                "立即下载稍后略过", "广告播放中", true, true, true,
                96, 36, 1080, 2400, false, 60, custom).score);
        assertEquals(0, AdConfidenceScorer.assess(
                "稍后略过", "广告页面 · 支付密码", true, true, true,
                96, 36, 1080, 2400, false, 60, custom).score);
    }

    @Test public void genericSkipLayoutFallbackRequiresAnExactShortLabel() {
        assertTrue(TextMatcher.isGenericSkipLabel("跳过"));
        assertTrue(TextMatcher.isGenericSkipLabel("Ｓｋｉｐ"));
        assertFalse(TextMatcher.isGenericSkipLabel("立即跳过"));
        assertFalse(TextMatcher.isGenericSkipLabel("Skip Ad"));
        assertFalse(TextMatcher.isGenericSkipLabel("跳过广告"));
    }

    @Test public void normalizesFullWidthAndZeroWidthAdLabels() {
        assertTrue(TextMatcher.isCountdown("Ｓｋｉｐ ３"));
        assertTrue(TextMatcher.isCountdown("跳过\u200B 3"));
        assertTrue(TextMatcher.isExplicitSkipAd("Ｓｋｉｐ Ａｄ"));
        assertEquals("skip ad", TextMatcher.normalize("Ｓｋｉｐ\u200B Ａｄ"));
    }
}
