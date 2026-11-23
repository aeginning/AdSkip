package com.aeginning.adskip.engine;

/** Central rules for excluding system apps and requiring foreground/user-launch eligibility. */
public final class AppScanPolicy {
    // Android ApplicationInfo.FLAG_SYSTEM and FLAG_UPDATED_SYSTEM_APP.
    public static final int FLAG_SYSTEM = 1;
    public static final int FLAG_UPDATED_SYSTEM_APP = 1 << 7;

    private AppScanPolicy() { }

    public static boolean isSystemAppFlags(int flags) {
        return (flags & (FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    public static boolean shouldScan(int flags, boolean appEnabled, boolean foreground,
                                     boolean userLaunchOnly, boolean userLaunched) {
        return !isSystemAppFlags(flags)
                && appEnabled
                && foreground
                && (!userLaunchOnly || userLaunched);
    }
}
