package com.aeginning.adskip.privilege;

/** Narrow, read-only diagnostics exposed by the Shizuku UserService. */
interface IAdSkipShellService {
    String getIdentity();
    void shutdown();
    String listThirdPartyPackages();
    String getForegroundActivity();
    String getPackageInfo(String packageName);
    String getAppOps(String packageName);
}
