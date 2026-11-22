package com.aeginning.adskip.engine;

/**
 * Classifies only genuinely sensitive editable fields.
 * Ordinary search boxes, comment fields and text inputs must not disable page scanning.
 * Input values are never read or persisted by this policy.
 */
public final class SensitiveInputPolicy {
    private static final int TEXT_VARIATION_PASSWORD = 0x080;
    private static final int TEXT_VARIATION_VISIBLE_PASSWORD = 0x090;
    private static final int TEXT_VARIATION_WEB_PASSWORD = 0x0E0;
    private static final int NUMBER_VARIATION_PASSWORD = 0x010;

    private static final String[] SENSITIVE_HINTS = {
            "pass phrase", "pin code", "security pin",
            "one time password", "one-time password", "otp", "verification code",
            "security code", "card number", "credit card", "cvv", "cvc",
            "口令", "支付密码", "交易密码", "验证码", "短信码",
            "银行卡号", "信用卡号", "安全码", "动态口令", "输入密码", "请输入密码",
            "パスワード", "暗証番号", "認証コード", "ワンタイムパスワード",
            "비밀번호", "인증번호", "보안 코드", "일회용 비밀번호"
    };

    private SensitiveInputPolicy() { }

    public static boolean isSensitive(boolean password, boolean editable, int inputType,
                                      CharSequence hint, CharSequence contentDescription) {
        if (password) return true;
        int variation = inputType & 0x0FF0;
        if (variation == TEXT_VARIATION_PASSWORD
                || variation == TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == TEXT_VARIATION_WEB_PASSWORD
                || variation == NUMBER_VARIATION_PASSWORD) return true;
        if (!editable) return false;

        String normalizedHint = normalize(hint);
        String normalizedDescription = normalize(contentDescription);
        // Exact labels and explicit input prompts count; generic search descriptions
        // such as "搜索验证码教程" or "Search password tutorial" do not.
        if ("密码".equals(normalizedHint) || "密码".equals(normalizedDescription)
                || isExplicitPasswordHint(normalizedHint)
                || isExplicitPasswordHint(normalizedDescription)) return true;
        for (String token : SENSITIVE_HINTS) {
            String normalizedToken = normalize(token);
            if (matchesSensitiveHint(normalizedHint, normalizedToken)
                    || matchesSensitiveHint(normalizedDescription, normalizedToken)) return true;
        }
        return false;
    }

    private static boolean isExplicitPasswordHint(String value) {
        return matchesSensitiveHint(value, "password")
                || matchesSensitiveHint(value, "passcode")
                || "password field".equals(value)
                || "passcode field".equals(value);
    }

    private static boolean matchesSensitiveHint(String value, String token) {
        if (value.isEmpty() || token.isEmpty()) return false;
        if (value.equals(token) || value.equals(token + " field")) return true;
        String[] prefixes = {
                "请输入", "输入", "请填写",
                "enter ", "enter your ", "enter the ",
                "type ", "type your ", "type the ",
                "input ", "input your ", "input the ",
                "provide "
        };
        for (String prefix : prefixes) {
            if (value.equals(prefix + token)) return true;
        }
        return false;
    }

    private static String normalize(CharSequence value) {
        return value == null ? "" : value.toString().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\u200B-\\u200D\\u2060\\uFEFF]", "")
                .replaceAll("\\s+", " ").trim();
    }
}
