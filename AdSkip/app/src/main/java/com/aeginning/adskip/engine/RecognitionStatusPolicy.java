package com.aeginning.adskip.engine;

/** Converts internal diagnostic events into short user-facing overlay states. */
public final class RecognitionStatusPolicy {
    private RecognitionStatusPolicy() { }

    public static String fromDiagnostic(String action) {
        if (action == null) return null;
        switch (action) {
            case "SCAN": return "正在扫描";
            case "OCR_START": return "正在识别（OCR）";
            case "OCR_QUEUED":
            case "OCR_QUEUE_REPLACED":
            case "OCR_QUEUE_WAIT": return "识别任务排队";
            case "OCR_SUCCESS":
            case "CANDIDATE":
            case "SPLASH_PROBE_CANDIDATE":
            case "COUNTDOWN_READY": return "识别成功 · 安全复核中";
            case "CONFIRM_PASSED":
            case "OCR_CONFIRM_START": return "识别通过 · 准备点击";
            case "CLICK_DISPATCHED":
            case "GESTURE_COMPLETED": return "尝试点击";
            case "CLICK_VERIFIED": return "关闭成功";
            case "CLICK_NOT_EFFECTIVE":
            case "CLICK_REJECTED":
            case "OCR_GESTURE_REJECTED": return "点击未生效";
            case "CLICK_BLOCKED":
            case "CLICK_REVALIDATION_FAILED":
            case "OCR_BLOCKED":
            case "OCR_STALE_WINDOW": return "安全检查未通过";
            case "NO_CANDIDATE":
            case "OCR_NO_CANDIDATE": return "未发现可用跳过按钮";
            case "OCR_ERROR":
            case "SCAN_ERROR": return "识别异常";
            case "OCR_DISABLED": return "OCR 已关闭";
            case "SERVICE_CONNECTED": return "服务已连接 · 等待目标应用";
            default: return null;
        }
    }
}
