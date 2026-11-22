package com.aeginning.adskip.privilege;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure helpers shared by the app process and unit tests. */
public final class PrivilegeUtils {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");

    private PrivilegeUtils() { }

    public static boolean isValidPackageName(String value) {
        return value != null && value.length() <= 255 && PACKAGE_NAME.matcher(value).matches();
    }

    public static String identityLabel(int uid) {
        if (uid == 0) return "Root (UID 0)";
        if (uid == 2000) return "ADB Shell (UID 2000)";
        if (uid < 0) return "未知 / 尚未授权";
        return "其他身份 (UID " + uid + ")";
    }

    public static int parseUid(String identity) {
        if (identity == null) return -1;
        for (String token : identity.split("[;\\n]")) {
            String trimmed = token.trim();
            if (trimmed.startsWith("uid=")) {
                try { return Integer.parseInt(trimmed.substring(4).trim()); }
                catch (NumberFormatException ignored) { return -1; }
            }
        }
        return -1;
    }

    public static String sanitizePackageList(String output, int maxPackages) {
        if (output == null || output.isEmpty()) return "未读取到应用列表。";
        List<String> packages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : output.split("\\r?\\n")) {
            String value = line.trim();
            if (value.startsWith("package:")) value = value.substring("package:".length()).trim();
            if (isValidPackageName(value) && seen.add(value)) {
                packages.add(value);
                if (packages.size() >= Math.max(1, maxPackages)) break;
            }
        }
        if (packages.isEmpty()) return "没有读取到第三方应用包名，或当前系统不允许查询。";
        StringBuilder result = new StringBuilder("已读取 ").append(packages.size()).append(" 个第三方应用包名：\n\n");
        for (String item : packages) result.append(item).append('\n');
        return result.toString().trim();
    }

    public static String normalizeDiagnosticOutput(String output, int maxChars) {
        if (output == null || output.trim().isEmpty()) return "系统没有返回可显示的信息。";
        String value = output.replace("\u0000", "").trim();
        int limit = Math.max(256, maxChars);
        if (value.length() > limit) value = value.substring(0, limit) + "\n\n[输出已截断]";
        return value;
    }

}
