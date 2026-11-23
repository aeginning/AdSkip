package com.aeginning.adskip.engine;

/** Determines whether an accessibility event represents a different foreground window session. */
public final class WindowSessionPolicy {
    private WindowSessionPolicy() { }

    public static boolean isNewSession(String previousPackage, String currentPackage,
                                       String previousActivityClass, String currentActivityClass,
                                       int previousWindowId, int currentWindowId,
                                       boolean compareActivityClass) {
        String current = normalize(currentPackage);
        if (current.isEmpty()) return false;

        String previous = normalize(previousPackage);
        if (previous.isEmpty() || !previous.equals(current)) return true;

        if (previousWindowId >= 0 && currentWindowId >= 0
                && previousWindowId != currentWindowId) return true;

        if (compareActivityClass) {
            String oldClass = normalize(previousActivityClass);
            String newClass = normalize(currentActivityClass);
            return !oldClass.isEmpty() && !newClass.isEmpty() && !oldClass.equals(newClass);
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
