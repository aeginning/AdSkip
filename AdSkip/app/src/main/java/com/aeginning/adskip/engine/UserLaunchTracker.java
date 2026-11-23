package com.aeginning.adskip.engine;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory proof that the current foreground app was explicitly opened by
 * clicking its launcher icon. Authorization is revoked when foreground changes.
 */
public final class UserLaunchTracker {
    public static final long LAUNCH_INTENT_TIMEOUT_MS = 10_000L;

    private final Set<String> userLaunchedPackages = new LinkedHashSet<>();
    private String pendingLauncherLabel = "";
    private long pendingLauncherClickAt;

    public synchronized void recordLauncherClick(String label, long elapsedRealtime) {
        String normalized = normalize(label);
        userLaunchedPackages.clear();
        if (normalized.isEmpty()) {
            clearPending();
            return;
        }
        pendingLauncherLabel = normalized;
        pendingLauncherClickAt = Math.max(1L, elapsedRealtime);
    }

    /**
     * Called on foreground/window identity changes. It preserves a pending launcher
     * intent but revokes the previous app's authorization as soon as it loses foreground.
     */
    public synchronized void onForegroundChanged(String foregroundPackage) {
        userLaunchedPackages.removeIf(packageName -> !packageName.equals(foregroundPackage));
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

        userLaunchedPackages.clear();
        userLaunchedPackages.add(packageName);
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
