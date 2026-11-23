package com.aeginning.adskip.engine;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory proof that an enabled app was explicitly opened by clicking its
 * launcher icon. It deliberately fails closed when labels do not match.
 */
public final class UserLaunchTracker {
    public static final long LAUNCH_INTENT_TIMEOUT_MS = 10_000L;
    private static final int MAX_AUTHORIZED_PACKAGES = 64;

    private final Set<String> userLaunchedPackages = new LinkedHashSet<>();
    private String pendingLauncherLabel = "";
    private long pendingLauncherClickAt;

    public synchronized void recordLauncherClick(String label, long elapsedRealtime) {
        String normalized = normalize(label);
        if (normalized.isEmpty()) {
            clearPending();
            return;
        }
        pendingLauncherLabel = normalized;
        pendingLauncherClickAt = Math.max(1L, elapsedRealtime);
    }

    /** Authorize only when the foreground app label exactly matches a recent launcher icon click. */
    public synchronized boolean onForegroundPackage(String packageName, String appLabel,
                                                     long elapsedRealtime) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        if (userLaunchedPackages.contains(packageName)) return true;
        if (pendingLauncherLabel.isEmpty() || pendingLauncherClickAt <= 0L) return false;

        long age = elapsedRealtime - pendingLauncherClickAt;
        if (age < 0L || age > LAUNCH_INTENT_TIMEOUT_MS) {
            clearPending();
            return false;
        }

        String foregroundLabel = normalize(appLabel);
        boolean matched = !foregroundLabel.isEmpty()
                && pendingLauncherLabel.equals(foregroundLabel);
        clearPending();
        if (!matched) return false;

        userLaunchedPackages.add(packageName);
        while (userLaunchedPackages.size() > MAX_AUTHORIZED_PACKAGES) {
            String oldest = userLaunchedPackages.iterator().next();
            userLaunchedPackages.remove(oldest);
        }
        return true;
    }

    public synchronized boolean isUserLaunched(String packageName) {
        return packageName != null && userLaunchedPackages.contains(packageName);
    }

    public synchronized void clear() {
        userLaunchedPackages.clear();
        clearPending();
    }

    private void clearPending() {
        pendingLauncherLabel = "";
        pendingLauncherClickAt = 0L;
    }

    private static String normalize(String label) {
        if (label == null) return "";
        String normalized = Normalizer.normalize(label, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) result.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }
}
