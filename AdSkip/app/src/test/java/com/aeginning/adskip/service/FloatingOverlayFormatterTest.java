package com.aeginning.adskip.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FloatingOverlayFormatterTest {

    @Test public void formatsAllEnabledItemsInStableOrder() {
        String actual = FloatingOverlayFormatter.format(
                true, "已启用",
                true, 3,
                true, "运行中",
                true, 12);
        assertEquals("广告跳过：已启用\n"
                + "已启用应用：3 个\n"
                + "DNS 过滤：运行中\n"
                + "近 24 小时自动点击：12 次", actual);
    }

    @Test public void hidesDisabledItemsWithoutLeavingBlankLines() {
        String actual = FloatingOverlayFormatter.format(
                true, "等待选择应用",
                false, 5,
                true, "未运行",
                false, 9);
        assertEquals("广告跳过：等待选择应用\nDNS 过滤：未运行", actual);
    }

    @Test public void returnsHelpfulTextWhenEveryDisplayItemIsDisabled() {
        assertEquals("已开启悬浮窗 · 暂无显示项目",
                FloatingOverlayFormatter.format(
                        false, "已启用", false, 2, false, "运行中", false, 4));
    }

    @Test public void doesNotRenderNullStatusAsLiteralNull() {
        assertEquals("广告跳过：未知",
                FloatingOverlayFormatter.format(true, null, false, 0, false, null, false, 0));
    }
}
