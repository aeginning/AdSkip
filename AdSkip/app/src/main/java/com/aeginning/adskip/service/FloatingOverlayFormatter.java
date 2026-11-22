package com.aeginning.adskip.service;

/** Pure formatter for the user-configurable floating status overlay. */
final class FloatingOverlayFormatter {
    private FloatingOverlayFormatter() { }

    static String format(boolean showSkip, String skipStatus,
                         boolean showApps, int enabledApps,
                         boolean showDns, String dnsStatus,
                         boolean showClicks, int clicks24h) {
        StringBuilder text = new StringBuilder();
        if (showSkip) appendLine(text, "广告跳过", skipStatus);
        if (showApps) appendLine(text, "已启用应用", enabledApps + " 个");
        if (showDns) appendLine(text, "DNS 过滤", dnsStatus);
        if (showClicks) appendLine(text, "近 24 小时自动点击", clicks24h + " 次");
        if (text.length() == 0) return "已开启悬浮窗 · 暂无显示项目";
        return text.toString();
    }

    private static void appendLine(StringBuilder target, String label, String value) {
        if (target.length() > 0) target.append('\n');
        target.append(label).append("：").append(value == null ? "未知" : value);
    }
}
