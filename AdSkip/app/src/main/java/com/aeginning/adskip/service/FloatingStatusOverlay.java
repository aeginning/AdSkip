package com.aeginning.adskip.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aeginning.adskip.R;
import com.aeginning.adskip.db.RuleDatabase;

/**
 * Compact, draggable and user-resizable status panel hosted by the existing
 * AccessibilityService. No separate overlay permission/service is required.
 */
final class FloatingStatusOverlay {
    static final String PREF_ENABLED = "floating_overlay_enabled";
    static final String PREF_SHOW_SKIP = "floating_overlay_show_skip";
    static final String PREF_SHOW_APPS = "floating_overlay_show_apps";
    static final String PREF_SHOW_DNS = "floating_overlay_show_dns";
    static final String PREF_SHOW_CLICKS = "floating_overlay_show_clicks";
    static final String PREF_SHOW_OCR = "floating_overlay_show_ocr";
    static final String PREF_SHOW_DNS_RULES = "floating_overlay_show_dns_rules";
    static final String PREF_SHOW_SAFETY_BLOCKS = "floating_overlay_show_safety_blocks";
    static final String PREF_SHOW_DNS_BLOCKS = "floating_overlay_show_dns_blocks";
    static final String PREF_SHOW_NO_ACTION = "floating_overlay_show_no_action";
    static final String PREF_SHOW_ERRORS = "floating_overlay_show_errors";
    static final String PREF_WIDTH_DP = "floating_overlay_width_dp";
    static final String PREF_HEIGHT_DP = "floating_overlay_height_dp";
    static final String PREF_TEXT_SIZE_SP = "floating_overlay_text_size_sp";
    static final String PREF_X = "floating_overlay_x";
    static final String PREF_Y = "floating_overlay_y";

    static final int DEFAULT_WIDTH_DP = 188;
    static final int MIN_WIDTH_DP = 150;
    static final int MAX_WIDTH_DP = 320;
    static final int DEFAULT_HEIGHT_DP = 210;
    static final int MIN_HEIGHT_DP = 120;
    static final int MAX_HEIGHT_DP = 460;
    static final int DEFAULT_TEXT_SIZE_SP = 10;
    static final int MIN_TEXT_SIZE_SP = 9;
    static final int MAX_TEXT_SIZE_SP = 16;

    private static final long REFRESH_INTERVAL_MS = 1_200L;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final SharedPreferences preferences;
    private final int touchSlop;

    private LinearLayout panel;
    private TextView title;
    private TextView details;
    private TextView collapseButton;
    private ScrollView detailsScroll;
    private WindowManager.LayoutParams layoutParams;
    private boolean attached;
    private boolean expanded = true;
    private long lastRefreshAt;
    private String lastText = "";
    private int appliedWidth = -1;
    private int appliedHeight = -1;
    private int appliedTextSize = -1;

    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean dragging;

    FloatingStatusOverlay(AccessibilityService service) {
        this.service = service;
        windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        preferences = service.getSharedPreferences("adskip_settings", Context.MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(service).getScaledTouchSlop();
    }

    void refresh(RuleDatabase database, boolean serviceAvailable, boolean force) {
        if (!preferences.getBoolean(PREF_ENABLED, false)) {
            remove();
            return;
        }
        if (!force && SystemClock.elapsedRealtime() - lastRefreshAt < REFRESH_INTERVAL_MS) return;
        lastRefreshAt = SystemClock.elapsedRealtime();

        int enabledApps = 0;
        int dnsRules = 0;
        RuleDatabase.Stats24h stats = null;
        if (database != null) {
            try {
                enabledApps = database.getEnabledApps().size();
            } catch (RuntimeException ignored) { }
            try {
                dnsRules = database.getActiveDomainRuleCount();
            } catch (RuntimeException ignored) { }
            try {
                stats = database.getStats24h();
            } catch (RuntimeException ignored) { }
        }
        if (stats == null) stats = new RuleDatabase.Stats24h(0, 0, 0, 0, 0);

        String skipStatus = !serviceAvailable ? "未运行"
                : enabledApps > 0 ? "已启用" : "等待选择应用";
        String dnsStatus = DnsFilterVpnService.isRunning() ? "运行中" : "未运行";
        String ocrStatus = preferences.getBoolean("ocr_enabled", true) ? "已启用" : "未启用";

        String text = FloatingOverlayFormatter.format(
                preferences.getBoolean(PREF_SHOW_SKIP, true), skipStatus,
                preferences.getBoolean(PREF_SHOW_APPS, true), enabledApps,
                preferences.getBoolean(PREF_SHOW_DNS, true), dnsStatus,
                preferences.getBoolean(PREF_SHOW_CLICKS, true), stats.clickCount,
                preferences.getBoolean(PREF_SHOW_OCR, true), ocrStatus,
                preferences.getBoolean(PREF_SHOW_DNS_RULES, true), dnsRules,
                preferences.getBoolean(PREF_SHOW_SAFETY_BLOCKS, true), stats.blockedCount,
                preferences.getBoolean(PREF_SHOW_DNS_BLOCKS, true), stats.dnsBlockCount,
                preferences.getBoolean(PREF_SHOW_NO_ACTION, false), stats.noActionCount,
                preferences.getBoolean(PREF_SHOW_ERRORS, false), stats.errorCount);

        if (!attached) ensurePanel();
        if (!attached) return;

        boolean contentChanged = !text.equals(lastText);
        if (contentChanged) {
            lastText = text;
            details.setText(text);
        }
        boolean sizeChanged = applyConfiguredSize();
        if (contentChanged || sizeChanged) {
            panel.requestLayout();
            updatePanelLayout();
        }
    }

    private void ensurePanel() {
        if (windowManager == null) return;
        lastText = "";
        createPanel();
        try {
            windowManager.addView(panel, layoutParams);
            attached = true;
        } catch (RuntimeException failure) {
            attached = false;
            panel = null;
            title = null;
            details = null;
            collapseButton = null;
            detailsScroll = null;
            layoutParams = null;
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void createPanel() {
        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(5), dp(8), dp(6));
        panel.setBackground(background(0xF21D2939, 0x665A7195, 16));
        panel.setElevation(dp(8));
        panel.setContentDescription("AdSkip 可调整大小的状态悬浮窗");

        LinearLayout header = new LinearLayout(service);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(30)));

        title = new DragHandleView(service);
        title.setText(service.getString(R.string.floating_overlay_title));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(11);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setContentDescription("拖动此处移动悬浮窗；轻点可收起或展开");
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        title.setOnTouchListener(this::handleDrag);
        title.setOnClickListener(view -> toggleExpanded());

        collapseButton = headerButton("−");
        collapseButton.setContentDescription("收起或展开悬浮窗内容");
        header.addView(collapseButton);
        collapseButton.setOnClickListener(view -> toggleExpanded());

        TextView closeButton = headerButton("×");
        closeButton.setContentDescription("关闭状态悬浮窗");
        header.addView(closeButton);
        closeButton.setOnClickListener(view ->
                preferences.edit().putBoolean(PREF_ENABLED, false).apply());

        detailsScroll = new ScrollView(service);
        detailsScroll.setFillViewport(false);
        detailsScroll.setVerticalScrollBarEnabled(true);
        detailsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        panel.addView(detailsScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        details = new TextView(service);
        details.setTextColor(0xFFF2F4F7);
        details.setIncludeFontPadding(false);
        details.setLineSpacing(dp(1), 1.0f);
        details.setPadding(dp(1), dp(4), dp(3), dp(4));
        details.setTextSize(DEFAULT_TEXT_SIZE_SP);
        details.setFocusable(false);
        detailsScroll.addView(details, new ScrollView.LayoutParams(-1, -2));

        layoutParams = new WindowManager.LayoutParams(
                dp(clamp(preferences.getInt(PREF_WIDTH_DP, DEFAULT_WIDTH_DP), MIN_WIDTH_DP, MAX_WIDTH_DP)),
                dp(clamp(preferences.getInt(PREF_HEIGHT_DP, DEFAULT_HEIGHT_DP), MIN_HEIGHT_DP, MAX_HEIGHT_DP)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = Math.max(0, preferences.getInt(PREF_X, dp(12)));
        layoutParams.y = Math.max(0, preferences.getInt(PREF_Y, dp(120)));
        applyConfiguredSize();
        if (!expanded) {
            detailsScroll.setVisibility(View.GONE);
            collapseButton.setText("+");
        }
    }

    private boolean applyConfiguredSize() {
        if (layoutParams == null) return false;
        int widthDp = clamp(preferences.getInt(PREF_WIDTH_DP, DEFAULT_WIDTH_DP),
                MIN_WIDTH_DP, MAX_WIDTH_DP);
        int heightDp = clamp(preferences.getInt(PREF_HEIGHT_DP, DEFAULT_HEIGHT_DP),
                MIN_HEIGHT_DP, MAX_HEIGHT_DP);
        int textSize = clamp(preferences.getInt(PREF_TEXT_SIZE_SP, DEFAULT_TEXT_SIZE_SP),
                MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP);

        int screenWidthDp = Math.max(MIN_WIDTH_DP,
                Math.round(service.getResources().getDisplayMetrics().widthPixels
                        / service.getResources().getDisplayMetrics().density) - 16);
        int screenHeightDp = Math.max(MIN_HEIGHT_DP,
                Math.round(service.getResources().getDisplayMetrics().heightPixels
                        / service.getResources().getDisplayMetrics().density) - 32);
        widthDp = Math.min(widthDp, screenWidthDp);
        heightDp = Math.min(heightDp, Math.min(MAX_HEIGHT_DP, screenHeightDp));

        boolean changed = widthDp != appliedWidth || heightDp != appliedHeight
                || textSize != appliedTextSize;
        if (!changed) return false;

        layoutParams.width = dp(widthDp);
        layoutParams.height = dp(heightDp);
        details.setTextSize(textSize);
        details.setLineSpacing(dp(textSize <= 10 ? 1 : 2), 1.0f);
        title.setTextSize(Math.max(10, Math.min(14, textSize + 1)));
        appliedWidth = widthDp;
        appliedHeight = heightDp;
        appliedTextSize = textSize;
        clampToScreen();
        return true;
    }

    private void toggleExpanded() {
        expanded = !expanded;
        detailsScroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        collapseButton.setText(expanded ? "−" : "+");
        updatePanelLayout();
    }

    private TextView headerButton(String value) {
        TextView button = new TextView(service);
        button.setText(value);
        button.setTextSize(18);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setBackground(background(0x33FFFFFF, 0, 7));
        button.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(26), dp(26));
        params.leftMargin = dp(3);
        button.setLayoutParams(params);
        button.setClickable(true);
        return button;
    }

    private boolean handleDrag(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = layoutParams.x;
                downY = layoutParams.y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) dragging = true;
                if (dragging && attached) {
                    layoutParams.x = Math.max(0, downX + Math.round(dx));
                    layoutParams.y = Math.max(0, downY + Math.round(dy));
                    clampToScreen();
                    updatePanelLayout();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    preferences.edit().putInt(PREF_X, layoutParams.x)
                            .putInt(PREF_Y, layoutParams.y).apply();
                } else {
                    view.performClick();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    preferences.edit().putInt(PREF_X, layoutParams.x)
                            .putInt(PREF_Y, layoutParams.y).apply();
                }
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private void clampToScreen() {
        if (layoutParams == null) return;
        android.util.DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - layoutParams.width);
        int maxY = Math.max(0, metrics.heightPixels - layoutParams.height);
        layoutParams.x = Math.max(0, Math.min(layoutParams.x, maxX));
        layoutParams.y = Math.max(0, Math.min(layoutParams.y, maxY));
    }

    private void updatePanelLayout() {
        if (!attached || panel == null) return;
        try {
            windowManager.updateViewLayout(panel, layoutParams);
        } catch (RuntimeException ignored) {
            remove();
        }
    }

    void remove() {
        if (attached && panel != null && windowManager != null) {
            try {
                windowManager.removeView(panel);
            } catch (RuntimeException ignored) { }
        }
        attached = false;
        panel = null;
        title = null;
        details = null;
        collapseButton = null;
        detailsScroll = null;
        layoutParams = null;
        appliedWidth = -1;
        appliedHeight = -1;
        appliedTextSize = -1;
        lastText = "";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    @android.annotation.SuppressLint("AppCompatCustomView")
    private static final class DragHandleView extends TextView {
        DragHandleView(Context context) { super(context); }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private GradientDrawable background(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
