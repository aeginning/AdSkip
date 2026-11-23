package com.aeginning.adskip.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FloatingOverlayFormatterTest {

    @Test public void preservesOriginalFourRowsAndAppendsExtendedMetrics() {
        String actual = FloatingOverlayFormatter.format(
                true, "已启用",
                true, 3,
                true, "运行中",
                true, 12,
                true, "已启用",
                true, 1_234,
                true, 7,
                true, 89,
                true, 5,
                true, 2);
        assertEquals("广告跳过：已启用\n"
                + "已启用应用：3 个\n"
                + "DNS 过滤：运行中\n"
                + "近 24 小时自动点击：12 次\n"
                + "OCR 辅助识别：已启用\n"
                + "当前 DNS 规则：1234 条\n"
                + "近 24 小时安全拦截：7 次\n"
                + "近 24 小时 DNS 拦截：89 次\n"
                + "近 24 小时未执行：5 次\n"
                + "近 24 小时错误：2 次", actual);
    }

    @Test public void hidesDisabledItemsWithoutRemovingOriginalEnabledRows() {
        String actual = FloatingOverlayFormatter.format(
                true, "等待选择应用",
                false, 5,
                true, "未运行",
                true, 9,
                false, "已启用",
                false, 30,
                false, 4,
                false, 6,
                false, 1,
                false, 3);
        assertEquals("广告跳过：等待选择应用\n"
                + "DNS 过滤：未运行\n"
                + "近 24 小时自动点击：9 次", actual);
    }

    @Test public void appendsLiveRecognitionStateWithoutReorderingOriginalRows() {
        String actual = FloatingOverlayFormatter.format(
                true, "已启用",
                true, 2,
                true, "运行中",
                true, 5,
                true, "已启用",
                false, 0,
                false, 0,
                false, 0,
                false, 0,
                false, 0,
                true, "关闭成功");
        assertEquals("广告跳过：已启用\n"
                + "已启用应用：2 个\n"
                + "DNS 过滤：运行中\n"
                + "近 24 小时自动点击：5 次\n"
                + "OCR 辅助识别：已启用\n"
                + "当前识别状态：关闭成功", actual);
    }

    @Test public void returnsHelpfulTextWhenEveryDisplayItemIsDisabled() {
        assertEquals("已开启悬浮窗 · 暂无显示项目",
                FloatingOverlayFormatter.format(
                        false, "已启用", false, 2, false, "运行中", false, 4,
                        false, "已启用", false, 10, false, 2, false, 3, false, 4, false, 5));
    }

    @Test public void doesNotRenderNullStatusAsLiteralNull() {
        assertEquals("广告跳过：未知",
                FloatingOverlayFormatter.format(
                        true, null, false, 0, false, null, false, 0,
                        false, null, false, 0, false, 0, false, 0, false, 0, false, 0));
    }
}
