package com.aeginning.adskip.engine;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory proof that the current foreground package was explicitly opened
 * from a launcher icon that uniquely resolved to an enabled app package.
 */
public final class UserLaunchTracker {
    public static final long LAUNCH_INTENT_TIMEOUT_MS = 10_000L;

    private final Set<String> userLaunchedPackages = new LinkedHashSet<>();
    private String pendingLauncherPackage = "";
    private long pendingLauncherClickAt;

    public synchronized void recordLauncherClick(String packageName, long elapsedRealtime) {
        userLaunchedPackages.clear();
        if (packageName == null || packageName.trim().isEmpty()) {
            clearPending();
            return;
        }
        pendingLauncherPackage = packageName.trim();
        pendingLauncherClickAt = Math.max(1L, elapsedRealtime);
    }

    /**
     * Called on foreground/window identity changes. It preserves a pending launch
     * intent but revokes authorization for an app as soon as it loses foreground.
     */
    public synchronized void onForegroundChanged(String foregroundPackage) {
        userLaunchedPackages.removeIf(packageName -> !packageName.equals(foregroundPackage));
    }

    /** Authorize only the exact package uniquely resolved from the recent launcher click. */
    public synchronized boolean onForegroundPackage(String packageName, long elapsedRealtime) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        if (userLaunchedPackages.contains(packageName)) return true;
        if (pendingLauncherPackage.isEmpty() || pendingLauncherClickAt <= 0L) return false;

        long age = elapsedRealtime - pendingLauncherClickAt;
        if (age < 0L || age > LAUNCH_INTENT_TIMEOUT_MS) {
            clearPending();
            return false;
        }

        boolean matched = pendingLauncherPackage.equals(packageName);
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
        pendingLauncherPackage = "";
        pendingLauncherClickAt = 0L;
    }

    /** NFKC/case/punctuation normalization for matching launcher labels to installed labels. */
    public static String normalizeLauncherLabel(String label) {
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
