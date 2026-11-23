package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecognitionStatusPolicyTest {
    @Test public void mapsRecognitionAndClickLifecycle() {
        assertEquals("正在识别（OCR）", RecognitionStatusPolicy.fromDiagnostic("OCR_START"));
        assertEquals("识别成功 · 安全复核中", RecognitionStatusPolicy.fromDiagnostic("CANDIDATE"));
        assertEquals("尝试点击", RecognitionStatusPolicy.fromDiagnostic("CLICK_DISPATCHED"));
        assertEquals("关闭成功", RecognitionStatusPolicy.fromDiagnostic("CLICK_VERIFIED"));
    }

    @Test public void unknownDiagnosticsDoNotOverwriteCurrentStatus() {
        assertNull(RecognitionStatusPolicy.fromDiagnostic(null));
        assertNull(RecognitionStatusPolicy.fromDiagnostic("SOME_INTERNAL_EVENT"));
    }
}
