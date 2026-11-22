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
    private static final int MAX_COUNTDOWN_RESCANS = 24;
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
                logDiagnostic(pkg, "APP_NOT_ENABLED", "该应用尚未在应用自动化中启用；按用户设置不执行识别或点击");
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
                        "发现密码、支付码或验证码输入区域；不读取输入内容，并暂停本窗口自动点击与截图 OCR");
                return;
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
        if (sensitive) hasSensitiveInputInCurrentWindow = true;

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
                    + "，置信度不足 " + lowConfidence + "，安全策略拒绝 " + unsafe
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
            executeNodeCandidate(first, packageName, context);
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
            nodeScanIncomplete = false;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            Candidate second = null;
            try {
                StringBuilder text = new StringBuilder();
                collectNodes(root, 0, nodes, text);
                if (hasSensitiveInputInCurrentWindow) {
                    logDiagnostic(packageName, "CONFIRM_SENSITIVE_INPUT",
                            "二次确认时出现敏感输入区域，已取消点击");
                    return;
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
                executeNodeCandidate(second, packageName, secondContext);
            } catch (Throwable failure) {
                logDiagnostic(packageName, "CONFIRM_ERROR", "二次确认异常：" + failure.getClass().getSimpleName());
            } finally {
                if (second != null && second.ownsNode) recycleNode(second.node);
                recycleNodes(nodes);
            }
        };
        mainHandler.postDelayed(pendingNodeConfirmation, CLICK_CONFIRM_DELAY_MS);
    }

    private void cancelPendingNodeConfirmation() {
        if (pendingNodeConfirmation != null) mainHandler.removeCallbacks(pendingNodeConfirmation);
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
            nodeScanIncomplete = false;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            Candidate candidate = null;
            try {
                StringBuilder screenText = new StringBuilder();
                collectNodes(root, 0, nodes, screenText);
                if (hasSensitiveInputInCurrentWindow) {
                    logDiagnostic(packageName, "COUNTDOWN_SENSITIVE_INPUT",
                            "倒计时窗口出现敏感输入区域，停止截图和自动点击");
                    return;
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
        if (pendingCountdownRescan != null) mainHandler.removeCallbacks(pendingCountdownRescan);
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
        StringBuilder builder = new StringBuilder();
        if (!node.isPassword() && !node.isEditable()) {
            if (node.getText() != null) builder.append(node.getText()).append(' ');
            if (node.getContentDescription() != null) builder.append(node.getContentDescription()).append(' ');
        }
        AccessibilityNodeInfo parent = node.getParent();
        for (int i = 0; parent != null && i < 2; i++) {
            if (parent.isVisibleToUser() && !parent.isPassword() && !parent.isEditable()) {
                if (parent.getText() != null) builder.append(parent.getText()).append(' ');
                if (parent.getContentDescription() != null) builder.append(parent.getContentDescription()).append(' ');
            }
            AccessibilityNodeInfo next = parent.getParent();
            recycleNode(parent);
            parent = next;
        }
        if (parent != null) recycleNode(parent);
        String result = builder.toString().trim();
        return result.length() > 120 ? result.substring(0, 120) : result;
    }

    private void refreshFloatingOverlay(boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> refreshFloatingOverlay(force));
            return;
        }
        if (floatingStatusOverlay != null) floatingStatusOverlay.refresh(database, true, force);
    }

    private void executeNodeCandidate(Candidate candidate, String packageName, String context) {
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
            nodeScanIncomplete = false;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            try {
                StringBuilder text = new StringBuilder();
                collectNodes(root, 0, nodes, text);
                if (hasSensitiveInputInCurrentWindow || nodeScanIncomplete) {
                    logDiagnostic(packageName, "CLICK_VERIFY_UNKNOWN",
                            "点击后页面出现敏感区域或控件树不完整，无法安全确认");
                    return;
                }
                boolean stillVisible = false;
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                for (AccessibilityNodeInfo node : nodes) {
                    String currentLabel = readLabel(node);
                    if (TextMatcher.similarityPercent(expectedLabel, currentLabel) < 65) continue;
                    Rect currentBounds = new Rect();
                    node.getBoundsInScreen(currentBounds);
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
    private void maybeRunOcr(String expectedPackage, boolean adContext, boolean allowVisualProbe, long generation) {
        if (ocrInFlight || hasSensitiveInputInCurrentWindow || !isOcrEnabled()) return;
        if (!adContext && !allowVisualProbe) return;
        long now = SystemClock.elapsedRealtime();
        long interval = adContext ? OCR_AD_INTERVAL_MS : OCR_VISUAL_PROBE_INTERVAL_MS;
        if (now - lastOcrAt < interval) return;
        lastOcrAt = now;
        ocrInFlight = true;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap bitmap = null;
                    try {
                        bitmap = copyScreenshotBitmap(result);
                        if (bitmap == null) {
                            ocrInFlight = false;
                            return;
                        }
                        Bitmap bounded = resizeForOcr(bitmap);
                        if (bounded != bitmap && !bitmap.isRecycled()) bitmap.recycle();
                        runRecognizers(bounded, expectedPackage, generation);
                        bitmap = null; // Ownership transferred to runRecognizers().
                    } catch (Throwable failure) {
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        ocrInFlight = false;
                    }
                }

                @Override public void onFailure(int errorCode) {
                    ocrInFlight = false;
                }
            });
        } catch (Throwable t) {
            ocrInFlight = false;
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

    private void runRecognizers(Bitmap bitmap, String expectedPackage, long generation) {
        runOcrPass(bitmap, expectedPackage, generation, false);
    }

    /**
     * Try the recognizer matching the device language first, then the other on-device
     * model when no safe skip candidate was found. The same downscaled bitmap is reused
     * sequentially, never processed concurrently, and is recycled exactly once.
     */
    private void runOcrPass(Bitmap bitmap, String expectedPackage, long generation, boolean alternate) {
        final int sourceWidth = bitmap.getWidth();
        final int sourceHeight = bitmap.getHeight();
        boolean chineseLocale = java.util.Locale.getDefault().getLanguage().startsWith("zh");
        TextRecognizer recognizer = alternate
                ? (chineseLocale ? latinRecognizer : chineseRecognizer)
                : (chineseLocale ? chineseRecognizer : latinRecognizer);
        if (recognizer == null) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
            return;
        }

        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            Task<Text> task = recognizer.process(image);
            task.addOnCompleteListener(completed -> {
                boolean handedToFallback = false;
                try {
                    if (destroyed || !completed.isSuccessful() || completed.getResult() == null) {
                        if (!alternate && !destroyed) {
                            handedToFallback = true;
                            runOcrPass(bitmap, expectedPackage, generation, true);
                        }
                        return;
                    }

                    Text result = completed.getResult();
                    String context = result.getText();
                    boolean allowCountdownClicks = isCountdownClickEnabled();
                    if (TextMatcher.isCountdown(context)) {
                        mainHandler.post(() -> scheduleCountdownRescan(expectedPackage, generation));
                        // Continue scanning the frame for a distinct X close affordance.
                        // The scorer rejects countdown labels unless their toggle is enabled.
                    } else {
                        // OCR confirms that the visual countdown is gone; stop the bounded poll loop.
                        mainHandler.post(this::cancelPendingCountdownRescan);
                    }

                    OcrCandidate best = null;
                    int bestScore = 0;
                    List<String> suppressedLabels = database == null
                            ? java.util.Collections.emptyList() : database.getSuppressedLabels(expectedPackage);
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String lineText = line.getText();
                            Rect box = line.getBoundingBox();
                            if (box == null || isSuppressedLabel(suppressedLabels, lineText)) continue;
                            AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(
                                    lineText, context, true, true, true, box.width(), box.height(),
                                    bitmap.getWidth(), bitmap.getHeight(),
                                    allowCountdownClicks, getMinimumSkipScore());
                            if (assessment.score >= getMinimumSkipScore()
                                    && assessment.score > bestScore) {
                                best = new OcrCandidate(new Rect(box), lineText, assessment.score);
                                bestScore = assessment.score;
                            }
                        }
                    }

                    if (best == null && !alternate && !destroyed) {
                        // Chinese ads can appear on English-system devices and vice versa.
                        handedToFallback = true;
                        runOcrPass(bitmap, expectedPackage, generation, true);
                        return;
                    }

                    if (best != null) {
                        OcrCandidate selected = best;
                        mainHandler.post(() -> executeOcrCandidate(selected, context, sourceWidth,
                                sourceHeight, expectedPackage, generation));
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!handedToFallback) {
                        if (!bitmap.isRecycled()) bitmap.recycle();
                        ocrInFlight = false;
                    }
                }
            });
        } catch (Throwable failure) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
        }
    }

    private void executeOcrCandidate(OcrCandidate candidate, String context, int bitmapWidth, int bitmapHeight,
                                     String expectedPackage, long generation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isOcrEnabled()
                || !isActiveWindowSafeForTarget(expectedPackage, generation)
                || database == null || !database.isAppEnabled(expectedPackage)
                || database.isLabelSuppressed(expectedPackage, candidate.label)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickAt < CLICK_COOLDOWN_MS) return;
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
                    "OCR 置信度、灵敏度门槛或安全审核未通过");
            return;
        }

        if (!isSecondConfirmationEnabled()) {
            if (generation == windowGeneration && isOcrEnabled()
                    && database != null && database.isAppEnabled(expectedPackage)
                    && !database.isLabelSuppressed(expectedPackage, candidate.label)
                    && isActiveWindowSafeForTarget(expectedPackage, generation)
                    && SafetyPolicy.isSafeTarget(mapped, screenWidth, screenHeight,
                            true, true, true, candidate.label, context,
                            isCountdownClickEnabled(), minimumScore)
                    && SystemClock.elapsedRealtime() - lastClickAt >= CLICK_COOLDOWN_MS
                    && dispatchTap(mapped.centerX(), mapped.centerY())) {
                lastClickAt = SystemClock.elapsedRealtime();
                database.log(expectedPackage, safeLogLabel(candidate.label), "CLICK",
                        "用户关闭二次确认；单次 OCR 观察；灵敏度="
                                + getSkipButtonSensitivity() + "；置信度=" + assessment.score);
                refreshFloatingOverlay(true);
            }
            return;
        }
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
        if (ocrInFlight) return;
        ocrInFlight = true;
        mainHandler.postDelayed(() -> {
            if (destroyed || !isOcrEnabled() || !isActiveWindowSafeForTarget(expectedPackage, generation)
                    || database == null || !database.isAppEnabled(expectedPackage)
                    || database.isLabelSuppressed(expectedPackage, expectedLabel)
                    || SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) {
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
                                ocrInFlight = false;
                                return;
                            }
                            Bitmap bounded = resizeForOcr(bitmap);
                            if (bounded != bitmap && !bitmap.isRecycled()) bitmap.recycle();
                            runOcrConfirmation(bounded, expectedLabel, firstBounds, expectedPackage,
                                    generation, firstScore);
                            bitmap = null; // Ownership transferred to runOcrConfirmation().
                        } catch (Throwable failure) {
                            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                            ocrInFlight = false;
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        ocrInFlight = false;
                    }
                });
            } catch (Throwable ignored) {
                ocrInFlight = false;
            }
        }, CLICK_CONFIRM_DELAY_MS);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private void runOcrConfirmation(Bitmap bitmap, String expectedLabel, Rect firstBounds,
                                    String expectedPackage, long generation, int firstScore) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            boolean chineseLocale = java.util.Locale.getDefault().getLanguage().startsWith("zh");
            TextRecognizer recognizer = chineseLocale ? chineseRecognizer : latinRecognizer;
            Task<Text> task = recognizer.process(image);
            task.addOnCompleteListener(completed -> {
                try {
                    if (destroyed || !isOcrEnabled() || !completed.isSuccessful() || completed.getResult() == null
                            || !isActiveWindowSafeForTarget(expectedPackage, generation)
                            || database == null || !database.isAppEnabled(expectedPackage)
                            || database.isLabelSuppressed(expectedPackage, expectedLabel)
                            || SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) return;

                    Text result = completed.getResult();
                    String context = result.getText();
                    List<String> suppressedLabels = database.getSuppressedLabels(expectedPackage);
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    float scaleX = screenWidth / (float) Math.max(1, bitmap.getWidth());
                    float scaleY = screenHeight / (float) Math.max(1, bitmap.getHeight());
                    int tolerance = dp(48);

                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            if (TextMatcher.similarityPercent(expectedLabel, line.getText())
                                    < getSimilarityThreshold()) continue;
                            if (isSuppressedLabel(suppressedLabels, line.getText())) continue;
                            Rect box = line.getBoundingBox();
                            if (box == null) continue;
                            Rect mapped = new Rect(
                                    Math.round(box.left * scaleX), Math.round(box.top * scaleY),
                                    Math.round(box.right * scaleX), Math.round(box.bottom * scaleY));
                            boolean sameArea = Math.abs(mapped.centerX() - firstBounds.centerX()) <= tolerance
                                    && Math.abs(mapped.centerY() - firstBounds.centerY()) <= tolerance;
                            if (!sameArea) continue;

                            AdConfidenceScorer.Assessment second = AdConfidenceScorer.assess(
                                    line.getText(), context, true, true, true, mapped.width(), mapped.height(),
                                    screenWidth, screenHeight,
                                    isCountdownClickEnabled(), getMinimumSkipScore());
                            AdConfidenceScorer.Assessment first = new AdConfidenceScorer.Assessment(
                                    firstScore, firstScore >= 90 ? AdConfidenceScorer.Level.HIGH
                                            : AdConfidenceScorer.Level.REVIEW, "首次 OCR 观察");
                            if (second.score < getMinimumSkipScore()
                                    || !AdConfidenceScorer.canProceedAfterSecondObservation(
                                            first, second, getMinimumSkipScore())
                                    || !SafetyPolicy.isSafeTarget(mapped, screenWidth, screenHeight,
                                            true, true, true, line.getText(), context,
                                            isCountdownClickEnabled(), getMinimumSkipScore())) continue;

                            if (!isOcrEnabled() || generation != windowGeneration
                                    || !isActiveWindowSafeForTarget(expectedPackage, generation)
                                    || !database.isAppEnabled(expectedPackage)
                                    || database.isLabelSuppressed(expectedPackage, line.getText())
                                    || SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) return;
                            if (dispatchTap(mapped.centerX(), mapped.centerY())) {
                                lastClickAt = SystemClock.elapsedRealtime();
                                database.log(expectedPackage, safeLogLabel(line.getText()), "CLICK",
                                        "OCR 二次截图确认相同文字与位置；置信度=" + second.score);
                                refreshFloatingOverlay(true);
                            }
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    ocrInFlight = false;
                }
            });
        } catch (Throwable ignored) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isActiveWindowSafeForTarget(String expectedPackage, long generation) {
        if (destroyed || generation != windowGeneration) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            CharSequence packageName = root.getPackageName();
            return generation == windowGeneration && packageName != null
                    && expectedPackage.contentEquals(packageName)
                    && !containsSensitiveInput(root);
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
        if (node.isVisibleToUser() && (node.isPassword() || node.isEditable())) return true;
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

    private boolean dispatchTap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 65))
                .build();
        return dispatchGesture(gesture, null, mainHandler);
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
