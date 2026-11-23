package com.aeginning.adskip.privilege;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/** Owns Shizuku Binder, permission and UserService lifecycle. */
public final class PrivilegeManager {
    public static final int REQUEST_PERMISSION_CODE = 772;
    private static volatile PrivilegeManager instance;

    /** One Binder/UserService owner shared by the Activity and AccessibilityService. */
    public static synchronized PrivilegeManager getInstance(Context context) {
        if (instance == null) instance = new PrivilegeManager(context, null);
        return instance;
    }

    public interface Listener {
        void onPrivilegeStateChanged(Status status);
    }

    public static final class Status {
        public final boolean binderAlive;
        public final boolean permissionGranted;
        public final int serverUid;
        public final boolean userServiceConnected;
        public final int userServiceUid;
        public final String detail;

        Status(boolean binderAlive, boolean permissionGranted, int serverUid,
               boolean userServiceConnected, int userServiceUid, String detail) {
            this.binderAlive = binderAlive;
            this.permissionGranted = permissionGranted;
            this.serverUid = serverUid;
            this.userServiceConnected = userServiceConnected;
            this.userServiceUid = userServiceUid;
            this.detail = detail;
        }

        public boolean canUseEnhancedDiagnostics() {
            return binderAlive && permissionGranted && userServiceConnected
                    && (userServiceUid == 0 || userServiceUid == 2000)
                    && (serverUid == 0 || serverUid == 2000)
                    && userServiceUid == serverUid;
        }

        public String toDisplayText() {
            String binder = binderAlive ? "已连接" : "未连接";
            String permission = permissionGranted ? "已授权" : "未授权";
            String server = PrivilegeUtils.identityLabel(serverUid);
            String user = userServiceConnected
                    ? PrivilegeUtils.identityLabel(userServiceUid) : "未连接";
            return "Shizuku Binder：" + binder
                    + "\nAdSkip 授权：" + permission
                    + "\n服务端身份：" + server
                    + "\n诊断进程身份：" + user
                    + "\n增强诊断：" + (canUseEnhancedDiagnostics() ? "可用" : "不可用（普通无障碍模式继续工作）")
                    + (detail == null || detail.isEmpty() ? "" : "\n状态：" + detail);
        }
    }

    private final Context appContext;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean listenersRegistered;
    private int clientCount;
    private boolean bindRequested;
    private IAdSkipShellService shellService;
    private int userServiceUid = -1;
    private String detail = "尚未连接";

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        detail = "检测到 Shizuku Binder";
        notifyChanged();
        try {
            if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService();
            }
        } catch (RuntimeException ignored) { }
    };
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        shellService = null;
        bindRequested = false;
        userServiceUid = -1;
        detail = "Shizuku 服务已断开；AdSkip 已降级为普通模式";
        notifyChanged();
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode != REQUEST_PERMISSION_CODE) return;
        detail = grantResult == PackageManager.PERMISSION_GRANTED
                ? "Shizuku 权限已授予" : "用户未授予 Shizuku 权限";
        notifyChanged();
        if (grantResult == PackageManager.PERMISSION_GRANTED) bindUserService();
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            shellService = IAdSkipShellService.Stub.asInterface(service);
            bindRequested = true;
            userServiceUid = -1;
            detail = "Shizuku UserService 已连接，正在校验执行身份";
            notifyChanged();
            verifyRemoteIdentity();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            shellService = null;
            bindRequested = false;
            userServiceUid = -1;
            detail = "UserService 连接中断；普通模式不受影响";
            notifyChanged();
        }
    };

    private final Shizuku.UserServiceArgs userServiceArgs;

    public PrivilegeManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        if (listener != null) listeners.addIfAbsent(listener);
        this.userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(appContext, AdSkipShellUserService.class))
                .tag("adskip-readonly-diagnostics")
                .version(1)
                .processNameSuffix("adskip_diagnostics")
                .debuggable(false)
                .daemon(false);
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
            if (listenersRegistered) listener.onPrivilegeStateChanged(refresh());
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public synchronized void acquire() {
        clientCount++;
        start();
    }

    public synchronized void release() {
        if (clientCount > 0) clientCount--;
        if (clientCount == 0) stop();
    }

    public synchronized void start() {
        if (listenersRegistered) {
            notifyChanged();
            return;
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        listenersRegistered = true;
        refresh();
    }

    public synchronized void stop() {
        if (listenersRegistered) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
            listenersRegistered = false;
        }
        closeUserServiceLocked();
    }

    public synchronized Status refresh() {
        boolean binderAlive = safePingBinder();
        boolean permission = false;
        int uid = -1;
        String state = detail;
        if (binderAlive) {
            try {
                permission = !Shizuku.isPreV11()
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                if (permission) uid = Shizuku.getUid();
            } catch (RuntimeException e) {
                state = "Binder 可见，但读取权限状态失败：" + e.getClass().getSimpleName();
            }
        } else {
            if (shellService != null || bindRequested) closeUserServiceLocked();
            state = "未检测到 Shizuku 服务；增强诊断已停止，普通无障碍模式可独立运行";
        }
        if (binderAlive && !permission && (shellService != null || bindRequested)) {
            closeUserServiceLocked();
            detail = "Shizuku 授权不可用或已撤销；已停止增强诊断服务，普通模式继续运行";
            state = detail;
        }
        return new Status(binderAlive, permission, uid, shellService != null, userServiceUid, state);
    }

    public void requestPermissionOrConnect() {
        if (!safePingBinder()) {
            detail = "请先启动 Shizuku 服务";
            notifyChanged();
            return;
        }
        try {
            if (Shizuku.isPreV11()) {
                detail = "Shizuku 服务版本低于 v11，不支持当前 UserService 集成；请升级 Shizuku";
                notifyChanged();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_PERMISSION_CODE);
                detail = "已请求 Shizuku 授权，请在系统弹窗中确认";
                notifyChanged();
            } else {
                bindUserService();
            }
        } catch (RuntimeException e) {
            detail = "无法请求权限：" + e.getClass().getSimpleName();
            notifyChanged();
        }
    }

    public synchronized void bindUserService() {
        if (!safePingBinder()) {
            detail = "Binder 已断开，等待普通模式或重新启动 Shizuku";
            notifyChanged();
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                detail = "尚未获得 Shizuku 授权";
                notifyChanged();
                return;
            }
            if (shellService != null || bindRequested) {
                notifyChanged();
                return;
            }
            bindRequested = true;
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
            detail = "正在连接只读诊断服务";
            notifyChanged();
        } catch (RuntimeException e) {
            bindRequested = false;
            shellService = null;
            userServiceUid = -1;
            detail = "UserService 启动失败：" + e.getClass().getSimpleName()
                    + "；请确认 Shizuku 已授权且服务正常";
            notifyChanged();
        }
    }

    public synchronized IAdSkipShellService requireShellService() {
        Status status = refresh();
        if (!status.canUseEnhancedDiagnostics() || shellService == null) {
            throw new IllegalStateException("增强诊断不可用，已安全降级到普通模式");
        }
        return shellService;
    }

    public synchronized void handleDiagnosticFailure(Throwable error) {
        String reason = error == null ? "远程诊断服务不可用" : error.getClass().getSimpleName();
        closeUserServiceLocked();
        detail = "增强诊断连接失败（" + reason + "）；已回退普通模式。重新连接前不会继续发送 Shell 命令";
        notifyChanged();
    }

    private void verifyRemoteIdentity() {
        final IAdSkipShellService service = shellService;
        if (service == null) return;
        Thread verifier = new Thread(() -> {
            int uid = -1;
            String result;
            try {
                result = service.getIdentity();
                uid = PrivilegeUtils.parseUid(result);
            } catch (Exception e) {
                result = "身份校验失败：" + e.getClass().getSimpleName();
            }
            synchronized (PrivilegeManager.this) {
                if (shellService != service) return;
                userServiceUid = uid;
                Status state = refresh();
                if (uid != state.serverUid || (uid != 0 && uid != 2000)) {
                    detail = "身份不匹配或不是 Shell/Root；禁止增强命令。" + result;
                } else {
                    detail = "身份校验通过：" + PrivilegeUtils.identityLabel(uid);
                }
                notifyChanged();
            }
        }, "AdSkip-PrivilegeCheck");
        verifier.setDaemon(true);
        verifier.start();
    }

    private void closeUserServiceLocked() {
        IAdSkipShellService previous = shellService;
        boolean wasBound = bindRequested;
        // Clear state before unbind so a synchronous disconnect callback cannot recurse.
        shellService = null;
        userServiceUid = -1;
        bindRequested = false;
        if (previous != null) {
            try { previous.shutdown(); } catch (Exception ignored) { }
        }
        if (wasBound && safePingBinder()) {
            try { Shizuku.unbindUserService(userServiceArgs, serviceConnection, false); }
            catch (RuntimeException ignored) { }
        }
    }

    private boolean safePingBinder() {
        try { return Shizuku.pingBinder(); }
        catch (RuntimeException ignored) { return false; }
    }

    private void notifyChanged() {
        Status status = refresh();
        for (Listener listener : listeners) {
            try { listener.onPrivilegeStateChanged(status); }
            catch (RuntimeException ignored) { }
        }
    }
}
