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
import android.widget.TextView;

import com.aeginning.adskip.db.RuleDatabase;

/**
 * Small, draggable status panel hosted by the already-enabled AccessibilityService.
 *
 * TYPE_ACCESSIBILITY_OVERLAY intentionally avoids requesting the unrelated
 * SYSTEM_ALERT_WINDOW permission and is automatically removed with the service.
 */
final class FloatingStatusOverlay {
    static final String PREF_ENABLED = "floating_overlay_enabled";
    static final String PREF_SHOW_SKIP = "floating_overlay_show_skip";
    static final String PREF_SHOW_APPS = "floating_overlay_show_apps";
    static final String PREF_SHOW_DNS = "floating_overlay_show_dns";
    static final String PREF_SHOW_CLICKS = "floating_overlay_show_clicks";
    static final String PREF_X = "floating_overlay_x";
    static final String PREF_Y = "floating_overlay_y";
    static final String PREF_REFRESH_REVISION = "floating_overlay_refresh_nonce";

    private static final long REFRESH_INTERVAL_MS = 1_200L;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final SharedPreferences preferences;
    private final int touchSlop;

    private LinearLayout panel;
    private TextView title;
    private TextView details;
    private TextView collapseButton;
    private WindowManager.LayoutParams layoutParams;
    private boolean attached;
    private boolean expanded = true;
    private long lastRefreshAt;
    private String lastText = "";

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
        int clicks24h = 0;
        if (database != null) {
            try {
                enabledApps = database.getEnabledApps().size();
            } catch (RuntimeException ignored) { }
            try {
                clicks24h = database.getStats24h().clickCount;
            } catch (RuntimeException ignored) { }
        }

        String skipStatus = !serviceAvailable ? "未运行"
                : enabledApps > 0 ? "已启用" : "等待选择应用";
        String dnsStatus = DnsFilterVpnService.isRunning() ? "运行中" : "未运行";
        String text = FloatingOverlayFormatter.format(
                preferences.getBoolean(PREF_SHOW_SKIP, true), skipStatus,
                preferences.getBoolean(PREF_SHOW_APPS, true), enabledApps,
                preferences.getBoolean(PREF_SHOW_DNS, true), dnsStatus,
                preferences.getBoolean(PREF_SHOW_CLICKS, true), clicks24h);

        if (!attached) ensurePanel();
        if (!attached) return;
        if (!text.equals(lastText)) {
            lastText = text;
            details.setText(text);
            // Keep the header neutral; all dynamic status rows obey their individual toggles.
            title.setText("AdSkip  ·  状态");
            if (expanded) details.setVisibility(View.VISIBLE);
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
            layoutParams = null;
        }
    }

    private void createPanel() {
        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(9), dp(12), dp(10));
        panel.setBackground(background(0xF21D2939, 0x665A7195, 18));
        panel.setElevation(dp(10));
        panel.setContentDescription("AdSkip 可移动状态悬浮窗");

        LinearLayout header = new LinearLayout(service);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        title = new TextView(service);
        title.setText("AdSkip  ·  状态");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setContentDescription("拖动此处移动悬浮窗");
        header.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1f));
        title.setOnTouchListener(this::handleDrag);

        collapseButton = headerButton("−");
        header.addView(collapseButton);
        collapseButton.setContentDescription("收起或展开悬浮窗内容");
        collapseButton.setOnClickListener(view -> {
            expanded = !expanded;
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            collapseButton.setText(expanded ? "−" : "+");
            updatePanelLayout();
        });

        TextView closeButton = headerButton("×");
        header.addView(closeButton);
        closeButton.setContentDescription("关闭状态悬浮窗");
        closeButton.setOnClickListener(view ->
                preferences.edit().putBoolean(PREF_ENABLED, false).apply());

        details = new TextView(service);
        details.setTextColor(0xFFF2F4F7);
        details.setTextSize(11);
        details.setLineSpacing(dp(3), 1.05f);
        details.setPadding(0, dp(7), 0, 0);
        details.setMaxWidth(dp(210));
        panel.addView(details, new LinearLayout.LayoutParams(-1, -2));

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        layoutParams.x = Math.max(0, preferences.getInt(PREF_X, dp(12)));
        layoutParams.y = Math.max(0, preferences.getInt(PREF_Y, dp(120)));
        if (!expanded) {
            details.setVisibility(View.GONE);
            collapseButton.setText("+");
        }
    }

    private TextView headerButton(String value) {
        TextView button = new TextView(service);
        button.setText(value);
        button.setTextSize(19);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setBackground(background(0x33FFFFFF, 0, 8));
        button.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(30));
        params.leftMargin = dp(4);
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
        android.util.DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - Math.max(panel.getWidth(), dp(100)));
        int maxY = Math.max(0, metrics.heightPixels - Math.max(panel.getHeight(), dp(60)));
        layoutParams.x = Math.min(layoutParams.x, maxX);
        layoutParams.y = Math.min(layoutParams.y, maxY);
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
        layoutParams = null;
        lastText = "";
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
