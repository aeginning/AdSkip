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
}
