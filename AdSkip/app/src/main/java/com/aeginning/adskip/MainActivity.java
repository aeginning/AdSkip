package com.aeginning.adskip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.aeginning.adskip.db.RuleDatabase;
import com.aeginning.adskip.service.DnsFilterVpnService;
import com.aeginning.adskip.service.DnsFeedManager;

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
    private final ExecutorService dnsFeedExecutor = Executors.newSingleThreadExecutor();
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

    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        // Respect status/navigation bars and display cutouts on modern Android versions.
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
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        root.setBackgroundColor(0xFFF4F6FB);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // Brand header.
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        android.widget.ImageView appIcon = new android.widget.ImageView(this);
        appIcon.setImageResource(R.drawable.ic_adskip);
        brand.addView(appIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams brandTextParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandTextParams.leftMargin = dp(12);
        brand.addView(brandText, brandTextParams);

        TextView title = label(R.string.main_title, 30, 0xFF172033, true);
        brandText.addView(title);
        TextView subtitle = label("智能跳广告助手 · 本地识别 · 安全点击", 13, 0xFF667085, false);
        addGap(brandText, 2);
        brandText.addView(subtitle);

        TextView version = label("VERSION " + getAppVersionName(), 10, 0xFF245BDB, true);
        version.setLetterSpacing(0.06f);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10), dp(7), dp(10), dp(7));
        version.setBackground(roundedDrawable(0xFFEAF0FF, 0, 18));
        brand.addView(version, new LinearLayout.LayoutParams(-2, -2));

        addGap(root, 20);

        // Main runtime overview.
        LinearLayout statusCard = card(root);
        LinearLayout statusHeading = new LinearLayout(this);
        statusHeading.setOrientation(LinearLayout.HORIZONTAL);
        statusHeading.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.addView(statusHeading, new LinearLayout.LayoutParams(-1, -2));

        TextView statusTitle = label("运行概览", 19, 0xFF172033, true);
        statusHeading.addView(statusTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView localBadge = label("本机运行", 11, 0xFF16804A, true);
        localBadge.setGravity(Gravity.CENTER);
        localBadge.setPadding(dp(9), dp(5), dp(9), dp(5));
        localBadge.setBackground(roundedDrawable(0xFFE9F7EF, 0, 16));
        statusHeading.addView(localBadge);

        serviceStatus = label("正在检查服务状态…", 14, 0xFF344054, false);
        serviceStatus.setLineSpacing(dp(4), 1.0f);
        serviceStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        serviceStatus.setBackground(roundedDrawable(0xFFF3F6FC, 0xFFE5EAF3, 13));
        addGap(statusCard, 12);
        statusCard.addView(serviceStatus);

        addGap(statusCard, 14);
        Button accessibility = button("开启 / 管理无障碍服务", 0xFF245BDB);
        statusCard.addView(accessibility, new LinearLayout.LayoutParams(-1, dp(52)));
        accessibility.setOnClickListener(v -> showAccessibilityDisclosure());

        TextView statusHint = label("无障碍负责识别明确的跳过按钮；DNS 过滤需要单独启动并获得 VPN 授权。", 12, 0xFF7A8495, false);
        addGap(statusCard, 9);
        statusCard.addView(statusHint);

        addGap(root, 14);

        // Optional floating status panel. It is hosted by the accessibility service,
        // so no SYSTEM_ALERT_WINDOW permission or separate foreground service is needed.
        LinearLayout overlayCard = card(root);
        overlayCard.addView(label("状态悬浮窗", 18, 0xFF172033, true));
        TextView overlayHint = label(
                "在其他应用上方显示 AdSkip 运行状态。可拖动顶部移动位置、收起或关闭；无障碍服务停止时悬浮窗会自动消失。无需额外悬浮窗权限。",
                12, 0xFF7A8495, false);
        overlayHint.setLineSpacing(dp(3), 1.0f);
        addGap(overlayCard, 5);
        overlayCard.addView(overlayHint);

        android.widget.Switch overlayMaster = addOverlayPreferenceSwitch(
                overlayCard, "启用状态悬浮窗", "floating_overlay_enabled", false);
        android.widget.Switch overlayShowSkip = addOverlayPreferenceSwitch(
                overlayCard, "显示广告跳过启用状态", "floating_overlay_show_skip", true);
        android.widget.Switch overlayShowApps = addOverlayPreferenceSwitch(
                overlayCard, "显示已启用应用数量", "floating_overlay_show_apps", true);
        android.widget.Switch overlayShowDns = addOverlayPreferenceSwitch(
                overlayCard, "显示 DNS 过滤状态", "floating_overlay_show_dns", true);
        android.widget.Switch overlayShowClicks = addOverlayPreferenceSwitch(
                overlayCard, "显示近 24 小时自动点击次数", "floating_overlay_show_clicks", true);
        android.widget.Switch overlayShowOcr = addOverlayPreferenceSwitch(
                overlayCard, "显示 OCR 辅助识别状态", "floating_overlay_show_ocr", true);
        android.widget.Switch overlayShowDnsRules = addOverlayPreferenceSwitch(
                overlayCard, "显示当前 DNS 规则数量", "floating_overlay_show_dns_rules", true);
        android.widget.Switch overlayShowSafetyBlocks = addOverlayPreferenceSwitch(
                overlayCard, "显示近 24 小时安全拦截次数", "floating_overlay_show_safety_blocks", true);
        android.widget.Switch overlayShowDnsBlocks = addOverlayPreferenceSwitch(
                overlayCard, "显示近 24 小时 DNS 拦截次数", "floating_overlay_show_dns_blocks", true);
        android.widget.Switch overlayShowNoAction = addOverlayPreferenceSwitch(
                overlayCard, "显示近 24 小时未执行次数", "floating_overlay_show_no_action", false);
        android.widget.Switch overlayShowErrors = addOverlayPreferenceSwitch(
                overlayCard, "显示近 24 小时错误次数", "floating_overlay_show_errors", false);
        android.widget.Switch[] overlayOptions = {
                overlayShowSkip, overlayShowApps, overlayShowDns, overlayShowClicks,
                overlayShowOcr, overlayShowDnsRules, overlayShowSafetyBlocks, overlayShowDnsBlocks,
                overlayShowNoAction, overlayShowErrors
        };
        Runnable syncOverlayOptions = () -> {
            boolean enabled = overlayMaster.isChecked();
            for (android.widget.Switch option : overlayOptions) option.setEnabled(enabled);
        };
        syncOverlayOptions.run();
        overlayMaster.setOnCheckedChangeListener((buttonView, checked) -> {
            syncOverlayOptions.run();
            getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                    .putBoolean("floating_overlay_enabled", checked).apply();
            if (checked && !isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "已保存悬浮窗设置；开启无障碍服务后才会显示。", Toast.LENGTH_LONG).show();
            } else if (checked) {
                Toast.makeText(this, "状态悬浮窗已开启，可在其他应用上方查看。", Toast.LENGTH_SHORT).show();
            }
        });

        addGap(overlayCard, 8);
        TextView sizeHeading = label("悬浮窗尺寸自定义", 14, 0xFF344054, true);
        overlayCard.addView(sizeHeading);
        TextView sizeHint = label(
                "默认已缩小；内容较多时可在悬浮窗内部上下滑动。宽度、高度和文字大小均可独立调整。",
                11, 0xFF7A8495, false);
        addGap(overlayCard, 3);
        overlayCard.addView(sizeHint);
        addOverlaySizeControl(overlayCard, "悬浮窗宽度", "floating_overlay_width_dp",
                150, 320, 178, "dp");
        addOverlaySizeControl(overlayCard, "悬浮窗高度", "floating_overlay_height_dp",
                120, 460, 190, "dp");
        addOverlaySizeControl(overlayCard, "悬浮窗文字大小", "floating_overlay_text_size_sp",
                9, 16, 10, "sp");

        addGap(root, 14);

        // App automation and false-positive controls.
        LinearLayout appCard = card(root);
        appCard.addView(label("应用自动化", 18, 0xFF172033, true));
        TextView appHint = label("仅对你明确勾选的桌面应用启用识别与安全点击。默认不对其他应用自动操作。", 12, 0xFF7A8495, false);
        addGap(appCard, 5);
        appCard.addView(appHint);
        appRuleStatus = label("尚未选择应用。默认不对任何第三方 App 自动点击。", 13, 0xFF475467, false);
        appRuleStatus.setLineSpacing(dp(3), 1.0f);
        addGap(appCard, 11);
        appCard.addView(appRuleStatus);

        addGap(appCard, 12);
        Button chooseApps = button("选择需要自动跳过广告的 App", 0xFF245BDB);
        appCard.addView(chooseApps, new LinearLayout.LayoutParams(-1, dp(50)));
        chooseApps.setOnClickListener(v -> showAppRulesDialog());

        addGap(appCard, 8);
        Button falsePositiveRules = button("管理误判屏蔽规则", 0xFFF0F3F8);
        falsePositiveRules.setTextColor(0xFF344054);
        appCard.addView(falsePositiveRules, new LinearLayout.LayoutParams(-1, dp(48)));
        falsePositiveRules.setOnClickListener(v -> showRuleLearningDialog());

        addGap(root, 14);

        // Local activity and diagnostics.
        LinearLayout statsCard = card(root);
        statsCard.addView(label("本地运行统计", 18, 0xFF172033, true));
        TextView statsHint = label("自动识别与 DNS 过滤的近 24 小时记录", 12, 0xFF7A8495, false);
        addGap(statsCard, 4);
        statsCard.addView(statsHint);
        statsStatus = label("正在统计近 24 小时数据…", 13, 0xFF475467, false);
        statsStatus.setLineSpacing(dp(3), 1.0f);
        statsStatus.setPadding(dp(12), dp(11), dp(12), dp(11));
        statsStatus.setBackground(roundedDrawable(0xFFF6F8FC, 0xFFE9EDF4, 12));
        addGap(statsCard, 11);
        statsCard.addView(statsStatus);

        addGap(statsCard, 10);
        Button compatibility = button("设备兼容性与诊断报告", 0xFFF0F3F8);
        compatibility.setTextColor(0xFF344054);
        statsCard.addView(compatibility, new LinearLayout.LayoutParams(-1, dp(48)));
        compatibility.setOnClickListener(v -> showCompatibilityReport());

        addGap(root, 14);

        // Optional Shizuku / ADB diagnostics.
        LinearLayout privilegeCard = card(root);
        privilegeCard.addView(label("Shizuku / ADB 增强模式", 18, 0xFF172033, true));
        TextView privilegeHint = label(
                "可选只读诊断：查询已安装应用、前台 Activity、应用包元数据和 AppOps。不会修改权限、禁用应用或执行任意 Shell 命令。Shizuku 断开后，普通无障碍模式继续运行。",
                12, 0xFF7A8495, false);
        privilegeHint.setLineSpacing(dp(3), 1.0f);
        addGap(privilegeCard, 5);
        privilegeCard.addView(privilegeHint);
        privilegeStatus = label("正在检查 Shizuku Binder…", 12, 0xFF475467, false);
        privilegeStatus.setLineSpacing(dp(3), 1.0f);
        privilegeStatus.setPadding(dp(12), dp(11), dp(12), dp(11));
        privilegeStatus.setBackground(roundedDrawable(0xFFF6F8FC, 0xFFE9EDF4, 12));
        addGap(privilegeCard, 11);
        privilegeCard.addView(privilegeStatus);

        addGap(privilegeCard, 10);
        Button connectPrivilege = button("连接 Shizuku / 请求授权", 0xFF245BDB);
        privilegeCard.addView(connectPrivilege, new LinearLayout.LayoutParams(-1, dp(50)));
        connectPrivilege.setOnClickListener(v -> connectShizuku());
        addGap(privilegeCard, 8);
        Button runDiagnostics = button("运行增强系统诊断", 0xFFF0F3F8);
        runDiagnostics.setTextColor(0xFF344054);
        privilegeCard.addView(runDiagnostics, new LinearLayout.LayoutParams(-1, dp(48)));
        runDiagnostics.setOnClickListener(v -> showPrivilegeDiagnostics());

        addGap(root, 14);

        // OCR keeps its original preference and callback.
        LinearLayout ocrCard = card(root);
        ocrCard.addView(label("图像识别（OCR）", 18, 0xFF172033, true));
        TextView ocrHint = label("Android 11+ 可使用无障碍截图在本机识别中文和英文；截图不会上传。", 12, 0xFF7A8495, false);
        ocrHint.setLineSpacing(dp(3), 1.0f);
        addGap(ocrCard, 5);
        ocrCard.addView(ocrHint);
        ocrSwitch = new android.widget.Switch(this);
        ocrSwitch.setText(R.string.ocr_switch_label);
        ocrSwitch.setTextSize(14);
        ocrSwitch.setTextColor(0xFF344054);
        ocrSwitch.setPadding(0, dp(8), 0, dp(4));
        ocrSwitch.setChecked(getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getBoolean("ocr_enabled", true));
        ocrSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences("adskip_settings", MODE_PRIVATE)
                        .edit().putBoolean("ocr_enabled", checked).apply());
        addGap(ocrCard, 7);
        ocrCard.addView(ocrSwitch);

        addGap(root, 14);

        // Optional countdown clicking and configurable second-observation text similarity.
        LinearLayout recognitionCard = card(root);
        recognitionCard.addView(label("跳过识别设置", 18, 0xFF172033, true));
        TextView recognitionHint = label(
                "支持倒计时“跳过 + 数字”及广告右上角 X / × 关闭符号识别。灵敏度控制候选触发门槛；二次确认可按需关闭；相似度仅在二次确认开启时生效。",
                12, 0xFF7A8495, false);
        recognitionHint.setLineSpacing(dp(3), 1.0f);
        addGap(recognitionCard, 5);
        recognitionCard.addView(recognitionHint);

        android.widget.Switch countdownClickSwitch = new android.widget.Switch(this);
        countdownClickSwitch.setText("倒计时期间也尝试点击“跳过”");
        countdownClickSwitch.setTextSize(14);
        countdownClickSwitch.setTextColor(0xFF344054);
        countdownClickSwitch.setPadding(0, dp(8), 0, dp(4));
        countdownClickSwitch.setChecked(getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getBoolean("countdown_click_enabled", false));
        countdownClickSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                        .putBoolean("countdown_click_enabled", checked).apply());
        addGap(recognitionCard, 6);
        recognitionCard.addView(countdownClickSwitch);

        android.widget.Switch secondConfirmationSwitch = new android.widget.Switch(this);
        secondConfirmationSwitch.setText("启用二次识别确认（关闭后更快，但误触风险更高）");
        secondConfirmationSwitch.setTextSize(14);
        secondConfirmationSwitch.setTextColor(0xFF344054);
        secondConfirmationSwitch.setPadding(0, dp(8), 0, dp(4));
        secondConfirmationSwitch.setChecked(getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getBoolean("second_confirmation_enabled", true));
        secondConfirmationSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                        .putBoolean("second_confirmation_enabled", checked).apply());
        addGap(recognitionCard, 4);
        recognitionCard.addView(secondConfirmationSwitch);

        TextView sensitivityLabel = label("", 13, 0xFF344054, true);
        int savedSensitivity = getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getInt("skip_button_sensitivity", 50);
        int initialSensitivity = Math.max(0, Math.min(100, savedSensitivity));
        int initialMinimumScore = 90 - Math.round(initialSensitivity * 0.30f);
        sensitivityLabel.setText(getString(R.string.skip_sensitivity_text,
                initialSensitivity, initialMinimumScore));
        addGap(recognitionCard, 9);
        recognitionCard.addView(sensitivityLabel);

        SeekBar sensitivitySeekBar = new SeekBar(this);
        sensitivitySeekBar.setMax(100);
        sensitivitySeekBar.setProgress(initialSensitivity);
        sensitivitySeekBar.setContentDescription("跳过按钮灵敏度，0 到 100，数值越高越容易触发");
        recognitionCard.addView(sensitivitySeekBar, new LinearLayout.LayoutParams(-1, dp(42)));
        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int threshold = 90 - Math.round(progress * 0.30f);
                sensitivityLabel.setText(getString(R.string.skip_sensitivity_text,
                        progress, threshold));
                if (fromUser) getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                        .putInt("skip_button_sensitivity", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        TextView sensitivityHint = label(
                "灵敏度越高，越容易接受普通“跳过”及 OCR 结果；越低越严格。无论如何，广告上下文、控件几何位置、危险操作屏蔽仍然生效。",
                11, 0xFF7A8495, false);
        addGap(recognitionCard, 3);
        recognitionCard.addView(sensitivityHint);

        TextView similarityLabel = label("", 13, 0xFF344054, true);
        int savedSimilarity = getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getInt("recognition_similarity_percent", 85);
        int initialSimilarity = Math.max(60, Math.min(100, savedSimilarity));
        similarityLabel.setText(getString(R.string.recognition_similarity_text,
                initialSimilarity));
        addGap(recognitionCard, 8);
        recognitionCard.addView(similarityLabel);

        SeekBar similaritySeekBar = new SeekBar(this);
        similaritySeekBar.setMax(40);
        similaritySeekBar.setProgress(initialSimilarity - 60);
        similaritySeekBar.setContentDescription("二次识别文字相似度，60% 到 100%");
        recognitionCard.addView(similaritySeekBar, new LinearLayout.LayoutParams(-1, dp(42)));
        similaritySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 60 + progress;
                similarityLabel.setText(getString(R.string.recognition_similarity_text, value));
                if (fromUser) getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                        .putInt("recognition_similarity_percent", value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        TextView similarityHint = label(
                "建议 85%：调低更容易容忍 OCR 识别差异，但可能增加误匹配；调高更严格。范围 60%–100%。",
                11, 0xFF7A8495, false);
        addGap(recognitionCard, 3);
        recognitionCard.addView(similarityHint);

        addGap(root, 14);

        // DNS controls remain individually available.
        LinearLayout dnsCard = card(root);
        dnsCard.addView(label("DNS 广告域名过滤", 18, 0xFF172033, true));
        TextView dnsHint = label("通过本地 DNS 规则拦截已知广告与追踪域名。启用时会占用 Android 单 VPN 通道。", 12, 0xFF7A8495, false);
        dnsHint.setLineSpacing(dp(3), 1.0f);
        addGap(dnsCard, 5);
        dnsCard.addView(dnsHint);
        domainRuleStatus = label("尚未添加过滤域名。建议先使用少量自定义规则测试。", 13, 0xFF475467, false);
        domainRuleStatus.setLineSpacing(dp(3), 1.0f);
        domainRuleStatus.setPadding(dp(12), dp(11), dp(12), dp(11));
        domainRuleStatus.setBackground(roundedDrawable(0xFFF6F8FC, 0xFFE9EDF4, 12));
        addGap(dnsCard, 11);
        dnsCard.addView(domainRuleStatus);

        addGap(dnsCard, 10);
        Button domains = button("管理 DNS 域名规则", 0xFFF0F3F8);
        domains.setTextColor(0xFF344054);
        dnsCard.addView(domains, new LinearLayout.LayoutParams(-1, dp(48)));
        domains.setOnClickListener(v -> showDomainRulesDialog());

        addGap(dnsCard, 8);
        Button feedLibrary = button("广告 DNS 规则库 / 订阅管理", 0xFFF0F3F8);
        feedLibrary.setTextColor(0xFF344054);
        dnsCard.addView(feedLibrary, new LinearLayout.LayoutParams(-1, dp(48)));
        feedLibrary.setOnClickListener(v -> showDnsFeedDialog());

        addGap(dnsCard, 8);
        LinearLayout dnsActions = new LinearLayout(this);
        dnsActions.setOrientation(LinearLayout.HORIZONTAL);
        dnsActions.setGravity(Gravity.CENTER_VERTICAL);
        dnsCard.addView(dnsActions, new LinearLayout.LayoutParams(-1, dp(50)));
        Button startVpn = button("启动 DNS 过滤", 0xFF16804A);
        dnsActions.addView(startVpn, new LinearLayout.LayoutParams(0, -1, 1f));
        startVpn.setOnClickListener(v -> showDnsDisclosure());
        View dnsActionGap = new View(this);
        dnsActions.addView(dnsActionGap, new LinearLayout.LayoutParams(dp(8), 1));
        Button stopVpn = button("停止过滤", 0xFFFCEDEC);
        stopVpn.setTextColor(0xFFB42318);
        dnsActions.addView(stopVpn, new LinearLayout.LayoutParams(0, -1, 1f));
        stopVpn.setOnClickListener(v -> {
            Intent stop = new Intent(this, DnsFilterVpnService.class)
                    .setAction(DnsFilterVpnService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this, "已发送停止请求", Toast.LENGTH_SHORT).show();
        });

        addGap(dnsCard, 8);
        Button logs = button("查看识别与过滤日志", 0xFFF0F3F8);
        logs.setTextColor(0xFF344054);
        dnsCard.addView(logs, new LinearLayout.LayoutParams(-1, dp(48)));
        logs.setOnClickListener(v -> showLogs());

        addGap(root, 14);

        // Safety and network limitations are kept visible, but separated from controls.
        LinearLayout safetyCard = card(root);
        safetyCard.addView(label("安全与隐私说明", 16, 0xFF172033, true));
        TextView safety = label(
                "安全策略：只识别明确的“跳过 / Skip / 关闭广告”控件。购买、下载、安装、授权、支付、订阅、登录、同意等操作会被拦截；不确定时不点击。",
                12, 0xFF475467, false);
        safety.setLineSpacing(dp(3), 1.0f);
        addGap(safetyCard, 7);
        safetyCard.addView(safety);

        TextView limitation = label(
                "DNS 边界：仅过滤进入本地虚拟 DNS 的 IPv4/IPv6 UDP/53 查询，并对上游截断响应尝试 TCP 重试；不代理客户端主动发起的 TCP/53，不过滤 DoH/DoT、硬编码 IP 或所有应用内广告。VPN 与其他 VPN 不能同时运行。",
                11, 0xFF7A8495, false);
        limitation.setLineSpacing(dp(3), 1.0f);
        addGap(safetyCard, 10);
        safetyCard.addView(limitation);

        setContentView(scroll);
        // Light navigation-bar icons require API 27. Keep API 26 readable with a dark bar.
        if (Build.VERSION.SDK_INT >= 27) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            getWindow().setNavigationBarColor(0xFF172033);
        }
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
        int manualDomainCount = database.getManualDomainRuleCount();
        int activeDomainCount = database.getActiveDomainRuleCount();
        List<RuleDatabase.DnsFeedInfo> feeds = database.getDnsFeeds();
        int enabledFeedCount = 0;
        for (RuleDatabase.DnsFeedInfo feed : feeds) if (feed.enabled) enabledFeedCount++;
        domainRuleStatus.setText(activeDomainCount == 0
                ? getString(R.string.dns_status_empty, manualDomainCount, enabledFeedCount, feeds.size())
                : getString(R.string.dns_status_summary, activeDomainCount, manualDomainCount,
                        enabledFeedCount, feeds.size()));
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

        // Keep the complete dialog within the usable screen area. The explanatory
        // text and app list share one bounded custom panel; only the list scrolls.
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxListHeight = Math.min(dp(300), Math.max(dp(160), (int) (screenHeight * 0.34f)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(4));

        TextView description = label(
                "仅列出桌面启动器应用。勾选需要启用的应用；顶部可全选或取消全选。",
                14, 0xFF475467, false);
        description.setLineSpacing(dp(2), 1.0f);
        content.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        Set<String> current = new HashSet<>(database.getEnabledApps());
        List<CheckBox> checks = new ArrayList<>();

        CheckBox selectAll = new CheckBox(this);
        selectAll.setText(getString(R.string.select_all_apps, filtered.size()));
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
        content.addView(scroll);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择启用自动跳广告的应用")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (ignored, which) -> {
                    List<String> packages = new ArrayList<>(checks.size());
                    Set<String> enabledPackages = new HashSet<>();
                    for (CheckBox check : checks) {
                        String packageName = (String) check.getTag();
                        packages.add(packageName);
                        if (check.isChecked()) enabledPackages.add(packageName);
                    }
                    if (!database.updateAppRules(packages, enabledPackages)) {
                        Toast.makeText(this, "保存应用规则失败，原有规则未修改。", Toast.LENGTH_LONG).show();
                        return;
                    }
                    getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                            .putLong("floating_overlay_refresh_nonce", System.currentTimeMillis()).apply();
                    refreshStatus();
                })
                .create();

        // The list has a fixed maximum height from its first measure pass;
        // only the list scrolls while the dialog action buttons remain visible.
        dialog.show();
    }

    private void showDomainRulesDialog() {
        EditText input = new EditText(this);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(7);
        input.setMaxLines(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("每行一个完整域名，例如：\nad.example.com\ntracker.example.net");
        input.setText(String.join("\n", database.getBlockedDomains()));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("DNS 域名黑名单")
                .setMessage("这里只编辑手工域名规则；已下载的订阅规则不会显示在此编辑框，也不会被本次保存覆盖。只接受完整域名（至少包含一个点），规则会匹配该域名及子域名。清空此框只会在二次确认后清除手工规则，不会删除订阅库。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String[] lines = input.getText().toString().split("[\\r\\n,;]+", -1);
                    List<String> rules = new ArrayList<>();
                    for (String line : lines) if (!line.trim().isEmpty()) rules.add(line.trim());

                    RuleDatabase.DomainRuleReplaceResult result = database.replaceDomainRules(rules);
                    if (!result.success) {
                        Toast.makeText(this,
                                "存在 " + result.invalidCount + " 条无效规则，当前 DNS 名单未修改。请检查域名格式。",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (result.savedCount == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle("确认清空 DNS 黑名单")
                                .setMessage("当前输入为空。确认后只删除手工 DNS 域名规则；已下载的订阅规则仍会保留并继续按各自开关生效。")
                                .setNegativeButton("继续编辑", null)
                                .setPositiveButton("确认清空", (confirm, which) -> {
                                    database.clearDomainRules();
                                    dialog.dismiss();
                                    refreshStatus();
                                    Toast.makeText(this, "手工 DNS 规则已清空，订阅规则保留", Toast.LENGTH_SHORT).show();
                                }).show();
                        return;
                    }
                    dialog.dismiss();
                    refreshStatus();
                    Toast.makeText(this, "已保存 " + result.savedCount + " 条 DNS 域名规则", Toast.LENGTH_SHORT).show();
                }));
        dialog.show();
    }


    private void showDnsFeedDialog() {
        DnsFeedManager.Source[] sources = {
                DnsFeedManager.Source.HAGEZI_LIGHT,
                DnsFeedManager.Source.STEVENBLACK_BASE,
                DnsFeedManager.Source.ADAWAY_HOSTS,
                DnsFeedManager.Source.ONEHOSTS_LITE,
                DnsFeedManager.Source.DAN_POLLOCK_HOSTS
        };
        String[] actions = {
                "下载 / 更新 HaGeZi Multi LIGHT（轻量，建议先用）",
                "下载 / 更新 StevenBlack Unified Hosts（覆盖较广）",
                "下载 / 更新 AdAway Official Hosts（移动广告）",
                "下载 / 更新 1Hosts Lite（轻量兼容）",
                "下载 / 更新 Dan Pollock Hosts（独立来源）",
                "管理已下载的 DNS 订阅"
        };
        new AlertDialog.Builder(this)
                .setTitle("广告 DNS 规则库")
                .setItems(actions, (dialog, which) -> {
                    if (which >= 0 && which < sources.length) {
                        confirmDnsFeedDownload(sources[which]);
                    } else {
                        showDnsFeedManagementDialog();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmDnsFeedDownload(DnsFeedManager.Source source) {
        String risk;
        if (source == DnsFeedManager.Source.HAGEZI_LIGHT
                || source == DnsFeedManager.Source.ADAWAY_HOSTS
                || source == DnsFeedManager.Source.ONEHOSTS_LITE) {
            risk = "这是偏兼容性的广告与追踪域名规则。仍可能影响部分 App 的广告、统计或个性化功能；如果出现 App 异常，可以暂停该订阅。";
        } else if (source == DnsFeedManager.Source.DAN_POLLOCK_HOSTS) {
            risk = "这是独立维护的广告与隐私 hosts 名单。规则覆盖范围与维护策略不同，仍可能误拦截合法服务；建议先单独启用并观察。";
        } else {
            risk = "这是覆盖较广的 hosts 聚合名单，可能涉及广告、恶意软件或追踪域名；误拦截风险高于轻量名单。建议先不要与其他大型名单同时启用。";
        }
        new AlertDialog.Builder(this)
                .setTitle("下载 " + source.displayName)
                .setMessage(source.description + "\n\n" + risk
                        + "\n\n规则只在本机解析和保存；下载将连接该规则来源的 HTTPS 地址。更新只替换这一份订阅，不影响手工规则或其他订阅。")
                .setNegativeButton("取消", null)
                .setPositiveButton("下载并导入", (dialog, which) -> downloadAndInstallDnsFeed(source))
                .show();
    }

    private void downloadAndInstallDnsFeed(DnsFeedManager.Source source) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        TextView status = label("正在连接规则来源并校验文件…", 13, 0xFF475467, false);
        status.setPadding(0, dp(12), 0, 0);
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(36)));
        content.addView(status);
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("更新 DNS 订阅")
                .setView(content)
                .setCancelable(false)
                .setNegativeButton("后台继续", (d, w) -> { })
                .create();
        progressDialog.show();

        dnsFeedExecutor.execute(() -> {
            RuleDatabase.DnsFeedImportResult importResult = null;
            int parsedCount = 0;
            String feedVersion = "";
            String failure = null;
            String downloadedFrom = "";
            int downloadAttempts = 0;
            try {
                DnsFeedManager.DownloadedFeed downloaded = DnsFeedManager.download(source,
                        getApplicationContext(),
                        progressText -> mainHandler.post(() -> {
                            if (!activityDestroyed && status.isAttachedToWindow()) {
                                status.setText(progressText);
                            }
                        }));
                parsedCount = downloaded.parsed.domains.size();
                feedVersion = downloaded.parsed.version;
                downloadedFrom = downloaded.downloadedFrom;
                downloadAttempts = downloaded.attempts;
                RuleDatabase workerDb = new RuleDatabase(getApplicationContext());
                try {
                    importResult = workerDb.replaceDnsFeed(
                            source.id, source.displayName, feedVersion, downloaded.parsed.domains);
                } finally {
                    workerDb.close();
                }
                if (!importResult.success) failure = importResult.error;
            } catch (Exception error) {
                failure = (error instanceof DnsFeedManager.DownloadException
                        ? ((DnsFeedManager.DownloadException) error).toUserMessage()
                        : error.getClass().getSimpleName() + ": " + error.getMessage())
                        + "\n当前网络：" + DnsFeedManager.describeActiveNetwork(getApplicationContext());
            }

            final int count = parsedCount;
            final String version = feedVersion;
            final String errorText = failure;
            final String sourceUrl = downloadedFrom;
            final int attempts = downloadAttempts;
            mainHandler.post(() -> {
                if (activityDestroyed || isFinishing()) return;
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (errorText != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("DNS 订阅暂时无法更新")
                            .setMessage(errorText + "\n\n原订阅和手工域名规则均未修改。")
                            .setNegativeButton("关闭", null)
                            .setPositiveButton("重试更新", (d, w) ->
                                    downloadAndInstallDnsFeed(source))
                            .show();
                    return;
                }
                refreshStatus();
                String message = "已导入 " + count + " 条有效域名"
                        + (version.isEmpty() ? "" : "（版本 " + version + "）")
                        + "。本次使用 " + (sourceUrl.isEmpty() ? "备用线路" : Uri.parse(sourceUrl).getHost())
                        + "，请求 " + attempts + " 次。原有手工规则和其他订阅均已保留。";
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                showDnsFeedManagementDialog();
            });
        });
    }

    private void showDnsFeedManagementDialog() {
        List<RuleDatabase.DnsFeedInfo> feeds = database.getDnsFeeds();
        if (feeds.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("DNS 订阅")
                    .setMessage("目前没有已下载的订阅。手工域名规则仍可在“管理 DNS 域名规则”中独立维护。")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }

        String[] labels = new String[feeds.size()];
        for (int i = 0; i < feeds.size(); i++) labels[i] = feeds.get(i).toDisplayText();
        new AlertDialog.Builder(this)
                .setTitle("已下载的 DNS 订阅")
                .setItems(labels, (dialog, which) -> showDnsFeedActions(feeds.get(which)))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showDnsFeedActions(RuleDatabase.DnsFeedInfo feed) {
        String toggleLabel = feed.enabled ? "暂停此订阅" : "启用此订阅";
        String[] actions = {toggleLabel, "删除此订阅", "关闭"};
        new AlertDialog.Builder(this)
                .setTitle(feed.feedName)
                .setMessage(feed.toDisplayText() + (feed.version.isEmpty() ? "" : "\n版本：" + feed.version))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        boolean updated = database.setDnsFeedEnabled(feed.feedId, !feed.enabled);
                        Toast.makeText(this, updated ? "订阅状态已更新" : "更新订阅状态失败",
                                Toast.LENGTH_SHORT).show();
                        refreshStatus();
                        showDnsFeedManagementDialog();
                    } else if (which == 1) {
                        new AlertDialog.Builder(this)
                                .setTitle("删除 DNS 订阅？")
                                .setMessage("只删除“" + feed.feedName + "”下载的域名，不会删除手工规则或其他订阅。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除订阅", (d, w) -> {
                                    boolean removed = database.removeDnsFeed(feed.feedId);
                                    Toast.makeText(this, removed ? "订阅已删除" : "删除订阅失败",
                                            Toast.LENGTH_SHORT).show();
                                    refreshStatus();
                                    showDnsFeedManagementDialog();
                                }).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAccessibilityDisclosure() {
        if (getSharedPreferences("adskip_settings", MODE_PRIVATE).getBoolean("accessibility_disclosure_accepted", false)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        String message = "AdSkip 需要无障碍权限，以便在你明确选择的 App 中读取当前界面的可见控件文字、描述和位置，寻找明确的跳过广告按钮。Android 11 及以上还可对当前屏幕截图并使用本地 OCR 识别按钮。\n\n"
                + "识别内容只在本机内存中处理，不上传服务器；本地 SQLite 仅保存你选择的 App 规则、匹配到的按钮标签和操作日志。不会读取或保存输入框中的输入值；普通搜索框不会中止页面识别。密码、支付密码、银行卡信息或验证码等敏感区域会被标记避让，区域及邻近范围内不会执行节点点击；为避免截图读取输入内容，检测到敏感区域时会暂停整屏 OCR。不录制音频，也不会尝试点击购买、支付、下载、安装、授权、订阅、登录或同意按钮。无法确定时不会点击。\n\n"
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
        if (database.getActiveDomainRuleCount() == 0) {
            new AlertDialog.Builder(this).setTitle("尚未设置 DNS 规则")
                    .setMessage("当前没有启用的 DNS 规则。你可以添加手工域名，或先下载 HaGeZi / StevenBlack 订阅。DNS 订阅与手工规则分开保存。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("下载规则库", (dialog, which) -> showDnsFeedDialog())
                    .setPositiveButton("管理手工规则", (dialog, which) -> showDomainRulesDialog())
                    .show();
            return;
        }
        if (getSharedPreferences("adskip_settings", MODE_PRIVATE).getBoolean("dns_disclosure_accepted_v2", false)) {
            requestVpnPermission();
            return;
        }
        String message = "DNS 过滤需要 Android 系统 VPN 授权。AdSkip 将 IPv4/IPv6 的本地虚拟 DNS 请求送入本地过滤器，不转发普通网页流量。\n\n"
                + "命中手工名单或已启用订阅的域名会返回 NXDOMAIN；未命中的查询会轮换尝试 Cloudflare、Google、Quad9 的 IPv4/IPv6 DNS。上游 UDP 响应被截断时，会尝试使用 DNS-over-TCP 重试。因此这些 DNS 提供商可能看到未被拦截的域名查询。命中黑名单及上游错误的域名会记录在本机日志中，重复域名日志会节流；日志仅用于排查过滤结果，可在日志页面清除，不会上传。\n\n"
                + "注意：当前只处理进入虚拟 DNS 的 UDP 查询；上游 TCP 仅用于截断响应重试，不代理客户端直接发起的 TCP/53 会话，也不拦截 DoH / DoT。启用后会占用 Android 单 VPN 通道，不能与其他 VPN 同时运行。你可以随时点击停止按钮或在系统 VPN 设置中断开。";
        new AlertDialog.Builder(this).setTitle("DNS VPN 数据流向说明")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("我已阅读并同意", (dialog, which) -> {
                    getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                            .putBoolean("dns_disclosure_accepted", true)
                            .putBoolean("dns_disclosure_accepted_v2", true).apply();
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
        if (grantResults.length == 0 || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "通知权限未授予；VPN 前台服务仍会由系统显示运行状态。", Toast.LENGTH_LONG).show();
        }
        continueVpnAuthorization();
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
        dnsFeedExecutor.shutdownNow();
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
        report.append("DNS 手工规则：").append(database.getManualDomainRuleCount()).append(" 条\n");
        report.append("DNS 生效规则：").append(database.getActiveDomainRuleCount()).append(" 条\n");
        report.append("DNS 订阅源：").append(database.getDnsFeeds().size()).append(" 个\n");
        report.append("近 24 小时统计：").append(database.getStats24h().toDisplayText()).append("\n\n");
        report.append("说明：此报告是本机能力快照，不代表广告识别一定成功。厂商 ROM、其他 VPN、安全窗口、DRM 和后台限制可能影响功能。诊断内容不会自动上传。");
        showDiagnosticResult("设备兼容性报告", report.toString());
    }

    private void showLogs() {
        List<String> logs = database.getRecentLogs(100);
        String content = logs.isEmpty()
                ? "当前还没有运行记录。先开启无障碍服务，在“应用自动化”中勾选要测试的应用，再打开该应用页面。\\n\\n服务连接、扫描开始、无候选、敏感区域保护、OCR 失败、安全拦截、手势结果和点击后验证都会写入本机诊断日志。"
                : "当前页面显示最近 100 条；点击“一键复制全部日志”会复制数据库中当前保留的全部日志。\\n诊断日志会说明每次未点击的原因；不会保存输入框内容或整屏 OCR 文本。\\n\\n" + String.join("\\n\\n", logs);
        TextView text = label(content, 12, 0xFF344054, false);
        text.setPadding(dp(14), dp(8), dp(14), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("识别诊断与过滤日志 · 最近 100 条")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setNeutralButton("清空日志", null)
                .setPositiveButton("一键复制全部日志", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> copyAllLogs());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("确认清空日志")
                            .setMessage("此操作会删除本机当前保存的全部识别与 DNS 过滤日志，且无法恢复。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("确认清空", (confirm, which) -> {
                                database.clearLogs();
                                dialog.dismiss();
                                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                            })
                            .show());
        });
        dialog.show();
    }

    private void copyAllLogs() {
        List<String> logs = database.getAllLogs();
        if (logs.isEmpty()) {
            Toast.makeText(this, "当前没有日志可复制", Toast.LENGTH_SHORT).show();
            return;
        }

        String content = "AdSkip 识别诊断与过滤日志（共 " + logs.size() + " 条）\\n\\n"
                + String.join("\\n\\n", logs);
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) {
                Toast.makeText(this, "无法访问系统剪贴板", Toast.LENGTH_LONG).show();
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("AdSkip 全部日志", content));
            Toast.makeText(this, "已复制全部 " + logs.size() + " 条日志", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制日志失败：" + e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addOverlaySizeControl(LinearLayout parent, String title,
                                       String preferenceKey, int min, int max,
                                       int defaultValue, String unit) {
        TextView valueLabel = label("", 12, 0xFF475467, true);
        int saved = getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getInt(preferenceKey, defaultValue);
        int initial = Math.max(min, Math.min(max, saved));
        valueLabel.setText(getString(R.string.floating_overlay_size_value, title, initial, unit));
        addGap(parent, 5);
        parent.addView(valueLabel);

        SeekBar control = new SeekBar(this);
        control.setMax(max - min);
        control.setProgress(initial - min);
        control.setContentDescription(getString(R.string.floating_overlay_size_description,
                title, min, max, unit));
        parent.addView(control, new LinearLayout.LayoutParams(-1, dp(38)));
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                valueLabel.setText(getString(R.string.floating_overlay_size_value, title, value, unit));
                if (fromUser) getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                        .putInt(preferenceKey, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    private android.widget.Switch addOverlayPreferenceSwitch(
            LinearLayout parent, String labelText, String preferenceKey, boolean defaultValue) {
        android.widget.Switch option = new android.widget.Switch(this);
        option.setText(labelText);
        option.setTextSize(14);
        option.setTextColor(0xFF344054);
        option.setPadding(0, dp(6), 0, dp(6));
        option.setChecked(getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getBoolean(preferenceKey, defaultValue));
        parent.addView(option, new LinearLayout.LayoutParams(-1, -2));
        if (!"floating_overlay_enabled".equals(preferenceKey)) {
            option.setOnCheckedChangeListener((buttonView, checked) ->
                    getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                            .putBoolean(preferenceKey, checked).apply());
        }
        return option;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(roundedDrawable(0xFFFFFFFF, 0xFFE8ECF3, 20));
        card.setElevation(dp(1));
        card.setClipToOutline(true);
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(46));
        b.setMinimumHeight(dp(46));
        b.setMinWidth(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setTextColor(color == 0xFFF0F3F8 || color == 0xFFFCEDEC
                ? 0xFF344054 : 0xFFFFFFFF);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000),
                roundedDrawable(color, 0, 14), null));
        b.setStateListAnimator(null);
        return b;
    }

    private android.graphics.drawable.GradientDrawable roundedDrawable(
            int fillColor, int strokeColor, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(2), 1.0f);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView label(int textRes, int size, int color, boolean bold) {
        TextView view = label(getString(textRes), size, color, bold);
        return view;
    }

    private void addGap(LinearLayout parent, int dp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(-1, dp(dp)));
    }

    private String getAppVersionName() {
        try {
            android.content.pm.PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = getPackageManager().getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.PackageInfoFlags.of(0L));
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            return info.versionName == null || info.versionName.trim().isEmpty()
                    ? "unknown" : info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "unknown";
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
