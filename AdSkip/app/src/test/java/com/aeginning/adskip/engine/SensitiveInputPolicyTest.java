package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SensitiveInputPolicyTest {
    @Test public void ordinarySearchAndCommentFieldsDoNotBlockScanning() {
        assertFalse(SensitiveInputPolicy.isSensitive(false, true, 0, "搜索", null));
        assertFalse(SensitiveInputPolicy.isSensitive(false, true, 0, "Search", "输入关键词"));
        assertFalse(SensitiveInputPolicy.isSensitive(false, true, 0, "Write a comment", null));
    }

    @Test public void passwordAndTypedSensitiveFieldsAreProtected() {
        assertTrue(SensitiveInputPolicy.isSensitive(true, true, 0, null, null));
        assertTrue(SensitiveInputPolicy.isSensitive(false, true, 0x81, null, null));
        assertTrue(SensitiveInputPolicy.isSensitive(false, true, 0x12, null, null));
        assertTrue(SensitiveInputPolicy.isSensitive(false, true, 0, "支付密码", null));
        assertTrue(SensitiveInputPolicy.isSensitive(false, true, 0, "One-time password", null));
        assertTrue(SensitiveInputPolicy.isSensitive(false, true, 0, null, "短信验证码"));
    }

    @Test public void exactPasswordHintIsSensitiveButSearchContextIsNot() {
        assertTrue(SensitiveInputPolicy.isSensitive(false, true, 0, "密码", null));
        assertTrue(SensitiveInputPolicy.isSensitive(false, true, 0, null, "密码"));
        assertFalse(SensitiveInputPolicy.isSensitive(false, true, 0, "搜索密码教程", null));
    }

    @Test public void descriptiveTextAloneDoesNotMarkAWholePageSensitive() {
        assertFalse(SensitiveInputPolicy.isSensitive(false, false, 0, "密码", "帮助说明"));
        assertFalse(SensitiveInputPolicy.isSensitive(false, true, 0, "搜索密码教程", null));
    }
}
