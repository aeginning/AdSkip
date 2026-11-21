package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextMatcherTest {
    @Test public void recognizesChineseSkipLabels() {
        assertTrue(TextMatcher.score("跳过广告", true) >= 90);
        assertTrue(TextMatcher.score("跳过", true) >= 80);
        assertEquals(0, TextMatcher.score("跳过", false));
    }

    @Test public void recognizesEnglishSkipLabels() {
        assertTrue(TextMatcher.score("Skip", true) >= 80);
        assertEquals(0, TextMatcher.score("Skip", false));
        assertTrue(TextMatcher.score("Skip Ad", false) >= 90);
    }

    @Test public void genericCloseRequiresAdContext() {
        assertEquals(0, TextMatcher.score("关闭", false));
        assertTrue(TextMatcher.score("关闭", true) > 0);
    }

    @Test public void rejectsDangerousActions() {
        assertEquals(0, TextMatcher.score("立即下载", true));
        assertEquals(0, TextMatcher.score("Allow", true));
        assertEquals(0, TextMatcher.score("Subscribe", true));
    }

    @Test public void doesNotClickBeforeCountdownCompletes() {
        assertEquals(0, TextMatcher.score("3秒后跳过", true));
        assertEquals(0, TextMatcher.score("Skip in 5 seconds", true));
        assertTrue(TextMatcher.score("Skip", true) > 0);
    }

    @Test public void detectsAdContext() {
        assertTrue(TextMatcher.hasAdContext("赞助内容 - 广告"));
        assertTrue(TextMatcher.hasAdContext("Sponsored"));
        assertFalse(TextMatcher.hasAdContext("设置与账号"));
    }
}
