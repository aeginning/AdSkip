package com.aeginning.adskip.privilege;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs inside Shizuku's UserService process (normally shell UID 2000, or root UID 0).
 * This service intentionally exposes only a fixed set of read-only diagnostics.
 * It does not accept arbitrary command strings and never changes app permissions/settings.
 */
public final class AdSkipShellUserService extends IAdSkipShellService.Stub {
    private static final Pattern PACKAGE = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final int MAX_OUTPUT = 24 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 5L;

    public AdSkipShellUserService() { }

    /** Shizuku v13 prefers the Context constructor when available. */
    public AdSkipShellUserService(Context ignored) { }

    @Override public String getIdentity() {
        int uid = android.os.Process.myUid();
        String role = uid == 0 ? "ROOT" : uid == 2000 ? "ADB/SHELL" : "UNKNOWN";
        String context = "unavailable";
        try {
            java.lang.Process process = new ProcessBuilder("/system/bin/id", "-Z").redirectErrorStream(true).start();
            if (process.waitFor(2, TimeUnit.SECONDS)) {
                context = readBounded(process.getInputStream(), 512).trim();
            } else {
                process.destroyForcibly();
            }
        } catch (Exception ignored) { }
        return "uid=" + uid + ";role=" + role + ";android=" + Build.VERSION.SDK_INT + ";selinux=" + context;
    }

    @Override public void shutdown() {
        final int pid = android.os.Process.myPid();
        Thread killer = new Thread(() -> {
            try { Thread.sleep(120L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            android.os.Process.killProcess(pid);
        }, "AdSkip-UserServiceShutdown");
        killer.setDaemon(true);
        killer.start();
    }

    @Override public String listThirdPartyPackages() {
        CommandResult result = run(new String[]{"pm", "list", "packages", "-3"});
        if (result.exitCode != 0) return failure("读取已安装应用列表", result);
        return result.output;
    }

    @Override public String getForegroundActivity() {
        CommandResult result = run(new String[]{"dumpsys", "activity", "activities"});
        if (result.exitCode != 0) return failure("读取前台 Activity", result);
        StringBuilder filtered = new StringBuilder();
        String[] keys = {"topResumedActivity", "mResumedActivity", "ResumedActivity", "mLastPausedActivity"};
        for (String line : result.output.split("\\r?\\n")) {
            String trimmed = line.trim();
            for (String key : keys) {
                if (trimmed.contains(key)) {
                    filtered.append(trimmed).append('\n');
                    break;
                }
            }
            if (filtered.length() > 6000) break;
        }
        if (filtered.length() == 0) {
            return "系统未返回可识别的 resumed Activity。\n可能原因：Android 厂商限制了 dumpsys 输出，或当前系统版本使用不同字段。";
        }
        return filtered.toString().trim();
    }

    @Override public String getPackageInfo(String packageName) {
        if (!isValidPackage(packageName)) return "包名格式无效，已拒绝执行。";
        CommandResult path = run(new String[]{"pm", "path", packageName});
        if (path.exitCode != 0 || !path.output.contains("package:")) return "目标应用不存在，或 Shell 无权查询：" + packageName;

        CommandResult info = run(new String[]{"dumpsys", "package", packageName});
        if (info.exitCode != 0) return failure("读取应用包元数据", info);

        StringBuilder filtered = new StringBuilder("应用包：").append(packageName).append('\n');
        String[] keys = {"versionCode=", "versionName=", "userId=", "firstInstallTime=", "lastUpdateTime=", "enabled=", "pkgFlags=", "flags="};
        int count = 0;
        for (String line : info.output.split("\\r?\\n")) {
            String trimmed = line.trim();
            for (String key : keys) {
                if (trimmed.contains(key)) {
                    filtered.append(trimmed).append('\n');
                    count++;
                    break;
                }
            }
            if (count >= 18) break;
        }
        return count == 0 ? "包存在，但没有读取到可展示的版本或安装元数据。" : filtered.toString().trim();
    }

    @Override public String getAppOps(String packageName) {
        if (!isValidPackage(packageName)) return "包名格式无效，已拒绝执行。";
        CommandResult path = run(new String[]{"pm", "path", packageName});
        if (path.exitCode != 0 || !path.output.contains("package:")) return "目标应用不存在，或 Shell 无权查询：" + packageName;

        CommandResult result = run(new String[]{"cmd", "appops", "get", packageName});
        if (result.exitCode != 0 || result.output.trim().isEmpty()) {
            result = run(new String[]{"appops", "get", packageName});
        }
        if (result.exitCode != 0 || result.output.trim().isEmpty()) {
            return "AppOps 查询不可用。设备可能限制 Shell 查询，或 Android 厂商修改了 appops 命令。\n"
                    + "AdSkip 未尝试修改任何 AppOps 状态。\n" + result.output;
        }
        return "AppOps 只读查询：" + packageName + "\n\n" + result.output;
    }


    /** Resolve only the fixed diagnostic binaries; arbitrary executable paths are never accepted. */
    private static String[] toAbsoluteCommand(String[] command) {
        if (command == null || command.length == 0) throw new IllegalArgumentException("Empty command");
        String executable;
        switch (command[0]) {
            case "pm": executable = "/system/bin/pm"; break;
            case "dumpsys": executable = "/system/bin/dumpsys"; break;
            case "cmd": executable = "/system/bin/cmd"; break;
            case "appops": executable = "/system/bin/appops"; break;
            default: throw new IllegalArgumentException("Diagnostic command is not allowlisted");
        }
        String[] resolved = command.clone();
        resolved[0] = executable;
        return resolved;
    }

    private static boolean isValidPackage(String packageName) {
        return packageName != null && packageName.length() <= 255 && PACKAGE.matcher(packageName).matches();
    }

    private static String failure(String operation, CommandResult result) {
        return operation + "失败（退出码 " + result.exitCode + "）。\n"
                + (TextUtils.isEmpty(result.output) ? "Shell 权限不足或设备命令不兼容。" : result.output);
    }

    private static CommandResult run(String[] command) {
        Process process = null;
        StreamCollector collector = null;
        try {
            process = new ProcessBuilder(toAbsoluteCommand(command)).redirectErrorStream(true).start();
            collector = new StreamCollector(process.getInputStream(), MAX_OUTPUT);
            collector.start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                collector.join(500);
                return new CommandResult(-2, collector.result() + "\n[命令超时，已终止]");
            }
            collector.join(500);
            return new CommandResult(process.exitValue(), collector.result());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new CommandResult(-3, "诊断被中断。");
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new CommandResult(-1, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
        }
    }

    private static String readBounded(InputStream input, int maxChars) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[256];
            int read;
            while ((read = reader.read(buffer)) >= 0 && out.length() < maxChars) {
                int allowed = Math.min(read, maxChars - out.length());
                out.append(buffer, 0, allowed);
            }
        }
        return out.toString();
    }

    private static final class StreamCollector extends Thread {
        private final InputStream input;
        private final int limit;
        private final StringBuilder output = new StringBuilder();
        private volatile boolean truncated;
        StreamCollector(InputStream input, int limit) {
            super("AdSkip-ShellOutput");
            this.input = input;
            this.limit = limit;
            setDaemon(true);
        }
        @Override public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    synchronized (output) {
                        int remaining = limit - output.length();
                        if (remaining > 0) {
                            int allowed = Math.min(read, remaining);
                            output.append(buffer, 0, allowed);
                            if (allowed < read) truncated = true;
                        } else {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) { }
        }
        String result() {
            synchronized (output) {
                String value = output.toString().trim();
                return truncated ? value + "\n[输出已截断]" : value;
            }
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output == null ? "" : output; }
    }
}
