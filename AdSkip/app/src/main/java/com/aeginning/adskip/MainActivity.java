package com.aeginning.adskip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aeginning.adskip.db.RuleDatabase;
import com.aeginning.adskip.service.DnsFilterVpnService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.aeginning.adskip.privilege.PrivilegeManager;
import com.aeginning.adskip.privilege.PrivilegeUtils;
import com.aeginning.adskip.privilege.IAdSkipShellService;

public final class MainActivity extends Activity {
    private static final int REQUEST_VPN = 410;
    private static final int REQUEST_NOTIFICATIONS = 411;
    private RuleDatabase database;
    private TextView serviceStatus;
    private TextView appRuleStatus;
    private TextView domainRuleStatus;
    private TextView statsStatus;
    private CompoundButton ocrSwitch;
    private TextView privilegeStatus;
    private PrivilegeManager privilegeManager;
    private final ExecutorService privilegeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean activityDestroyed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new RuleDatabase(this);
        privilegeManager = new PrivilegeManager(this, status -> runOnUiThread(() -> {
            if (!activityDestroyed) updatePrivilegeStatus(status.toDisplayText());
        }));
        privilegeManager.start();
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        // Target SDK 35 is edge-to-edge on Android 15+: keep content clear of system bars/cutouts.
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        } else {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(0xFFF5F7FA);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.main_title);
        title.setTextSize(30);
        title.setTextColor(0xFF172033);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("智能跳广告助手 · 本地识别 · 安全点击");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF667085);
        addGap(root, 4);
        root.addView(subtitle);

        addGap(root, 22);
        LinearLayout statusCard = card(root);
        TextView statusTitle = label("运行状态", 18, 0xFF172033, true);
        statusCard.addView(statusTitle);
        serviceStatus = label("正在检查无障碍服务…", 14, 0xFF475467, false);
        addGap(statusCard, 8);
        statusCard.addView(serviceStatus);
        addGap(root, 12);

        Button accessibility = button("开启无障碍服务", 0xFF245BDB);
        root.addView(accessibility);
        accessibility.setOnClickListener(v -> showAccessibilityDisclosure());

        addGap(root, 12);
        LinearLayout appCard = card(root);
        appCard.addView(label("应用规则", 18, 0xFF172033, true));
        appRuleStatus = label("尚未选择应用。默认不对任何第三方 App 自动点击。", 13, 0xFF667085, false);
        addGap(appCard, 6);
        appCard.addView(appRuleStatus);
        addGap(root, 8);
        Button chooseApps = button("选择需要自动跳过广告的 App", 0xFF344054);
        root.addView(chooseApps);
        chooseApps.setOnClickListener(v -> showAppRulesDialog());
        addGap(root, 8);
        Button falsePositiveRules = button("管理误判屏蔽规则", 0xFF475467);
        root.addView(falsePositiveRules);
        falsePositiveRules.setOnClickListener(v -> showRuleLearningDialog());

        addGap(root, 14);
        LinearLayout statsCard = card(root);
        statsCard.addView(label("本地运行统计", 18, 0xFF172033, true));
        statsStatus = label("正在统计近 24 小时数据…", 13, 0xFF667085, false);
        addGap(statsCard, 6);
        statsCard.addView(statsStatus);
        addGap(root, 8);
        Button compatibility = button("设备兼容性与诊断报告", 0xFF475467);
        root.addView(compatibility);
        compatibility.setOnClickListener(v -> showCompatibilityReport());

        addGap(root, 14);
        LinearLayout privilegeCard = card(root);
        privilegeCard.addView(label("Shizuku / ADB 增强模式", 18, 0xFF172033, true));
        TextView privilegeHint = label(
                "可选增强功能：只读查询已安装应用、前台 Activity、应用包元数据和 AppOps 状态。不会执行修改权限、禁用应用或任意 Shell 命令。Shizuku 断开后，普通无障碍模式继续运行。",
                13, 0xFF667085, false);
        addGap(privilegeCard, 5);
        privilegeCard.addView(privilegeHint);
        privilegeStatus = label("正在检查 Shizuku Binder…", 13, 0xFF475467, false);
        addGap(privilegeCard, 9);
        privilegeCard.addView(privilegeStatus);
        addGap(root, 8);
        Button connectPrivilege = button("连接 Shizuku / 请求授权", 0xFF245BDB);
        root.addView(connectPrivilege);
        connectPrivilege.setOnClickListener(v -> connectShizuku());
        addGap(root, 8);
        Button runDiagnostics = button("运行增强系统诊断", 0xFF344054);
        root.addView(runDiagnostics);
        runDiagnostics.setOnClickListener(v -> showPrivilegeDiagnostics());
        addGap(root, 14);
        LinearLayout ocrCard = card(root);
        ocrCard.addView(label("图像识别（OCR）", 18, 0xFF172033, true));
        TextView ocrHint = label("Android 11+ 使用无障碍截图在本机识别中文和英文；不会上传截图。", 13, 0xFF667085, false);
        addGap(ocrCard, 5);
        ocrCard.addView(ocrHint);
        ocrSwitch = new android.widget.Switch(this);
        ocrSwitch.setText(R.string.ocr_switch_label);
        ocrSwitch.setTextColor(0xFF344054);
        ocrSwitch.setChecked(getSharedPreferences("adskip_settings", MODE_PRIVATE).getBoolean("ocr_enabled", true));
        ocrSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences("adskip_settings", MODE_PRIVATE).edit().putBoolean("ocr_enabled", checked).apply());
        addGap(ocrCard, 8);
        ocrCard.addView(ocrSwitch);

        addGap(root, 14);
        LinearLayout dnsCard = card(root);
        dnsCard.addView(label("DNS 广告域名过滤", 18, 0xFF172033, true));
        domainRuleStatus = label("尚未添加过滤域名。建议先使用少量自定义规则测试。", 13, 0xFF667085, false);
        addGap(dnsCard, 5);
        dnsCard.addView(domainRuleStatus);
        addGap(root, 8);
        Button domains = button("管理 DNS 域名规则", 0xFF344054);
        root.addView(domains);
        domains.setOnClickListener(v -> showDomainRulesDialog());
        addGap(root, 8);
        Button startVpn = button("启动 DNS 过滤 VPN", 0xFF16794B);
        root.addView(startVpn);
        startVpn.setOnClickListener(v -> showDnsDisclosure());
        addGap(root, 8);
        Button stopVpn = button("停止 DNS 过滤", 0xFFB42318);
        root.addView(stopVpn);
        stopVpn.setOnClickListener(v -> {
            Intent stop = new Intent(this, DnsFilterVpnService.class).setAction(DnsFilterVpnService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this, "已发送停止请求", Toast.LENGTH_SHORT).show();
        });
        addGap(root, 8);
        Button logs = button("查看识别与过滤日志", 0xFF344054);
        root.addView(logs);
        logs.setOnClickListener(v -> showLogs());

        addGap(root, 18);
        TextView safety = label("安全策略：只识别明确的“跳过 / Skip / 关闭广告”控件。购买、下载、安装、授权、支付、订阅、登录、同意等操作会被拦截；不确定时不点击。", 13, 0xFF475467, false);
        root.addView(safety);
        addGap(root, 12);
        TextView limitation = label("说明：DNS 模块过滤发往本地虚拟 DNS 的 IPv4/IPv6 UDP/53，并对上游返回 TC=1 的截断响应尝试 TCP 重试；尚不代理客户端主动发起的 TCP/53 DNS 会话，也无法过滤 DoH/DoT、硬编码 IP 或所有应用内广告。启动 VPN 会占用 Android 单 VPN 通道。", 12, 0xFF667085, false);
        root.addView(limitation);

        setContentView(scroll);
        refreshStatus();
    }

    private void refreshStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        boolean dns = DnsFilterVpnService.isRunning();
        serviceStatus.setText(getString(R.string.service_status,
                getString(enabled ? R.string.accessibility_enabled : R.string.accessibility_disabled),
                getString(dns ? R.string.dns_running : R.string.dns_stopped)));
        List<String> apps = database.getEnabledApps();
        appRuleStatus.setText(apps.isEmpty() ? "尚未选择应用。默认不对任何第三方 App 自动点击。"
                : "已启用 " + apps.size() + " 个应用：" + String.join("、", apps));
        List<String> domains = database.getBlockedDomains();
        domainRuleStatus.setText(domains.isEmpty() ? "尚未添加过滤域名。建议先使用少量自定义规则测试。"
                : "已配置 " + domains.size() + " 条域名规则；过滤采用域名后缀匹配。");
        if (statsStatus != null) statsStatus.setText(database.getStats24h().toDisplayText());
        if (privilegeManager != null) updatePrivilegeStatus(privilegeManager.refresh().toDisplayText());
    }

    private void updatePrivilegeStatus(String value) {
        if (privilegeStatus != null) privilegeStatus.setText(value);
    }

    private void connectShizuku() {
        if (!isShizukuInstalled()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要先安装 Shizuku")
                    .setMessage("AdSkip 不会自动下载或安装 Shizuku。请从 Shizuku 官方网站安装并启动服务，然后返回这里连接。非 Root 设备可使用 Android 无线调试启动。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("打开官方页面", (dialog, which) ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))))
                    .show();
            return;
        }
        if (!privilegeManager.refresh().binderAlive) {
            new AlertDialog.Builder(this)
                    .setTitle("Shizuku 服务尚未运行")
                    .setMessage("请先打开 Shizuku，使用无线调试或 Root 启动 Shizuku 服务，再返回 AdSkip。普通无障碍跳广告功能不受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("打开 Shizuku", (dialog, which) -> openShizukuApp())
                    .show();
            return;
        }
        privilegeManager.requestPermissionOrConnect();
        Toast.makeText(this, "正在检查 Shizuku 授权与诊断服务", Toast.LENGTH_SHORT).show();
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void openShizukuApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "未找到 Shizuku 启动入口，请手动打开 Shizuku", Toast.LENGTH_LONG).show();
    }

    private void showPrivilegeDiagnostics() {
        PrivilegeManager.Status status = privilegeManager.refresh();
        if (!status.canUseEnhancedDiagnostics()) {
            new AlertDialog.Builder(this)
                    .setTitle("增强诊断不可用")
                    .setMessage(status.toDisplayText() + "\n\n请启动 Shizuku、授予 AdSkip 权限并连接 UserService。普通无障碍识别和 DNS 过滤仍可单独使用。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("连接 / 授权", (dialog, which) -> connectShizuku())
                    .show();
            return;
        }
        String[] actions = {"查询已安装第三方应用", "诊断当前前台 Activity", "查询应用包元数据", "只读查询 AppOps"};
        new AlertDialog.Builder(this).setTitle("Shizuku 增强诊断（只读）")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) runPrivilegeQuery("已安装第三方应用", service ->
                            PrivilegeUtils.sanitizePackageList(service.listThirdPartyPackages(), 300));
                    else if (which == 1) runForegroundDiagnostic();
                    else if (which == 2) promptPackageName("查询应用包元数据", pkg ->
                            runPrivilegeQuery("应用包元数据：" + pkg, service -> service.getPackageInfo(pkg)));
                    else if (which == 3) promptPackageName("查询 AppOps（只读）", pkg ->
                            runPrivilegeQuery("AppOps 状态：" + pkg, service -> service.getAppOps(pkg)));
                }).show();
    }

    private void runForegroundDiagnostic() {
        runPrivilegeQuery("当前前台 Activity 诊断", service -> {
            String shell = service.getForegroundActivity();
            String local = getLastAccessibilityWindow();
            return "【Shizuku / Shell dumpsys】\n" + shell + "\n\n【无障碍最近窗口事件】\n" + local;
        });
    }

    private String getLastAccessibilityWindow() {
        android.content.SharedPreferences prefs = getSharedPreferences("adskip_settings", MODE_PRIVATE);
        String packageName = prefs.getString("foreground_last_package", "");
        String className = prefs.getString("foreground_last_class", "");
        long at = prefs.getLong("foreground_last_at", 0L);
        if (packageName.isEmpty() || at <= 0) return "暂无记录。请启用 AdSkip 无障碍服务后切换到目标应用。";
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - at) / 1000L);
        return "包名：" + packageName + "\nActivity：" + (className.isEmpty() ? "系统未提供" : className)
                + "\n距上次窗口事件：" + ageSeconds + " 秒";
    }

    private interface ShellQuery { String execute(IAdSkipShellService service) throws Exception; }

    private void runPrivilegeQuery(String title, ShellQuery query) {
        TextView loading = label("正在通过 Shizuku 执行只读诊断…", 14, 0xFF475467, false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(loading)
                .setNegativeButton("关闭", null).show();
        privilegeExecutor.execute(() -> {
            String result;
            try {
                IAdSkipShellService service = privilegeManager.requireShellService();
                result = query.execute(service);
                result = PrivilegeUtils.normalizeDiagnosticOutput(result, 16000);
            } catch (Exception e) {
                privilegeManager.handleDiagnosticFailure(e);
                result = "诊断未完成：" + e.getClass().getSimpleName() + "\n" + String.valueOf(e.getMessage())
                        + "\n\n增强模式已停止本次请求并回退普通模式；无障碍识别不受影响。";
            }
            final String output = result;
            mainHandler.post(() -> {
                if (activityDestroyed || isFinishing()) return;
                if (dialog.isShowing()) dialog.dismiss();
                showDiagnosticResult(title, output);
            });
        });
    }

    private void promptPackageName(String title, Consumer<String> onSubmit) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("例如 com.example.app");
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage("只读取所填应用的包元数据或 AppOps 状态，不会修改权限。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("查询", (dialog, which) -> {
                    String pkg = input.getText().toString().trim();
                    if (!PrivilegeUtils.isValidPackageName(pkg)) {
                        Toast.makeText(this, "包名格式无效", Toast.LENGTH_LONG).show();
                        return;
                    }
                    onSubmit.accept(pkg);
                }).show();
    }

    private void showDiagnosticResult(String title, String output) {
        TextView body = label(output, 12, 0xFF344054, false);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setPositiveButton("关闭", null).show();
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;

            String services = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null || services.isEmpty()) return false;

            android.content.ComponentName expected = new android.content.ComponentName(this,
                    com.aeginning.adskip.service.AdSkipAccessibilityService.class);
            // Android stores a colon-separated list of flattened component names.
            // Parse each component instead of using substring matching.
            for (String entry : services.split(":")) {
                android.content.ComponentName enabledComponent =
                        android.content.ComponentName.unflattenFromString(entry.trim());
                if (expected.equals(enabledComponent)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void showAppRulesDialog() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = getPackageManager().queryIntentActivities(launcher, 0);
        activities.sort((a, b) -> String.valueOf(a.loadLabel(getPackageManager()))
                .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager()))));
        List<ResolveInfo> filtered = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            if (seenPackages.add(info.activityInfo.packageName)) filtered.add(info);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        Set<String> current = new HashSet<>(database.getEnabledApps());
        List<CheckBox> checks = new ArrayList<>();

        CheckBox selectAll = new CheckBox(this);
        selectAll.setText("全选当前列表（" + filtered.size() + " 个应用）");
        selectAll.setTextSize(14);
        selectAll.setEnabled(!filtered.isEmpty());
        list.addView(selectAll);

        for (ResolveInfo info : filtered) {
            CheckBox check = new CheckBox(this);
            check.setText(getString(R.string.app_checkbox_label,
                    info.loadLabel(getPackageManager()), info.activityInfo.packageName));
            check.setTextSize(14);
            check.setTag(info.activityInfo.packageName);
            check.setChecked(current.contains(info.activityInfo.packageName));
            list.addView(check);
            checks.add(check);
        }

        boolean initiallyAllChecked = !checks.isEmpty();
        for (CheckBox check : checks) {
            if (!check.isChecked()) {
                initiallyAllChecked = false;
                break;
            }
        }
        selectAll.setChecked(initiallyAllChecked);

        final boolean[] synchronizingSelection = {false};
        selectAll.setOnCheckedChangeListener((buttonView, checked) -> {
            if (synchronizingSelection[0]) return;
            synchronizingSelection[0] = true;
            for (CheckBox item : checks) item.setChecked(checked);
            synchronizingSelection[0] = false;
        });
        for (CheckBox check : checks) {
            check.setOnCheckedChangeListener((buttonView, checked) -> {
                if (synchronizingSelection[0]) return;
                boolean allChecked = !checks.isEmpty();
                for (CheckBox item : checks) {
                    if (!item.isChecked()) {
                        allChecked = false;
                        break;
                    }
                }
                synchronizingSelection[0] = true;
                selectAll.setChecked(allChecked);
                synchronizingSelection[0] = false;
            });
        }

        scroll.addView(list);
        new AlertDialog.Builder(this).setTitle("选择启用自动跳广告的应用")
                .setMessage("仅列出桌面启动器应用。可使用顶部“全选当前列表”，建议先选择少量应用测试。")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    for (CheckBox check : checks) {
                        database.setAppEnabled((String) check.getTag(), check.isChecked());
                    }
                    refreshStatus();
                }).show();
    }

    private void showDomainRulesDialog() {
        EditText input = new EditText(this);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(7);
        input.setMaxLines(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("每行一个域名，例如：\nad.example.com\ntracker.example.net");
        input.setText(String.join("\n", database.getBlockedDomains()));
        new AlertDialog.Builder(this).setTitle("DNS 域名黑名单")
                .setMessage("已预置常见广告投放、广告联盟及部分追踪域名，涵盖 Google、Microsoft、Amazon、TikTok、腾讯、百度、阿里和常见广告网络。可按需删除或增补。规则会匹配该域名及其子域名；误屏蔽可能导致 App 广告、统计或部分功能异常。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String[] lines = input.getText().toString().split("[\\r\\n,;]+", -1);
                    List<String> rules = new ArrayList<>();
                    for (String line : lines) if (!line.trim().isEmpty()) rules.add(line.trim());
                    database.replaceDomainRules(rules);
                    refreshStatus();
                }).show();
    }


    private void showAccessibilityDisclosure() {
        if (getSharedPreferences("adskip_settings", MODE_PRIVATE).getBoolean("accessibility_disclosure_accepted", false)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        String message = "AdSkip 需要无障碍权限，以便在你明确选择的 App 中读取当前界面的可见控件文字、描述和位置，寻找明确的跳过广告按钮。Android 11 及以上还可对当前屏幕截图并使用本地 OCR 识别按钮。\n\n"
                + "识别内容只在本机内存中处理，不上传服务器；本地 SQLite 仅保存你选择的 App 规则、匹配到的按钮标签和操作日志。不会提取可编辑输入控件文本；检测到密码或其他可编辑输入控件时会停用 OCR 截图识别。不录制音频，也不会尝试点击购买、支付、下载、安装、授权、订阅、登录或同意按钮。无法确定时不会点击。\n\n"
                + "启用后仍可随时在 Android 无障碍设置中关闭服务。请只为你信任的 App 启用规则。";
        new AlertDialog.Builder(this).setTitle("无障碍权限用途与数据说明")
                .setMessage(message)
                .setNegativeButton("不同意", null)
                .setPositiveButton("我已阅读并同意", (dialog, which) -> {
                    getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                            .putBoolean("accessibility_disclosure_accepted", true).apply();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }).show();
    }

    private void showDnsDisclosure() {
        if (database.getBlockedDomains().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("尚未设置 DNS 规则")
                    .setMessage("当前 DNS 黑名单为空。首次安装或升级时会提供一组可编辑的常见广告域名默认规则；如果你已清空规则，请先在“管理 DNS 域名规则”中添加确认需要拦截的域名。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("管理规则", (dialog, which) -> showDomainRulesDialog())
                    .show();
            return;
        }
        if (getSharedPreferences("adskip_settings", MODE_PRIVATE).getBoolean("dns_disclosure_accepted", false)) {
            requestVpnPermission();
            return;
        }
        String message = "DNS 过滤需要 Android 系统 VPN 授权。AdSkip 将 IPv4/IPv6 的本地虚拟 DNS 请求送入本地过滤器，不转发普通网页流量。\n\n"
                + "命中黑名单的域名会返回 NXDOMAIN；未命中的查询会按顺序尝试 Cloudflare / Google 的 IPv4、IPv6 DNS。上游 UDP 响应被截断时，会尝试使用 DNS-over-TCP 重试。因此这些 DNS 提供商可能看到未被拦截的域名查询。命中黑名单及上游错误的域名会记录在本机日志中，日志仅用于排查过滤结果，可在日志页面清除；不会上传。\n\n"
                + "注意：当前只处理进入虚拟 DNS 的 UDP 查询；上游 TCP 仅用于截断响应重试，不代理客户端直接发起的 TCP/53 会话，也不拦截 DoH / DoT。启用后会占用 Android 单 VPN 通道，不能与其他 VPN 同时运行。你可以随时点击停止按钮或在系统 VPN 设置中断开。";
        new AlertDialog.Builder(this).setTitle("DNS VPN 数据流向说明")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("我已阅读并同意", (dialog, which) -> {
                    getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                            .putBoolean("dns_disclosure_accepted", true).apply();
                    requestVpnPermission();
                }).show();
    }

    private void requestVpnPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        continueVpnAuthorization();
    }

    private void continueVpnAuthorization() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, REQUEST_VPN);
        else startDnsService();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length == 0 || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知权限未授予；VPN 前台服务仍会由系统显示运行状态。", Toast.LENGTH_LONG).show();
            }
            continueVpnAuthorization();
        }
    }

    private void startDnsService() {
        Intent start = new Intent(this, DnsFilterVpnService.class).setAction(DnsFilterVpnService.ACTION_START);
        startForegroundService(start);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) startDnsService();
            else Toast.makeText(this, "未授予 VPN 权限，DNS 过滤未启动", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (serviceStatus != null) refreshStatus();
        if (privilegeManager != null) {
            PrivilegeManager.Status status = privilegeManager.refresh();
            if (status.binderAlive && status.permissionGranted && !status.userServiceConnected) {
                privilegeManager.bindUserService();
            }
        }
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        if (privilegeManager != null) privilegeManager.stop();
        privilegeExecutor.shutdownNow();
        if (database != null) database.close();
        super.onDestroy();
    }


    private void showRuleLearningDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setHint("目标应用包名，例如 com.example.app");
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText phraseInput = new EditText(this);
        phraseInput.setSingleLine(true);
        phraseInput.setHint("误判词，例如：Skip");
        content.addView(packageInput);
        content.addView(phraseInput);
        new AlertDialog.Builder(this).setTitle("添加本地误判屏蔽规则")
                .setMessage("如果某个 App 中的文字被误认为跳过按钮，可在这里按包名屏蔽该短语。规则只在本机生效，不会影响其他 App。不要添加支付、授权等敏感词作为跳过规则。")
                .setView(content)
                .setNegativeButton("取消", null)
                .setNeutralButton("查看 / 删除已有规则", (dialog, which) -> {
                    String pkg = packageInput.getText().toString().trim();
                    if (!PrivilegeUtils.isValidPackageName(pkg)) {
                        Toast.makeText(this, "请先填写正确的包名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<String> rules = database.getSuppressedLabels(pkg);
                    if (rules.isEmpty()) {
                        new AlertDialog.Builder(this).setMessage("该应用还没有误判屏蔽规则。").setPositiveButton("关闭", null).show();
                        return;
                    }
                    String[] values = rules.toArray(new String[0]);
                    new AlertDialog.Builder(this).setTitle("选择要删除的屏蔽词")
                            .setItems(values, (d, index) -> {
                                database.removeSuppressedLabel(pkg, values[index]);
                                Toast.makeText(this, "已删除规则：" + values[index], Toast.LENGTH_SHORT).show();
                            }).setNegativeButton("关闭", null).show();
                })
                .setPositiveButton("保存屏蔽词", (dialog, which) -> {
                    String pkg = packageInput.getText().toString().trim();
                    String phrase = phraseInput.getText().toString().trim();
                    if (!PrivilegeUtils.isValidPackageName(pkg) || !database.addSuppressedLabel(pkg, phrase)) {
                        Toast.makeText(this, "包名或屏蔽词无效；请检查长度与内容", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "误判屏蔽规则已保存到本机", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void showCompatibilityReport() {
        StringBuilder report = new StringBuilder();
        report.append("Android：").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        report.append("系统构建：").append(Build.DISPLAY).append("\n");
        report.append("OCR 截图能力：").append(Build.VERSION.SDK_INT >= 30 ? "支持无障碍截图 API" : "不支持，使用控件树识别").append("\n");
        report.append("无障碍服务：").append(isAccessibilityServiceEnabled() ? "已启用" : "未启用").append("\n");
        report.append("DNS VPN：").append(DnsFilterVpnService.isRunning() ? "运行中" : "未运行").append("\n");
        report.append("Shizuku：\n").append(privilegeManager == null ? "未初始化" : privilegeManager.refresh().toDisplayText()).append("\n\n");
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        report.append("AdSkip Java 堆：约 ").append(usedMb).append(" MB / 最大 ").append(maxMb).append(" MB\n");
        report.append("当前已启用应用：").append(database.getEnabledApps().size()).append(" 个\n");
        report.append("DNS 域名规则：").append(database.getBlockedDomains().size()).append(" 条\n");
        report.append("近 24 小时统计：").append(database.getStats24h().toDisplayText()).append("\n\n");
        report.append("说明：此报告是本机能力快照，不代表广告识别一定成功。厂商 ROM、其他 VPN、安全窗口、DRM 和后台限制可能影响功能。诊断内容不会自动上传。");
        showDiagnosticResult("设备兼容性报告", report.toString());
    }

    private void showLogs() {
        List<String> logs = database.getRecentLogs(100);
        String content = logs.isEmpty() ? "暂无日志。启用无障碍服务并选择测试应用后，识别与安全拦截记录会显示在这里。"
                : String.join("\n\n", logs);
        TextView text = label(content, 12, 0xFF344054, false);
        text.setPadding(dp(14), dp(8), dp(14), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("最近 100 条日志")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setNeutralButton("清空日志", (dialog, which) -> database.clearLogs())
                .show();
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundColor(0xFFFFFFFF);
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        return b;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private void addGap(LinearLayout parent, int dp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
