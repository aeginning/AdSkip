package com.aeginning.adskip.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.aeginning.adskip.db.RuleDatabase;
import com.aeginning.adskip.engine.SafetyPolicy;
import com.aeginning.adskip.engine.TextMatcher;
import com.aeginning.adskip.engine.AdConfidenceScorer;
import com.aeginning.adskip.engine.SensitiveInputPolicy;
import com.aeginning.adskip.engine.SensitiveRegionPolicy;
import com.aeginning.adskip.engine.DiagnosticLogThrottle;
import com.aeginning.adskip.engine.TargetStability;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessibility-based skip engine. It only acts on explicit skip/close-ad labels,
 * and never clicks purchase, install, permission, subscription, or consent actions.
 */
public final class AdSkipAccessibilityService extends AccessibilityService {
    private static final long EVENT_INTERVAL_MS = 350L;
    private static final long CLICK_COOLDOWN_MS = 2_200L;
    private static final long OCR_AD_INTERVAL_MS = 850L;
    private static final long OCR_VISUAL_PROBE_INTERVAL_MS = 7_000L;
    private static final long CLICK_CONFIRM_DELAY_MS = 260L;
    private static final long COUNTDOWN_RESCAN_INTERVAL_MS = 700L;
    private static final long CLICK_VERIFY_DELAY_MS = 750L;
    private static final int MAX_COUNTDOWN_RESCANS = 48;
    private static final int OCR_MAX_DIMENSION = 1280;
    private static final int MAX_NODES = 650;
    private static final int MAX_DEPTH = 14;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();
    private RuleDatabase database;
    private FloatingStatusOverlay floatingStatusOverlay;
    private SharedPreferences overlayPreferences;
    private SharedPreferences.OnSharedPreferenceChangeListener overlayPreferenceListener;
    private TextRecognizer chineseRecognizer;
    private TextRecognizer latinRecognizer;
    private TextRecognizer japaneseRecognizer;
    private TextRecognizer koreanRecognizer;
    private TextRecognizer devanagariRecognizer;
    private final DiagnosticLogThrottle diagnosticThrottle = new DiagnosticLogThrottle();
    private String lastCandidateDecisionReason = "尚未执行控件树候选判断";
    private long lastScanAt;
    private long lastClickAt;
    private long lastOcrAt;
    private volatile boolean ocrInFlight;
    private volatile boolean hasSensitiveInputInCurrentWindow;
    private final List<Rect> sensitiveInputBounds = new ArrayList<>();
    private boolean sensitiveInputBoundsUnknown;
    private volatile long windowGeneration;
    private volatile boolean destroyed;
    private boolean nodeScanIncomplete;
    private String pendingNodeSignature;
    private Runnable pendingNodeConfirmation;
    private Runnable pendingCountdownRescan;
    private String countdownRescanPackage;
    private int countdownRescanAttempts;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        destroyed = false;
        database = new RuleDatabase(this);
        floatingStatusOverlay = new FloatingStatusOverlay(this);
        overlayPreferences = getSharedPreferences("adskip_settings", MODE_PRIVATE);
        overlayPreferenceListener = (preferences, key) -> {
            if (key != null && (key.startsWith("floating_overlay_")
                    || "dns_active".equals(key) || "ocr_enabled".equals(key))
                    && floatingStatusOverlay != null) {
                mainHandler.post(() -> {
                    if (!destroyed && floatingStatusOverlay != null) {
                        floatingStatusOverlay.refresh(database, true, true);
                    }
                });
            }
        };
        overlayPreferences.registerOnSharedPreferenceChangeListener(overlayPreferenceListener);
        chineseRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        japaneseRecognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        koreanRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        devanagariRecognizer = TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
        floatingStatusOverlay.refresh(database, true, true);
        logDiagnostic("", "SERVICE_CONNECTED", "无障碍服务已连接；等待用户启用并打开目标应用");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (destroyed || event == null || database == null) return;
        refreshFloatingOverlay(false);
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            windowGeneration++;
            cancelPendingNodeConfirmation();
            cancelPendingCountdownRescan();
        }

        CharSequence packageName = event.getPackageName();
        if (packageName == null) {
            logDiagnostic("", "NO_PACKAGE", "系统无障碍事件未提供目标应用包名，无法安全扫描");
            return;
        }
        String pkg = packageName.toString();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                    .putString("foreground_last_package", pkg)
                    .putString("foreground_last_class", className == null ? "" : className.toString())
                    .putLong("foreground_last_at", System.currentTimeMillis())
                    .apply();
        }
        if (pkg.equals(getPackageName())) return;
        if (!database.isAppEnabled(pkg)) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                logDiagnostic("", "APP_NOT_ENABLED",
                        "前台应用尚未在应用自动化中启用；为保护隐私不扫描页面，包名不写入日志");
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastScanAt < EVENT_INTERVAL_MS) {
            logDiagnostic(pkg, "SCAN_THROTTLED", "短时间收到重复无障碍事件，已合并扫描以降低耗电");
            return;
        }
        lastScanAt = now;
        logDiagnostic(pkg, "SCAN", "开始扫描当前窗口的可见控件树");

        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable failure) {
            logDiagnostic(pkg, "NO_WINDOW", "读取活动窗口失败：" + failure.getClass().getSimpleName());
            return;
        }
        hasSensitiveInputInCurrentWindow = false;
        sensitiveInputBounds.clear();
        sensitiveInputBoundsUnknown = false;
        nodeScanIncomplete = false;
        if (root == null) {
            logDiagnostic(pkg, "NO_WINDOW", "Android 未提供活动窗口节点；可能是安全窗口、切换动画或应用限制");
            return;
        }
        CharSequence rootPackage = root.getPackageName();
        if (rootPackage == null || !pkg.contentEquals(rootPackage)) {
            recycleNode(root);
            logDiagnostic(pkg, "WINDOW_MISMATCH", "活动窗口包名与无障碍事件不一致，已放弃本次扫描");
            return;
        }

        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        Candidate candidate = null;
        String context = "";
        try {
            StringBuilder screenText = new StringBuilder();
            collectNodes(root, 0, nodes, screenText);
            if (hasSensitiveInputInCurrentWindow) {
                logDiagnostic(pkg, "SENSITIVE_INPUT",
                        "已标记密码、支付码或验证码输入区域；该区域及邻近区域不参与节点点击，整屏 OCR 截图关闭");
            }
            if (nodeScanIncomplete) {
                logDiagnostic(pkg, "SCAN_INCOMPLETE",
                        "控件树超过安全扫描预算或节点遍历不完整；为避免遗漏风险上下文，本次不点击");
                return;
            }

            context = screenText.toString();
            if (TextMatcher.isCountdown(context)) {
                logDiagnostic(pkg, "COUNTDOWN_WAIT", "检测到广告倒计时；启动定时主动扫描，等待跳过按钮可用");
                scheduleCountdownRescan(pkg, windowGeneration);
            }
            candidate = chooseCandidate(nodes, context, pkg);
            if (candidate != null) {
                logDiagnostic(pkg, "CANDIDATE", "发现候选控件，置信度=" + candidate.score + "；进入安全复核");
                scheduleNodeConfirmation(candidate, pkg, windowGeneration, context);
            } else {
                logDiagnostic(pkg, "NO_CANDIDATE", lastCandidateDecisionReason);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    maybeRunOcr(pkg, TextMatcher.hasAdContext(context), true, windowGeneration, context);
                } else {
                    logDiagnostic(pkg, "OCR_UNAVAILABLE",
                            "当前 Android 版本低于 11，无法使用无障碍截图 OCR；仅能识别控件树");
                }
            }
        } catch (Throwable failure) {
            logDiagnostic(pkg, "SCAN_ERROR", "控件树扫描异常：" + failure.getClass().getSimpleName());
        } finally {
            if (candidate != null && candidate.ownsNode) recycleNode(candidate.node);
            recycleNodes(nodes);
        }
    }

    private void collectNodes(AccessibilityNodeInfo node, int depth,
                              List<AccessibilityNodeInfo> nodes, StringBuilder text) {
        if (node == null) return;
        if (!node.isVisibleToUser()) {
            recycleNode(node);
            return;
        }
        if (depth > MAX_DEPTH || nodes.size() >= MAX_NODES) {
            nodeScanIncomplete = true;
            recycleNode(node);
            return;
        }
        nodes.add(node);
        boolean password = node.isPassword();
        boolean editable = node.isEditable();
        boolean sensitive = SensitiveInputPolicy.isSensitive(password, editable,
                node.getInputType(), node.getHintText(), node.getContentDescription());
        if (sensitive) {
            hasSensitiveInputInCurrentWindow = true;
            Rect sensitiveBounds = new Rect();
            node.getBoundsInScreen(sensitiveBounds);
            if (sensitiveBounds.isEmpty()) {
                sensitiveInputBoundsUnknown = true;
            } else {
                sensitiveInputBounds.add(sensitiveBounds);
            }
        }

        // Never read text values from any editable field. A normal search box is not,
        // however, a reason to suppress scanning of unrelated buttons elsewhere.
        if (!password && !editable) {
            CharSequence label = node.getText();
            CharSequence description = node.getContentDescription();
            if (label != null && label.length() > 0 && text.length() < 16000) {
                text.append(label, 0, Math.min(label.length(), 16000 - text.length())).append(' ');
            }
            if (description != null && description.length() > 0 && text.length() < 16000) {
                text.append(description, 0, Math.min(description.length(), 16000 - text.length())).append(' ');
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (nodes.size() >= MAX_NODES) {
                nodeScanIncomplete = true;
                break;
            }
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectNodes(child, depth + 1, nodes, text);
        }
    }

    @SuppressWarnings("deprecation")
    private void recycleNode(AccessibilityNodeInfo node) {
        if (node != null && Build.VERSION.SDK_INT < 33) node.recycle();
    }

    private void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) recycleNode(node);
        nodes.clear();
    }

    private Candidate chooseCandidate(List<AccessibilityNodeInfo> nodes, String context, String packageName) {
        Candidate best = null;
        int bestScore = 0;
        int minimumScore = getMinimumSkipScore();
        int inspected = 0;
        int emptyLabels = 0;
        int noClickableAncestor = 0;
        int suppressed = 0;
        int lowConfidence = 0;
        int unsafe = 0;
        int sensitiveRegionRejected = 0;
        String firstRejection = "";
        List<String> suppressedLabels = database == null
                ? java.util.Collections.emptyList() : database.getSuppressedLabels(packageName);

        for (AccessibilityNodeInfo node : nodes) {
            inspected++;
            String label = readLabel(node);
            if (label.isEmpty()) {
                emptyLabels++;
                continue;
            }
            if (isSuppressedLabel(suppressedLabels, label)) {
                suppressed++;
                continue;
            }
            ClickableTarget target = nearestClickable(node);
            if (target == null) {
                noClickableAncestor++;
                continue;
            }
            boolean selected = false;
            try {
                AccessibilityNodeInfo clickable = target.node;
                Rect bounds = new Rect();
                clickable.getBoundsInScreen(bounds);
                if (overlapsCurrentSensitiveRegion(bounds)) {
                    sensitiveRegionRejected++;
                    if (firstRejection.isEmpty()) firstRejection = "候选控件与密码/支付/验证码敏感区域或其邻近范围重叠";
                    continue;
                }
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(label, context,
                        clickable.isClickable(), clickable.isEnabled(), clickable.isVisibleToUser(),
                        bounds.width(), bounds.height(), width, height,
                        isCountdownClickEnabled(), minimumScore);
                int score = assessment.score;
                if (score <= bestScore || score < minimumScore) {
                    lowConfidence++;
                    if (firstRejection.isEmpty()) firstRejection = assessment.reason;
                    continue;
                }
                if (!SafetyPolicy.isSafeTarget(bounds, width, height,
                        clickable.isClickable(), clickable.isEnabled(), clickable.isVisibleToUser(),
                        label, context, isCountdownClickEnabled(), minimumScore)) {
                    unsafe++;
                    if (firstRejection.isEmpty()) firstRejection = "目标未通过最终安全区域或高风险操作策略";
                    continue;
                }

                if (best != null && best.ownsNode) recycleNode(best.node);
                best = new Candidate(clickable, target.owned, new Rect(bounds), label, score);
                bestScore = score;
                selected = true;
            } finally {
                if (!selected && target.owned) recycleNode(target.node);
            }
        }

        if (best == null) {
            lastCandidateDecisionReason = "扫描可见节点 " + inspected + " 个：空标签 " + emptyLabels
                    + "，无可点击父控件 " + noClickableAncestor + "，用户屏蔽 " + suppressed
                    + "，置信度不足 " + lowConfidence + "，敏感区域排除 " + sensitiveRegionRejected
                    + "，安全策略拒绝 " + unsafe
                    + (firstRejection.isEmpty() ? "；未发现明确的广告跳过/关闭控件" : "；首个拒绝原因：" + firstRejection);
        } else {
            lastCandidateDecisionReason = "发现候选控件，置信度=" + bestScore;
        }
        return best;
    }

    private boolean isSuppressedLabel(List<String> phrases, String candidate) {
        String normalized = TextMatcher.normalize(candidate);
        if (normalized.isEmpty()) return false;
        for (String phrase : phrases) {
            if (phrase != null && normalized.contains(TextMatcher.normalize(phrase))) return true;
        }
        return false;
    }

    /** Two observations are required. The node is reacquired after the delay; stale nodes are never clicked. */
    private void scheduleNodeConfirmation(Candidate first, String packageName, long generation, String context) {
        if (!isSecondConfirmationEnabled()) {
            executeNodeCandidate(first, packageName, context, generation);
            return;
        }
        String signature = packageName + "|" + TextMatcher.normalize(first.label);
        if (signature.equals(pendingNodeSignature)) {
            logDiagnostic(packageName, "CONFIRM_PENDING", "同一候选控件已在等待二次观察，忽略重复确认请求");
            return;
        }
        cancelPendingNodeConfirmation();
        pendingNodeSignature = signature;
        final Rect initialBounds = new Rect(first.bounds);
        final String initialLabel = first.label;
        final int initialScore = first.score;
        pendingNodeConfirmation = () -> {
            pendingNodeConfirmation = null;
            pendingNodeSignature = null;
            if (destroyed || database == null || generation != windowGeneration
                    || !database.isAppEnabled(packageName)) {
                logDiagnostic(packageName, "CONFIRM_ABORTED", "二次确认期间窗口或应用状态已变化");
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                logDiagnostic(packageName, "CONFIRM_NO_WINDOW", "二次确认时无法重新读取活动窗口");
                return;
            }
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !packageName.contentEquals(rootPackage)) {
                recycleNode(root);
                logDiagnostic(packageName, "CONFIRM_WINDOW_MISMATCH", "二次确认时活动窗口已切换");
                return;
            }
            hasSensitiveInputInCurrentWindow = false;
            sensitiveInputBounds.clear();
            sensitiveInputBoundsUnknown = false;
            nodeScanIncomplete = false;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            Candidate second = null;
            try {
                StringBuilder text = new StringBuilder();
                collectNodes(root, 0, nodes, text);
                if (hasSensitiveInputInCurrentWindow) {
                    logDiagnostic(packageName, "CONFIRM_SENSITIVE_INPUT",
                            "二次扫描发现敏感区域；会避开该区域，只允许其它区域的候选继续复核");
                }
                if (nodeScanIncomplete) {
                    logDiagnostic(packageName, "CONFIRM_SCAN_INCOMPLETE",
                            "二次确认控件树不完整，已取消点击");
                    return;
                }
                String secondContext = text.toString();
                second = chooseCandidate(nodes, secondContext, packageName);
                if (second == null) {
                    logDiagnostic(packageName, "CONFIRM_NO_CANDIDATE",
                            "二次观察未找到同类安全候选：" + lastCandidateDecisionReason);
                    if (countdownRescanPackage != null) scheduleCountdownRescan(packageName, generation);
                    return;
                }

                boolean sameLabel = TextMatcher.similarityPercent(initialLabel, second.label)
                        >= getSimilarityThreshold();
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                boolean sameArea = TargetStability.areBoundsConsistent(
                        initialBounds.left, initialBounds.top, initialBounds.right, initialBounds.bottom,
                        second.bounds.left, second.bounds.top, second.bounds.right, second.bounds.bottom,
                        screenWidth, screenHeight, dp(28), dp(72));
                int minimumScore = getMinimumSkipScore();
                AdConfidenceScorer.Assessment firstAssessment = new AdConfidenceScorer.Assessment(
                        initialScore, initialScore >= 90 ? AdConfidenceScorer.Level.HIGH
                                : AdConfidenceScorer.Level.REVIEW, "首次观察");
                AdConfidenceScorer.Assessment secondAssessment = AdConfidenceScorer.assess(second.label,
                        secondContext, second.node.isClickable(), second.node.isEnabled(),
                        second.node.isVisibleToUser(), second.bounds.width(), second.bounds.height(),
                        screenWidth, screenHeight, isCountdownClickEnabled(), minimumScore);
                if (!sameLabel || !sameArea
                        || !AdConfidenceScorer.canProceedAfterSecondObservation(
                                firstAssessment, secondAssessment, minimumScore)) {
                    logDiagnostic(packageName, "CONFIRM_BLOCKED",
                            "二次确认未通过：文字相似=" + sameLabel + "，位置一致=" + sameArea
                                    + "，首次分数=" + initialScore + "，复核分数=" + secondAssessment.score);
                    database.log(packageName, safeLogLabel(initialLabel), "BLOCKED",
                            "二次观察的文字、位置或置信度不一致；未执行点击");
                    if (countdownRescanPackage != null) scheduleCountdownRescan(packageName, generation);
                    return;
                }
                logDiagnostic(packageName, "CONFIRM_PASSED", "文字相似度、动态位置容差和二次置信度均通过");
                executeNodeCandidate(second, packageName, secondContext, generation);
            } catch (Throwable failure) {
                logDiagnostic(packageName, "CONFIRM_ERROR", "二次确认异常：" + failure.getClass().getSimpleName());
            } finally {
                if (second != null && second.ownsNode) recycleNode(second.node);
                recycleNodes(nodes);
            }
        };
        mainHandler.postDelayed(pendingNodeConfirmation, CLICK_CONFIRM_DELAY_MS);
    }

    private void logDiagnostic(String packageName, String action, String reason) {
        if (destroyed || database == null || action == null) return;
        String safePackage = packageName == null ? "" : packageName;
        long interval;
        if ("SCAN".equals(action)) interval = 4_000L;
        else if ("SCAN_THROTTLED".equals(action)) interval = 5_000L;
        else if ("APP_NOT_ENABLED".equals(action)) interval = 30_000L;
        else interval = 1_200L;
        if (!diagnosticThrottle.shouldLog(safePackage + "|" + action,
                SystemClock.elapsedRealtime(), interval)) return;
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.length() > 220) safeReason = safeReason.substring(0, 220);
        try {
            // Keep only package, event code and generic reason. Never store screen OCR
            // text, editable values, notifications or raw candidate labels.
            database.log(safePackage, "诊断", action, safeReason);
        } catch (RuntimeException ignored) {
            // A diagnostic failure must not crash the accessibility service.
        }
    }

    private void cancelPendingNodeConfirmation() {
        if (pendingNodeConfirmation != null) {
            String packageName = "";
            if (pendingNodeSignature != null) {
                int separator = pendingNodeSignature.indexOf('|');
                packageName = separator > 0 ? pendingNodeSignature.substring(0, separator) : "";
            }
            logDiagnostic(packageName, "CONFIRM_CANCELLED",
                    "二次确认任务因窗口切换、服务中断或新候选覆盖而取消；未执行旧候选点击");
            mainHandler.removeCallbacks(pendingNodeConfirmation);
        }
        pendingNodeConfirmation = null;
        pendingNodeSignature = null;
    }

    /**
     * Re-scan a selected app after a visible ad countdown. Some splash ads expose
     * “跳过 3” only through OCR or update the label without a window-content event.
     * Polling is bounded and always revalidates the foreground package and window.
     */
    private void scheduleCountdownRescan(String packageName, long generation) {
        if (destroyed || database == null || packageName == null
                || generation != windowGeneration || !database.isAppEnabled(packageName)) return;
        if (!packageName.equals(countdownRescanPackage)) {
            cancelPendingCountdownRescan();
            countdownRescanPackage = packageName;
            countdownRescanAttempts = 0;
        }
        if (pendingCountdownRescan != null) return;
        if (countdownRescanAttempts >= MAX_COUNTDOWN_RESCANS) {
            logDiagnostic(packageName, "COUNTDOWN_TIMEOUT",
                    "主动扫描达到 " + MAX_COUNTDOWN_RESCANS + " 次上限，未发现可安全点击按钮；未执行点击");
            countdownRescanPackage = null;
            countdownRescanAttempts = 0;
            return;
        }
        countdownRescanAttempts++;
        pendingCountdownRescan = () -> {
            pendingCountdownRescan = null;
            if (destroyed || database == null || generation != windowGeneration
                    || !database.isAppEnabled(packageName)) {
                logDiagnostic(packageName, "COUNTDOWN_CANCELLED", "主动扫描期间应用或窗口状态发生变化");
                return;
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                logDiagnostic(packageName, "COUNTDOWN_NO_WINDOW", "倒计时主动扫描时暂时无法读取活动窗口");
                scheduleCountdownRescan(packageName, generation);
                return;
            }
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !packageName.contentEquals(rootPackage)) {
                recycleNode(root);
                logDiagnostic(packageName, "COUNTDOWN_WINDOW_MISMATCH", "倒计时扫描目标窗口已切换");
                return;
            }

            hasSensitiveInputInCurrentWindow = false;
            sensitiveInputBounds.clear();
            sensitiveInputBoundsUnknown = false;
            nodeScanIncomplete = false;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            Candidate candidate = null;
            try {
                StringBuilder screenText = new StringBuilder();
                collectNodes(root, 0, nodes, screenText);
                if (hasSensitiveInputInCurrentWindow) {
                    logDiagnostic(packageName, "COUNTDOWN_SENSITIVE_INPUT",
                            "倒计时窗口包含敏感输入区域；停止整屏 OCR，并仅扫描该区域之外的安全控件");
                }
                if (nodeScanIncomplete) {
                    logDiagnostic(packageName, "COUNTDOWN_SCAN_INCOMPLETE",
                            "倒计时主动扫描的控件树不完整，本轮不点击");
                    scheduleCountdownRescan(packageName, generation);
                    return;
                }

                String context = screenText.toString();
                candidate = chooseCandidate(nodes, context, packageName);
                if (candidate != null) {
                    logDiagnostic(packageName, "COUNTDOWN_READY",
                            "主动扫描已发现可用跳过候选，进入二次确认");
                    scheduleNodeConfirmation(candidate, packageName, generation, context);
                } else {
                    if (TextMatcher.isCountdown(context)) {
                        logDiagnostic(packageName, "COUNTDOWN_WAIT",
                                "倒计时仍在显示；继续等待并主动重新扫描");
                    } else {
                        logDiagnostic(packageName, "COUNTDOWN_ENDED",
                                "控件树中的倒计时已消失；立即进行 OCR 并继续短间隔检查按钮");
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // The countdown may be gone from the tree while the ad close/skip
                        // control is rendered only in pixels.
                        maybeRunOcr(packageName, true, true, generation, context);
                    }
                    scheduleCountdownRescan(packageName, generation);
                }
            } catch (Throwable failure) {
                logDiagnostic(packageName, "COUNTDOWN_SCAN_ERROR",
                        "倒计时主动扫描异常：" + failure.getClass().getSimpleName());
                scheduleCountdownRescan(packageName, generation);
            } finally {
                if (candidate != null && candidate.ownsNode) recycleNode(candidate.node);
                recycleNodes(nodes);
            }
        };
        mainHandler.postDelayed(pendingCountdownRescan, COUNTDOWN_RESCAN_INTERVAL_MS);
    }

    private void cancelPendingCountdownRescan() {
        if (pendingCountdownRescan != null) {
            logDiagnostic(countdownRescanPackage, "COUNTDOWN_CANCELLED",
                    "倒计时主动扫描队列因窗口切换、服务中断或点击请求已派发而取消");
            mainHandler.removeCallbacks(pendingCountdownRescan);
        }
        pendingCountdownRescan = null;
        countdownRescanPackage = null;
        countdownRescanAttempts = 0;
    }

    private ClickableTarget nearestClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        boolean owned = false;
        for (int i = 0; current != null && i < 5; i++) {
            if (current.isClickable() && current.isEnabled()) return new ClickableTarget(current, owned);
            AccessibilityNodeInfo parent = current.getParent();
            if (owned) recycleNode(current);
            current = parent;
            owned = true;
        }
        if (owned) recycleNode(current);
        return null;
    }

    private String readLabel(AccessibilityNodeInfo node) {
        // Prefer the node's own label. This keeps glyph-only controls such as "X"
        // exact instead of concatenating unrelated parent/ad-description text.
        if (!node.isPassword() && !node.isEditable()) {
            String own = joinLabel(node.getText(), node.getContentDescription());
            if (!own.isEmpty()) return truncateLabel(own);
        }

        AccessibilityNodeInfo parent = node.getParent();
        for (int i = 0; parent != null && i < 2; i++) {
            String label = "";
            if (parent.isVisibleToUser() && !parent.isPassword() && !parent.isEditable()) {
                label = joinLabel(parent.getText(), parent.getContentDescription());
            }
            AccessibilityNodeInfo next = parent.getParent();
            recycleNode(parent);
            parent = next;
            if (!label.isEmpty()) {
                if (parent != null) recycleNode(parent);
                return truncateLabel(label);
            }
        }
        if (parent != null) recycleNode(parent);
        return "";
    }

    private String joinLabel(CharSequence text, CharSequence description) {
        StringBuilder label = new StringBuilder();
        if (text != null && text.length() > 0) label.append(text);
        if (description != null && description.length() > 0) {
            if (label.length() > 0) label.append(' ');
            label.append(description);
        }
        return label.toString().trim();
    }

    private String truncateLabel(String label) {
        return label.length() > 120 ? label.substring(0, 120) : label;
    }

    private void refreshFloatingOverlay(boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> refreshFloatingOverlay(force));
            return;
        }
        if (floatingStatusOverlay != null) floatingStatusOverlay.refresh(database, true, force);
    }

    private void executeNodeCandidate(Candidate candidate, String packageName, String context,
                                       long generation) {
        if (destroyed || generation != windowGeneration || database == null
                || !database.isAppEnabled(packageName)
                || !isNodeTargetSafe(packageName, generation, candidate.bounds)) {
            logDiagnostic(packageName, "CLICK_REVALIDATION_FAILED",
                    "点击前应用开关、前台窗口、敏感区域避让或控件树完整性未通过；候选已作废");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickAt < CLICK_COOLDOWN_MS) {
            logDiagnostic(packageName, "CLICK_COOLDOWN", "距离上一次点击不足冷却时间；本次候选未执行");
            return;
        }
        int minimumScore = getMinimumSkipScore();
        if (!SafetyPolicy.isSafeTarget(candidate.bounds,
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels,
                candidate.node.isClickable(), candidate.node.isEnabled(), candidate.node.isVisibleToUser(),
                candidate.label, context, isCountdownClickEnabled(), minimumScore)) {
            database.log(packageName, safeLogLabel(candidate.label), "BLOCKED",
                    "最终安全策略拒绝控件树候选；未执行点击");
            logDiagnostic(packageName, "CLICK_BLOCKED",
                    "候选未通过最终安全策略：高风险词、广告上下文或位置区域不符合要求");
            return;
        }

        boolean clicked = candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) clicked = dispatchTap(candidate.bounds.centerX(), candidate.bounds.centerY(), packageName);
        if (clicked) {
            lastClickAt = now;
            database.log(packageName, safeLogLabel(candidate.label), "CLICK",
                    (isSecondConfirmationEnabled() ? "二次确认后" : "用户关闭二次确认；单次观察")
                            + "识别通过；灵敏度=" + getSkipButtonSensitivity()
                            + "；最低分=" + minimumScore + "；系统已接受点击请求");
            logDiagnostic(packageName, "CLICK_DISPATCHED",
                    "系统已接受点击请求；将在短暂延迟后检查目标是否消失或页面是否变化");
            cancelPendingCountdownRescan();
            scheduleClickVerification(packageName, candidate.label, candidate.bounds, "控件树");
            refreshFloatingOverlay(true);
        } else {
            database.log(packageName, safeLogLabel(candidate.label), "NO_ACTION",
                    "控件 ACTION_CLICK 与坐标手势均未被系统接受");
            logDiagnostic(packageName, "CLICK_REJECTED", "控件拒绝 ACTION_CLICK，坐标手势也未成功派发");
        }
    }

    private void scheduleClickVerification(String packageName, String label, Rect clickedBounds, String source) {
        if (clickedBounds == null) return;
        final Rect expectedBounds = new Rect(clickedBounds);
        final String expectedLabel = label == null ? "" : label;
        mainHandler.postDelayed(() -> {
            if (destroyed || database == null) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                logDiagnostic(packageName, "CLICK_VERIFY_UNKNOWN",
                        source + "点击后活动窗口不可读取，无法确认按钮状态");
                return;
            }
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !packageName.contentEquals(rootPackage)) {
                recycleNode(root);
                logDiagnostic(packageName, "CLICK_VERIFIED",
                        source + "点击后前台应用已切换，原广告页面已离开");
                return;
            }

            hasSensitiveInputInCurrentWindow = false;
            sensitiveInputBounds.clear();
            sensitiveInputBoundsUnknown = false;
            nodeScanIncomplete = false;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            try {
                StringBuilder text = new StringBuilder();
                collectNodes(root, 0, nodes, text);
                if (sensitiveInputBoundsUnknown || nodeScanIncomplete) {
                    logDiagnostic(packageName, "CLICK_VERIFY_UNKNOWN",
                            "点击后敏感区域边界未知或控件树不完整，无法安全确认");
                    return;
                }
                if (hasSensitiveInputInCurrentWindow) {
                    logDiagnostic(packageName, "CLICK_VERIFY_SENSITIVE_PRESENT",
                            "页面存在敏感输入区域；验证将只检查敏感区域之外的原候选位置");
                }
                boolean stillVisible = false;
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                for (AccessibilityNodeInfo node : nodes) {
                    String currentLabel = readLabel(node);
                    if (TextMatcher.similarityPercent(expectedLabel, currentLabel) < 65) continue;
                    Rect currentBounds = new Rect();
                    node.getBoundsInScreen(currentBounds);
                    if (overlapsCurrentSensitiveRegion(currentBounds)) continue;
                    if (TargetStability.areBoundsConsistent(
                            expectedBounds.left, expectedBounds.top, expectedBounds.right, expectedBounds.bottom,
                            currentBounds.left, currentBounds.top, currentBounds.right, currentBounds.bottom,
                            screenWidth, screenHeight, dp(28), dp(72))) {
                        stillVisible = true;
                        break;
                    }
                }
                if (stillVisible) {
                    database.log(packageName, safeLogLabel(expectedLabel), "NO_ACTION",
                            "点击请求已派发，但延迟验证时原候选仍然可见；请检查应用是否拦截手势");
                    logDiagnostic(packageName, "CLICK_NOT_EFFECTIVE",
                            source + "点击后原候选仍在相同区域显示，可能未生效；未自动重复点击");
                } else {
                    logDiagnostic(packageName, "CLICK_VERIFIED",
                            source + "点击后原候选已消失或位置/页面已变化");
                }
            } catch (Throwable failure) {
                logDiagnostic(packageName, "CLICK_VERIFY_ERROR",
                        "点击结果验证异常：" + failure.getClass().getSimpleName());
            } finally {
                recycleNodes(nodes);
            }
        }, CLICK_VERIFY_DELAY_MS);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private void maybeRunOcr(String expectedPackage, boolean adContext, boolean allowVisualProbe,
                             long generation, String treeContext) {
        if (ocrInFlight) {
            logDiagnostic(expectedPackage, "OCR_BUSY", "上一轮 OCR 尚未完成，本次截图请求已合并");
            return;
        }
        if (hasSensitiveInputInCurrentWindow) {
            logDiagnostic(expectedPackage, "OCR_BLOCKED_SENSITIVE",
                    "当前窗口存在敏感输入区域，已禁止截图 OCR");
            return;
        }
        if (!isOcrEnabled()) {
            logDiagnostic(expectedPackage, "OCR_DISABLED", "用户已关闭 OCR 辅助识别");
            return;
        }
        if (!adContext && !allowVisualProbe) {
            logDiagnostic(expectedPackage, "OCR_NO_AD_CONTEXT", "页面没有广告上下文，且未允许视觉探测");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long interval = adContext ? OCR_AD_INTERVAL_MS : OCR_VISUAL_PROBE_INTERVAL_MS;
        if (now - lastOcrAt < interval) {
            logDiagnostic(expectedPackage, "OCR_THROTTLED", "OCR 截图间隔未达到限制，已节流");
            return;
        }
        lastOcrAt = now;
        ocrInFlight = true;
        logDiagnostic(expectedPackage, "OCR_START",
                "开始本地多语言 OCR；优先语言由系统语言和控件树字符特征决定");
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap bitmap = null;
                    try {
                        bitmap = copyScreenshotBitmap(result);
                        if (bitmap == null) {
                            logDiagnostic(expectedPackage, "OCR_ERROR", "系统截图结果为空或无法转换为软件位图");
                            ocrInFlight = false;
                            return;
                        }
                        Bitmap bounded = resizeForOcr(bitmap);
                        if (bounded != bitmap && !bitmap.isRecycled()) bitmap.recycle();
                        runRecognizers(bounded, expectedPackage, generation, treeContext);
                        bitmap = null;
                    } catch (Throwable failure) {
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        logDiagnostic(expectedPackage, "OCR_ERROR",
                                "截图预处理失败：" + failure.getClass().getSimpleName());
                        ocrInFlight = false;
                    }
                }

                @Override public void onFailure(int errorCode) {
                    logDiagnostic(expectedPackage, "OCR_ERROR", "Android 截图 API 返回错误码 " + errorCode
                            + "；可能受安全窗口、系统权限或厂商 ROM 限制");
                    ocrInFlight = false;
                }
            });
        } catch (Throwable failure) {
            logDiagnostic(expectedPackage, "OCR_ERROR",
                    "调用截图 API 异常：" + failure.getClass().getSimpleName());
            ocrInFlight = false;
        }
    }

    private List<TextRecognizer> getRecognizerOrder(String visibleText) {
        List<TextRecognizer> ordered = new ArrayList<>();
        String language = java.util.Locale.getDefault().getLanguage();
        boolean japanese = containsUnicodeScript(visibleText, Character.UnicodeBlock.HIRAGANA)
                || containsUnicodeScript(visibleText, Character.UnicodeBlock.KATAKANA)
                || "ja".equals(language);
        boolean korean = containsHangul(visibleText) || "ko".equals(language);
        boolean devanagari = containsUnicodeScript(visibleText, Character.UnicodeBlock.DEVANAGARI)
                || "hi".equals(language) || "mr".equals(language) || "ne".equals(language);
        boolean chinese = containsUnicodeScript(visibleText, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)
                || "zh".equals(language);

        if (japanese) addRecognizer(ordered, japaneseRecognizer);
        if (korean) addRecognizer(ordered, koreanRecognizer);
        if (devanagari) addRecognizer(ordered, devanagariRecognizer);
        if (chinese) addRecognizer(ordered, chineseRecognizer);
        if (!japanese && !korean && !devanagari && !chinese) {
            addRecognizer(ordered, latinRecognizer);
        }
        // Bounded sequential fallback: only one model processes the bitmap at a time.
        addRecognizer(ordered, latinRecognizer);
        addRecognizer(ordered, chineseRecognizer);
        addRecognizer(ordered, japaneseRecognizer);
        addRecognizer(ordered, koreanRecognizer);
        addRecognizer(ordered, devanagariRecognizer);
        return ordered;
    }

    private void addRecognizer(List<TextRecognizer> ordered, TextRecognizer recognizer) {
        if (recognizer != null && !ordered.contains(recognizer)) ordered.add(recognizer);
    }

    private boolean containsHangul(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private boolean containsUnicodeScript(String value, Character.UnicodeBlock target) {
        if (value == null || target == null) return false;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (Character.UnicodeBlock.of(codePoint) == target) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private String recognizerName(TextRecognizer recognizer) {
        if (recognizer == chineseRecognizer) return "中文";
        if (recognizer == latinRecognizer) return "拉丁字母";
        if (recognizer == japaneseRecognizer) return "日文";
        if (recognizer == koreanRecognizer) return "韩文";
        if (recognizer == devanagariRecognizer) return "天城文";
        return "未知";
    }

    private void runRecognizers(Bitmap bitmap, String expectedPackage, long generation, String treeContext) {
        List<TextRecognizer> recognizers = getRecognizerOrder(treeContext);
        if (recognizers.isEmpty()) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
            logDiagnostic(expectedPackage, "OCR_ERROR", "没有可用的本地 OCR 识别模型");
            return;
        }
        runOcrPass(bitmap, expectedPackage, generation, recognizers, 0);
    }

    private void runOcrPass(Bitmap bitmap, String expectedPackage, long generation,
                            List<TextRecognizer> recognizers, int recognizerIndex) {
        if (destroyed || bitmap == null || bitmap.isRecycled()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
            return;
        }
        if (recognizerIndex >= recognizers.size()) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
            logDiagnostic(expectedPackage, "OCR_NO_CANDIDATE",
                    "已按优先顺序尝试全部可用语言模型，仍未发现明确且安全的跳过/关闭控件");
            return;
        }

        TextRecognizer recognizer = recognizers.get(recognizerIndex);
        final int sourceWidth = bitmap.getWidth();
        final int sourceHeight = bitmap.getHeight();
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            Task<Text> task = recognizer.process(image);
            task.addOnCompleteListener(completed -> {
                boolean handedToFallback = false;
                try {
                    if (destroyed) return;
                    if (!completed.isSuccessful() || completed.getResult() == null) {
                        logDiagnostic(expectedPackage, "OCR_MODEL_ERROR",
                                recognizerName(recognizer) + "模型未能完成识别；尝试下一个语言模型");
                        if (recognizerIndex + 1 < recognizers.size()) {
                            handedToFallback = true;
                            runOcrPass(bitmap, expectedPackage, generation, recognizers, recognizerIndex + 1);
                        } else {
                            logDiagnostic(expectedPackage, "OCR_ERROR",
                                    "全部语言模型均未成功返回识别结果");
                        }
                        return;
                    }

                    Text result = completed.getResult();
                    String context = result.getText() == null ? "" : result.getText();
                    logDiagnostic(expectedPackage, "OCR_SUCCESS",
                            recognizerName(recognizer) + "模型完成识别；文本块=" + result.getTextBlocks().size());
                    if (TextMatcher.isCountdown(context)) {
                        logDiagnostic(expectedPackage, "OCR_COUNTDOWN_WAIT",
                                "OCR 图像仍检测到倒计时；保持主动扫描任务");
                        mainHandler.post(() -> scheduleCountdownRescan(expectedPackage, generation));
                    } else if (countdownRescanPackage != null) {
                        logDiagnostic(expectedPackage, "OCR_COUNTDOWN_ENDED",
                                "OCR 图像中未见倒计时，继续检查按钮是否已出现");
                    }

                    OcrCandidate best = null;
                    int bestScore = 0;
                    boolean allowCountdownClicks = isCountdownClickEnabled();
                    List<String> suppressedLabels = database == null
                            ? java.util.Collections.emptyList() : database.getSuppressedLabels(expectedPackage);
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String lineText = line.getText();
                            Rect box = line.getBoundingBox();
                            if (box == null || isSuppressedLabel(suppressedLabels, lineText)) continue;
                            AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(
                                    lineText, context, true, true, true, box.width(), box.height(),
                                    sourceWidth, sourceHeight,
                                    allowCountdownClicks, getMinimumSkipScore());
                            if (assessment.score >= getMinimumSkipScore() && assessment.score > bestScore) {
                                best = new OcrCandidate(new Rect(box), lineText, assessment.score);
                                bestScore = assessment.score;
                            }

                            // Some close affordances are rendered as one glyph inside a
                            // longer OCR line. Inspect individual elements without relaxing
                            // the final ad-context and top-right geometry safety checks.
                            for (Text.Element element : line.getElements()) {
                                String elementText = element.getText();
                                Rect elementBox = element.getBoundingBox();
                                if (elementBox == null || !TextMatcher.isCloseGlyph(elementText)
                                        || isSuppressedLabel(suppressedLabels, elementText)) continue;
                                AdConfidenceScorer.Assessment glyphAssessment = AdConfidenceScorer.assess(
                                        elementText, context, true, true, true,
                                        elementBox.width(), elementBox.height(), sourceWidth, sourceHeight,
                                        allowCountdownClicks, getMinimumSkipScore());
                                if (glyphAssessment.score >= getMinimumSkipScore()
                                        && glyphAssessment.score > bestScore) {
                                    best = new OcrCandidate(new Rect(elementBox),
                                            elementText, glyphAssessment.score);
                                    bestScore = glyphAssessment.score;
                                }
                            }
                        }
                    }

                    if (best == null) {
                        if (recognizerIndex + 1 < recognizers.size()) {
                            logDiagnostic(expectedPackage, "OCR_FALLBACK",
                                    recognizerName(recognizer) + "模型未找到候选，继续使用下一个语言模型");
                            handedToFallback = true;
                            runOcrPass(bitmap, expectedPackage, generation, recognizers, recognizerIndex + 1);
                        } else {
                            logDiagnostic(expectedPackage, "OCR_NO_CANDIDATE",
                                    "全部语言模型均未找到达到灵敏度门槛的安全候选");
                        }
                        return;
                    }

                    OcrCandidate selected = best;
                    logDiagnostic(expectedPackage, "OCR_CANDIDATE",
                            recognizerName(recognizer) + "模型发现候选，置信度=" + selected.score);
                    mainHandler.post(() -> executeOcrCandidate(selected, context, sourceWidth,
                            sourceHeight, expectedPackage, generation));
                } catch (Throwable failure) {
                    logDiagnostic(expectedPackage, "OCR_ERROR",
                            "OCR 结果处理异常：" + failure.getClass().getSimpleName());
                    if (!destroyed && recognizerIndex + 1 < recognizers.size()) {
                        handedToFallback = true;
                        runOcrPass(bitmap, expectedPackage, generation, recognizers, recognizerIndex + 1);
                    }
                } finally {
                    if (!handedToFallback) {
                        if (!bitmap.isRecycled()) bitmap.recycle();
                        ocrInFlight = false;
                    }
                }
            });
        } catch (Throwable failure) {
            logDiagnostic(expectedPackage, "OCR_MODEL_ERROR",
                    recognizerName(recognizer) + "模型调用异常：" + failure.getClass().getSimpleName());
            if (recognizerIndex + 1 < recognizers.size()) {
                runOcrPass(bitmap, expectedPackage, generation, recognizers, recognizerIndex + 1);
            } else {
                if (!bitmap.isRecycled()) bitmap.recycle();
                ocrInFlight = false;
                logDiagnostic(expectedPackage, "OCR_ERROR", "所有语言模型均无法处理当前截图");
            }
        }
    }

    private boolean isOcrEnabled() {
        return getSharedPreferences("adskip_settings", MODE_PRIVATE).getBoolean("ocr_enabled", true);
    }

    private boolean isCountdownClickEnabled() {
        return getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getBoolean("countdown_click_enabled", false);
    }

    private int getSkipButtonSensitivity() {
        return Math.max(0, Math.min(100, getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getInt("skip_button_sensitivity", 50)));
    }

    private int getMinimumSkipScore() {
        return AdConfidenceScorer.minimumScoreForSensitivity(getSkipButtonSensitivity());
    }

    private boolean isSecondConfirmationEnabled() {
        return getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getBoolean("second_confirmation_enabled", true);
    }

    private int getSimilarityThreshold() {
        return Math.max(60, Math.min(100, getSharedPreferences("adskip_settings", MODE_PRIVATE)
                .getInt("recognition_similarity_percent", 85)));
    }

    private Bitmap resizeForOcr(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= OCR_MAX_DIMENSION) return source;
        float scale = OCR_MAX_DIMENSION / (float) longest;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }



    private void executeOcrCandidate(OcrCandidate candidate, String context, int bitmapWidth, int bitmapHeight,
                                     String expectedPackage, long generation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            logDiagnostic(expectedPackage, "OCR_UNAVAILABLE", "Android 11 以下不支持当前截图 OCR 路径");
            return;
        }
        if (!isOcrEnabled()) {
            logDiagnostic(expectedPackage, "OCR_DISABLED", "OCR 辅助识别已关闭");
            return;
        }
        if (!isActiveWindowSafeForTarget(expectedPackage, generation)) {
            logDiagnostic(expectedPackage, "OCR_STALE_WINDOW", "OCR 完成时窗口已变化或出现敏感输入区域");
            return;
        }
        if (database == null || !database.isAppEnabled(expectedPackage)) {
            logDiagnostic(expectedPackage, "OCR_APP_DISABLED", "OCR 完成时目标应用自动化已关闭");
            return;
        }
        if (database.isLabelSuppressed(expectedPackage, candidate.label)) {
            logDiagnostic(expectedPackage, "OCR_SUPPRESSED", "候选命中用户设置的误判屏蔽规则");
            return;
        }
        if (SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) {
            logDiagnostic(expectedPackage, "CLICK_COOLDOWN", "OCR 候选命中点击冷却期，未执行点击");
            return;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int minimumScore = getMinimumSkipScore();
        float scaleX = screenWidth / (float) Math.max(1, bitmapWidth);
        float scaleY = screenHeight / (float) Math.max(1, bitmapHeight);
        Rect mapped = new Rect(
                Math.round(candidate.bounds.left * scaleX),
                Math.round(candidate.bounds.top * scaleY),
                Math.round(candidate.bounds.right * scaleX),
                Math.round(candidate.bounds.bottom * scaleY));
        AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(candidate.label, context,
                true, true, true, mapped.width(), mapped.height(), screenWidth, screenHeight,
                isCountdownClickEnabled(), minimumScore);
        if (assessment.score < minimumScore || !SafetyPolicy.isSafeTarget(mapped, screenWidth, screenHeight,
                true, true, true, candidate.label, context,
                isCountdownClickEnabled(), minimumScore)) {
            database.log(expectedPackage, safeLogLabel(candidate.label), "BLOCKED",
                    "OCR 候选未通过置信度门槛或最终安全审核；未执行点击");
            logDiagnostic(expectedPackage, "OCR_BLOCKED",
                    "OCR 候选被安全策略拒绝，原因：" + assessment.reason);
            return;
        }

        if (!isSecondConfirmationEnabled()) {
            if (!isActiveWindowSafeForTarget(expectedPackage, generation)
                    || !database.isAppEnabled(expectedPackage)
                    || SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) {
                logDiagnostic(expectedPackage, "OCR_REVALIDATION_FAILED",
                        "单次 OCR 模式执行前，目标窗口、应用规则或冷却条件发生变化");
                return;
            }
            if (dispatchTap(mapped.centerX(), mapped.centerY(), expectedPackage)) {
                lastClickAt = SystemClock.elapsedRealtime();
                database.log(expectedPackage, safeLogLabel(candidate.label), "CLICK",
                        "用户关闭二次确认；单次 OCR 观察；置信度=" + assessment.score
                                + "；系统已接受手势请求");
                logDiagnostic(expectedPackage, "CLICK_DISPATCHED",
                        "单次 OCR 手势已派发；将在延迟后验证按钮状态");
                cancelPendingCountdownRescan();
                scheduleClickVerification(expectedPackage, candidate.label, mapped, "OCR");
                refreshFloatingOverlay(true);
            } else {
                database.log(expectedPackage, safeLogLabel(candidate.label), "NO_ACTION",
                        "OCR 候选通过安全审核，但 Android 未接受坐标手势");
                logDiagnostic(expectedPackage, "OCR_GESTURE_REJECTED",
                        "OCR 点击位置通过安全审核，但系统拒绝手势派发");
            }
            return;
        }

        logDiagnostic(expectedPackage, "OCR_CONFIRM_START",
                "OCR 候选通过安全审核，准备第二次截图确认");
        confirmOcrCandidate(candidate.label, mapped, expectedPackage, generation, assessment.score);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private Bitmap copyScreenshotBitmap(ScreenshotResult result) {
        if (result == null) return null;
        HardwareBuffer hardwareBuffer = result.getHardwareBuffer();
        if (hardwareBuffer == null) return null;
        Bitmap hardwareBitmap = null;
        try {
            ColorSpace colorSpace = result.getColorSpace();
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
            return hardwareBitmap == null ? null : hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (hardwareBitmap != null && !hardwareBitmap.isRecycled()) hardwareBitmap.recycle();
            hardwareBuffer.close();
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private void confirmOcrCandidate(String expectedLabel, Rect firstBounds,
                                     String expectedPackage, long generation, int firstScore) {
        if (ocrInFlight) {
            logDiagnostic(expectedPackage, "OCR_CONFIRM_BUSY", "另一轮 OCR 正在执行，二次截图确认已合并");
            return;
        }
        ocrInFlight = true;
        mainHandler.postDelayed(() -> {
            if (destroyed || database == null) {
                ocrInFlight = false;
                return;
            }
            if (!isOcrEnabled()) {
                logDiagnostic(expectedPackage, "OCR_CONFIRM_DISABLED", "二次截图前 OCR 已关闭");
                ocrInFlight = false;
                return;
            }
            if (!isActiveWindowSafeForTarget(expectedPackage, generation)) {
                logDiagnostic(expectedPackage, "OCR_CONFIRM_STALE",
                        "二次截图前目标窗口发生变化或检测到敏感输入区域");
                ocrInFlight = false;
                return;
            }
            if (!database.isAppEnabled(expectedPackage)
                    || database.isLabelSuppressed(expectedPackage, expectedLabel)) {
                logDiagnostic(expectedPackage, "OCR_CONFIRM_RULE_BLOCKED",
                        "二次截图前应用规则已关闭或候选已被用户屏蔽");
                ocrInFlight = false;
                return;
            }
            if (SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) {
                logDiagnostic(expectedPackage, "CLICK_COOLDOWN", "二次截图确认时仍处于点击冷却期");
                ocrInFlight = false;
                return;
            }
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        Bitmap bitmap = null;
                        try {
                            bitmap = copyScreenshotBitmap(result);
                            if (bitmap == null) {
                                logDiagnostic(expectedPackage, "OCR_CONFIRM_ERROR", "二次截图为空或无法转换为位图");
                                ocrInFlight = false;
                                return;
                            }
                            Bitmap bounded = resizeForOcr(bitmap);
                            if (bounded != bitmap && !bitmap.isRecycled()) bitmap.recycle();
                            runOcrConfirmation(bounded, expectedLabel, firstBounds, expectedPackage,
                                    generation, firstScore);
                            bitmap = null;
                        } catch (Throwable failure) {
                            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                            logDiagnostic(expectedPackage, "OCR_CONFIRM_ERROR",
                                    "二次截图预处理异常：" + failure.getClass().getSimpleName());
                            ocrInFlight = false;
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        logDiagnostic(expectedPackage, "OCR_CONFIRM_ERROR",
                                "Android 二次截图 API 失败，错误码=" + errorCode);
                        ocrInFlight = false;
                    }
                });
            } catch (Throwable failure) {
                logDiagnostic(expectedPackage, "OCR_CONFIRM_ERROR",
                        "请求二次截图异常：" + failure.getClass().getSimpleName());
                ocrInFlight = false;
            }
        }, CLICK_CONFIRM_DELAY_MS);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private void runOcrConfirmation(Bitmap bitmap, String expectedLabel, Rect firstBounds,
                                    String expectedPackage, long generation, int firstScore) {
        List<TextRecognizer> recognizers = getRecognizerOrder(expectedLabel);
        runOcrConfirmationPass(bitmap, expectedLabel, firstBounds, expectedPackage,
                generation, firstScore, recognizers, 0);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private void runOcrConfirmationPass(Bitmap bitmap, String expectedLabel, Rect firstBounds,
                                        String expectedPackage, long generation, int firstScore,
                                        List<TextRecognizer> recognizers, int recognizerIndex) {
        if (destroyed || bitmap == null || bitmap.isRecycled()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
            return;
        }
        if (recognizerIndex >= recognizers.size()) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
            if (database != null) {
                database.log(expectedPackage, safeLogLabel(expectedLabel), "BLOCKED",
                        "二次 OCR 未找到文字与位置均匹配的候选；未执行点击");
            }
            logDiagnostic(expectedPackage, "OCR_CONFIRM_NO_MATCH",
                    "所有语言模型均未找到文字相似且位置一致的二次候选");
            return;
        }

        TextRecognizer recognizer = recognizers.get(recognizerIndex);
        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();
        try {
            Task<Text> task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
            task.addOnCompleteListener(completed -> {
                boolean handedToFallback = false;
                try {
                    if (destroyed) return;
                    if (!completed.isSuccessful() || completed.getResult() == null) {
                        logDiagnostic(expectedPackage, "OCR_CONFIRM_MODEL_ERROR",
                                recognizerName(recognizer) + "模型二次识别失败");
                        if (recognizerIndex + 1 < recognizers.size()) {
                            handedToFallback = true;
                            runOcrConfirmationPass(bitmap, expectedLabel, firstBounds, expectedPackage,
                                    generation, firstScore, recognizers, recognizerIndex + 1);
                        } else {
                            logDiagnostic(expectedPackage, "OCR_CONFIRM_ERROR", "全部语言模型二次识别失败");
                        }
                        return;
                    }

                    Text result = completed.getResult();
                    String context = result.getText() == null ? "" : result.getText();
                    List<String> suppressedLabels = database == null
                            ? java.util.Collections.emptyList() : database.getSuppressedLabels(expectedPackage);
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    float scaleX = screenWidth / (float) Math.max(1, bitmapWidth);
                    float scaleY = screenHeight / (float) Math.max(1, bitmapHeight);
                    boolean matched = false;

                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String observedLabel = line.getText();
                            Rect box = line.getBoundingBox();
                            if (TextMatcher.isCloseGlyph(expectedLabel)) {
                                if (!TextMatcher.isCloseGlyph(observedLabel)) {
                                    observedLabel = null;
                                    for (Text.Element element : line.getElements()) {
                                        if (TextMatcher.isCloseGlyph(element.getText())
                                                && element.getBoundingBox() != null) {
                                            observedLabel = element.getText();
                                            box = element.getBoundingBox();
                                            break;
                                        }
                                    }
                                }
                                if (observedLabel == null || !TextMatcher.isCloseGlyph(observedLabel)) continue;
                            } else if (TextMatcher.similarityPercent(expectedLabel, observedLabel)
                                    < getSimilarityThreshold()) {
                                continue;
                            }
                            if (isSuppressedLabel(suppressedLabels, observedLabel) || box == null) continue;
                            Rect mapped = new Rect(
                                    Math.round(box.left * scaleX), Math.round(box.top * scaleY),
                                    Math.round(box.right * scaleX), Math.round(box.bottom * scaleY));
                            boolean sameArea = TargetStability.areBoundsConsistent(
                                    firstBounds.left, firstBounds.top, firstBounds.right, firstBounds.bottom,
                                    mapped.left, mapped.top, mapped.right, mapped.bottom,
                                    screenWidth, screenHeight, dp(28), dp(72));
                            if (!sameArea) continue;

                            AdConfidenceScorer.Assessment second = AdConfidenceScorer.assess(
                                    observedLabel, context, true, true, true, mapped.width(), mapped.height(),
                                    screenWidth, screenHeight, isCountdownClickEnabled(), getMinimumSkipScore());
                            AdConfidenceScorer.Assessment first = new AdConfidenceScorer.Assessment(
                                    firstScore, firstScore >= 90 ? AdConfidenceScorer.Level.HIGH
                                            : AdConfidenceScorer.Level.REVIEW, "首次 OCR 观察");
                            if (second.score < getMinimumSkipScore()
                                    || !AdConfidenceScorer.canProceedAfterSecondObservation(
                                            first, second, getMinimumSkipScore())
                                    || !SafetyPolicy.isSafeTarget(mapped, screenWidth, screenHeight,
                                            true, true, true, observedLabel, context,
                                            isCountdownClickEnabled(), getMinimumSkipScore())) {
                                logDiagnostic(expectedPackage, "OCR_CONFIRM_BLOCKED",
                                        "二次 OCR 文字位置匹配，但置信度或最终安全策略未通过");
                                continue;
                            }

                            if (!isOcrEnabled() || generation != windowGeneration
                                    || !isActiveWindowSafeForTarget(expectedPackage, generation)
                                    || database == null || !database.isAppEnabled(expectedPackage)
                                    || database.isLabelSuppressed(expectedPackage, observedLabel)
                                    || SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) {
                                logDiagnostic(expectedPackage, "OCR_CONFIRM_REVALIDATION_FAILED",
                                        "二次确认通过后执行前，窗口、应用规则或冷却状态发生变化");
                                matched = true;
                                break;
                            }

                            if (dispatchTap(mapped.centerX(), mapped.centerY(), expectedPackage)) {
                                lastClickAt = SystemClock.elapsedRealtime();
                                database.log(expectedPackage, safeLogLabel(observedLabel), "CLICK",
                                        "多语言 OCR 二次截图确认文字、位置和安全策略；置信度="
                                                + second.score + "；系统已接受手势请求");
                                logDiagnostic(expectedPackage, "CLICK_DISPATCHED",
                                        "OCR 二次确认手势已派发，等待点击结果验证");
                                cancelPendingCountdownRescan();
                                scheduleClickVerification(expectedPackage, observedLabel, mapped, "OCR 二次确认");
                                refreshFloatingOverlay(true);
                            } else {
                                database.log(expectedPackage, safeLogLabel(observedLabel), "NO_ACTION",
                                        "OCR 二次确认通过，但 Android 未接受坐标手势");
                                logDiagnostic(expectedPackage, "OCR_GESTURE_REJECTED",
                                        "二次 OCR 通过后 Android 未接受手势派发");
                            }
                            matched = true;
                            break;
                        }
                        if (matched) break;
                    }

                    if (!matched && recognizerIndex + 1 < recognizers.size()) {
                        logDiagnostic(expectedPackage, "OCR_CONFIRM_FALLBACK",
                                recognizerName(recognizer) + "未找到稳定候选，尝试下一个语言模型");
                        handedToFallback = true;
                        runOcrConfirmationPass(bitmap, expectedLabel, firstBounds, expectedPackage,
                                generation, firstScore, recognizers, recognizerIndex + 1);
                    } else if (!matched) {
                        if (database != null) {
                            database.log(expectedPackage, safeLogLabel(expectedLabel), "BLOCKED",
                                    "二次 OCR 未找到文字与位置均匹配的候选；未执行点击");
                        }
                        logDiagnostic(expectedPackage, "OCR_CONFIRM_NO_MATCH",
                                "二次截图未找到文字和位置均匹配的按钮");
                    }
                } catch (Throwable failure) {
                    logDiagnostic(expectedPackage, "OCR_CONFIRM_ERROR",
                            "二次 OCR 结果处理异常：" + failure.getClass().getSimpleName());
                    if (!destroyed && recognizerIndex + 1 < recognizers.size()) {
                        handedToFallback = true;
                        runOcrConfirmationPass(bitmap, expectedLabel, firstBounds, expectedPackage,
                                generation, firstScore, recognizers, recognizerIndex + 1);
                    }
                } finally {
                    if (!handedToFallback) {
                        if (!bitmap.isRecycled()) bitmap.recycle();
                        ocrInFlight = false;
                    }
                }
            });
        } catch (Throwable failure) {
            logDiagnostic(expectedPackage, "OCR_CONFIRM_MODEL_ERROR",
                    recognizerName(recognizer) + "调用二次识别异常：" + failure.getClass().getSimpleName());
            if (recognizerIndex + 1 < recognizers.size()) {
                runOcrConfirmationPass(bitmap, expectedLabel, firstBounds, expectedPackage,
                        generation, firstScore, recognizers, recognizerIndex + 1);
            } else {
                if (!bitmap.isRecycled()) bitmap.recycle();
                ocrInFlight = false;
                logDiagnostic(expectedPackage, "OCR_CONFIRM_ERROR", "全部二次 OCR 模型调用失败");
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isNodeTargetSafe(String expectedPackage, long generation, Rect targetBounds) {
        if (destroyed || generation != windowGeneration || targetBounds == null) {
            logDiagnostic(expectedPackage, "WINDOW_STALE", "节点候选复核时窗口代次已失效或目标区域为空");
            return false;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            logDiagnostic(expectedPackage, "NO_WINDOW", "节点点击前复核时无法读取活动窗口");
            return false;
        }
        try {
            CharSequence rootPackage = root.getPackageName();
            if (generation != windowGeneration || rootPackage == null
                    || !expectedPackage.contentEquals(rootPackage)) {
                logDiagnostic(expectedPackage, "WINDOW_MISMATCH", "节点点击前复核发现前台应用已切换");
                return false;
            }
            List<Rect> regions = new ArrayList<>();
            boolean[] unknown = new boolean[]{false};
            collectSensitiveInputRegions(root, 0, new int[]{0}, regions, unknown);
            if (unknown[0]) {
                logDiagnostic(expectedPackage, "SENSITIVE_REGION_UNKNOWN",
                        "无法完整读取敏感输入区域边界；为避免误触，本次节点点击已阻止");
                return false;
            }
            if (overlapsSensitiveRegion(targetBounds, regions, false)) {
                logDiagnostic(expectedPackage, "CLICK_SENSITIVE_REGION",
                        "候选位置与密码、支付或验证码区域重叠/过近，已阻止点击");
                return false;
            }
            return true;
        } finally {
            recycleNode(root);
        }
    }

    private void collectSensitiveInputRegions(AccessibilityNodeInfo node, int depth,
                                              int[] visited, List<Rect> regions,
                                              boolean[] unknown) {
        if (node == null || unknown[0]) return;
        if (depth > MAX_DEPTH || ++visited[0] > MAX_NODES) {
            unknown[0] = true;
            return;
        }
        if (node.isVisibleToUser() && SensitiveInputPolicy.isSensitive(
                node.isPassword(), node.isEditable(), node.getInputType(),
                node.getHintText(), node.getContentDescription())) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) unknown[0] = true;
            else regions.add(bounds);
        }
        for (int i = 0; i < node.getChildCount() && !unknown[0]; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectSensitiveInputRegions(child, depth + 1, visited, regions, unknown);
            } finally {
                recycleNode(child);
            }
        }
    }

    private boolean overlapsCurrentSensitiveRegion(Rect target) {
        return overlapsSensitiveRegion(target, sensitiveInputBounds, sensitiveInputBoundsUnknown);
    }

    private boolean overlapsSensitiveRegion(Rect target, List<Rect> regions, boolean unknown) {
        if (target == null || unknown) return true;
        for (Rect region : regions) {
            if (SensitiveRegionPolicy.overlapsOrNear(
                    target.left, target.top, target.right, target.bottom,
                    region.left, region.top, region.right, region.bottom,
                    dp(24), dp(18))) return true;
        }
        return false;
    }

    private boolean isActiveWindowSafeForTarget(String expectedPackage, long generation) {
        if (destroyed || generation != windowGeneration) {
            logDiagnostic(expectedPackage, "WINDOW_STALE", "待执行动作对应的窗口代次已失效");
            return false;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            logDiagnostic(expectedPackage, "NO_WINDOW", "执行前复核时 Android 未提供活动窗口");
            return false;
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (generation != windowGeneration || packageName == null
                    || !expectedPackage.contentEquals(packageName)) {
                logDiagnostic(expectedPackage, "WINDOW_MISMATCH", "执行前复核发现前台应用已切换");
                return false;
            }
            if (containsSensitiveInput(root)) {
                logDiagnostic(expectedPackage, "SENSITIVE_INPUT",
                        "执行前复核发现密码、支付码或验证码输入区域，已取消动作");
                return false;
            }
            return true;
        } finally {
            recycleNode(root);
        }
    }

    private boolean containsSensitiveInput(AccessibilityNodeInfo root) {
        return containsSensitiveInput(root, 0, new int[]{0});
    }

    private boolean containsSensitiveInput(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (node == null) return false;
        if (depth > MAX_DEPTH || ++visited[0] > MAX_NODES) return true;
        if (node.isVisibleToUser() && SensitiveInputPolicy.isSensitive(
                node.isPassword(), node.isEditable(), node.getInputType(),
                node.getHintText(), node.getContentDescription())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean sensitive;
            try {
                sensitive = containsSensitiveInput(child, depth + 1, visited);
            } finally {
                recycleNode(child);
            }
            if (sensitive) return true;
        }
        return false;
    }

    private String safeLogLabel(String raw) {
        String value = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("跳过广告") || value.contains("skip ad")) return "跳过广告 / Skip Ad";
        if (value.contains("跳过") || value.contains("skip")) return "跳过 / Skip";
        if (value.contains("关闭") || value.contains("close")) return "关闭广告 / Close Ad";
        return "跳过按钮";
    }

    private boolean dispatchTap(int x, int y, String packageName) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 65))
                .build();
        try {
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription completedGesture) {
                    logDiagnostic(packageName, "GESTURE_COMPLETED",
                            "Android 已完成坐标手势；点击是否生效仍以页面变化验证为准");
                }

                @Override public void onCancelled(GestureDescription cancelledGesture) {
                    logDiagnostic(packageName, "GESTURE_CANCELLED",
                            "Android 系统取消坐标手势，按钮未确认点击");
                }
            }, mainHandler);
            if (!accepted) {
                logDiagnostic(packageName, "GESTURE_REJECTED",
                        "dispatchGesture 未接受本次手势，可能被系统动画或其他手势占用");
            }
            return accepted;
        } catch (Throwable failure) {
            logDiagnostic(packageName, "GESTURE_ERROR",
                    "坐标手势派发异常：" + failure.getClass().getSimpleName());
            return false;
        }
    }

    @Override public void onInterrupt() {
        // An interrupted service must never complete a previously queued OCR/node click.
        windowGeneration++;
        cancelPendingNodeConfirmation();
        cancelPendingCountdownRescan();
        refreshFloatingOverlay(true);
    }

    @Override public void onDestroy() {
        destroyed = true;
        windowGeneration++;
        cancelPendingNodeConfirmation();
        cancelPendingCountdownRescan();
        if (overlayPreferences != null && overlayPreferenceListener != null) {
            overlayPreferences.unregisterOnSharedPreferenceChangeListener(overlayPreferenceListener);
        }
        if (floatingStatusOverlay != null) floatingStatusOverlay.remove();
        if (chineseRecognizer != null) chineseRecognizer.close();
        if (latinRecognizer != null) latinRecognizer.close();
        if (japaneseRecognizer != null) japaneseRecognizer.close();
        if (koreanRecognizer != null) koreanRecognizer.close();
        if (devanagariRecognizer != null) devanagariRecognizer.close();
        diagnosticThrottle.clear();
        if (database != null) database.close();
        screenshotExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final boolean ownsNode;
        final Rect bounds;
        final String label;
        final int score;
        Candidate(AccessibilityNodeInfo node, boolean ownsNode, Rect bounds, String label, int score) {
            this.node = node;
            this.ownsNode = ownsNode;
            this.bounds = bounds;
            this.label = label;
            this.score = score;
        }
    }

    private static final class ClickableTarget {
        final AccessibilityNodeInfo node;
        final boolean owned;
        ClickableTarget(AccessibilityNodeInfo node, boolean owned) {
            this.node = node;
            this.owned = owned;
        }
    }

    private static final class OcrCandidate {
        final Rect bounds;
        final String label;
        final int score;
        OcrCandidate(Rect bounds, String label, int score) {
            this.bounds = bounds; this.label = label; this.score = score;
        }
    }
}
