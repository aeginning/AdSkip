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
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.aeginning.adskip.db.RuleDatabase;
import com.aeginning.adskip.engine.SafetyPolicy;
import com.aeginning.adskip.engine.TextMatcher;
import com.aeginning.adskip.engine.AdConfidenceScorer;
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
    private static final int OCR_MAX_DIMENSION = 1280;
    private static final int MAX_NODES = 650;
    private static final int MAX_DEPTH = 14;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();
    private RuleDatabase database;
    private TextRecognizer chineseRecognizer;
    private TextRecognizer latinRecognizer;
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

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        destroyed = false;
        database = new RuleDatabase(this);
        chineseRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (destroyed || event == null || database == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        // Invalidate delayed actions even if Android emits a window event without a package name.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            windowGeneration++;
            cancelPendingNodeConfirmation();
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;
        String pkg = packageName.toString();
        // Keep only the latest window identity (no history and no screen text) for foreground diagnostics.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            getSharedPreferences("adskip_settings", MODE_PRIVATE).edit()
                    .putString("foreground_last_package", pkg)
                    .putString("foreground_last_class", className == null ? "" : className.toString())
                    .putLong("foreground_last_at", System.currentTimeMillis())
                    .apply();
        }
        if (pkg.equals(getPackageName()) || !database.isAppEnabled(pkg)) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastScanAt < EVENT_INTERVAL_MS) return;
        lastScanAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        hasSensitiveInputInCurrentWindow = false;
        nodeScanIncomplete = false;
        if (root == null) return;
        CharSequence rootPackage = root.getPackageName();
        if (rootPackage == null || !pkg.contentEquals(rootPackage)) {
            recycleNode(root);
            return;
        }

        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        Candidate candidate = null;
        String context = "";
        try {
            StringBuilder screenText = new StringBuilder();
            collectNodes(root, 0, nodes, screenText);
            if (nodeScanIncomplete || hasSensitiveInputInCurrentWindow) return;
            context = screenText.toString();
            candidate = chooseCandidate(nodes, context, pkg);
            if (candidate != null) {
                scheduleNodeConfirmation(candidate, pkg, windowGeneration);
            } else {
                boolean allowVisualProbe = true;
                maybeRunOcr(pkg, TextMatcher.hasAdContext(context), allowVisualProbe, windowGeneration);
            }
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
        if (password || editable) hasSensitiveInputInCurrentWindow = true;
        // Do not extract text from editable controls; password and editable-input screens also disable OCR fallback.
        if (!password && !editable) {
            CharSequence label = node.getText();
            CharSequence description = node.getContentDescription();
            if (label != null && label.length() > 0 && text.length() < 16000) text.append(label, 0, Math.min(label.length(), 16000 - text.length())).append(' ');
            if (description != null && description.length() > 0 && text.length() < 16000) text.append(description, 0, Math.min(description.length(), 16000 - text.length())).append(' ');
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
        List<String> suppressedLabels = database == null
                ? java.util.Collections.emptyList() : database.getSuppressedLabels(packageName);
        for (AccessibilityNodeInfo node : nodes) {
            String label = readLabel(node);
            if (isSuppressedLabel(suppressedLabels, label)) continue;
            ClickableTarget target = nearestClickable(node);
            if (target == null) continue;
            boolean selected = false;
            try {
                AccessibilityNodeInfo clickable = target.node;
                Rect bounds = new Rect();
                clickable.getBoundsInScreen(bounds);
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(label, context,
                        clickable.isClickable(), clickable.isEnabled(), clickable.isVisibleToUser(),
                        bounds.width(), bounds.height(), width, height);
                int score = assessment.score;
                if (score <= bestScore || score < 75) continue;
                if (!SafetyPolicy.isSafeTarget(bounds, width, height,
                        clickable.isClickable(), clickable.isEnabled(), clickable.isVisibleToUser(), label, context)) continue;

                if (best != null && best.ownsNode) recycleNode(best.node);
                best = new Candidate(clickable, target.owned, new Rect(bounds), label, score);
                bestScore = score;
                selected = true;
            } finally {
                if (!selected && target.owned) recycleNode(target.node);
            }
        }
        return best;
    }

    private boolean isSuppressedLabel(List<String> phrases, String candidate) {
        String normalized = AdConfidenceScorer.normalize(candidate);
        if (normalized.isEmpty()) return false;
        for (String phrase : phrases) {
            if (phrase != null && normalized.contains(AdConfidenceScorer.normalize(phrase))) return true;
        }
        return false;
    }

    /** Two observations are required. The node is reacquired after the delay; stale nodes are never clicked. */
    private void scheduleNodeConfirmation(Candidate first, String packageName, long generation) {
        String signature = packageName + "|" + AdConfidenceScorer.normalize(first.label);
        if (signature.equals(pendingNodeSignature)) return;
        cancelPendingNodeConfirmation();
        pendingNodeSignature = signature;
        final Rect initialBounds = new Rect(first.bounds);
        final String initialLabel = first.label;
        final int initialScore = first.score;
        pendingNodeConfirmation = () -> {
            pendingNodeConfirmation = null;
            pendingNodeSignature = null;
            if (destroyed || database == null || generation != windowGeneration || !database.isAppEnabled(packageName)) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !packageName.contentEquals(rootPackage)) {
                recycleNode(root);
                return;
            }
            hasSensitiveInputInCurrentWindow = false;
            nodeScanIncomplete = false;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            Candidate second = null;
            try {
                StringBuilder text = new StringBuilder();
                collectNodes(root, 0, nodes, text);
                if (nodeScanIncomplete || hasSensitiveInputInCurrentWindow) return;
                second = chooseCandidate(nodes, text.toString(), packageName);
                if (second == null) return;
                boolean sameLabel = AdConfidenceScorer.normalize(initialLabel)
                        .equals(AdConfidenceScorer.normalize(second.label));
                boolean sameArea = Math.abs(initialBounds.centerX() - second.bounds.centerX()) <= 48
                        && Math.abs(initialBounds.centerY() - second.bounds.centerY()) <= 48;
                AdConfidenceScorer.Assessment firstAssessment = new AdConfidenceScorer.Assessment(
                        initialScore, initialScore >= 90 ? AdConfidenceScorer.Level.HIGH : AdConfidenceScorer.Level.REVIEW,
                        "首次观察");
                AdConfidenceScorer.Assessment secondAssessment = AdConfidenceScorer.assess(second.label, text.toString(),
                        second.node.isClickable(), second.node.isEnabled(), second.node.isVisibleToUser(),
                        second.bounds.width(), second.bounds.height(),
                        getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
                if (!sameLabel || !sameArea
                        || !AdConfidenceScorer.canProceedAfterSecondObservation(firstAssessment, secondAssessment)) {
                    database.log(packageName, safeLogLabel(initialLabel), "BLOCKED", "二次识别不一致或置信度不足");
                    return;
                }
                executeNodeCandidate(second, packageName, text.toString());
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

    private void executeNodeCandidate(Candidate candidate, String packageName, String context) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickAt < CLICK_COOLDOWN_MS) return;
        if (!SafetyPolicy.isSafeTarget(candidate.bounds,
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels,
                candidate.node.isClickable(), candidate.node.isEnabled(), candidate.node.isVisibleToUser(), candidate.label, context)) {
            database.log(packageName, safeLogLabel(candidate.label), "BLOCKED", "SafetyPolicy rejected node candidate");
            return;
        }

        boolean clicked = candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) clicked = dispatchTap(candidate.bounds.centerX(), candidate.bounds.centerY());
        if (clicked) {
            lastClickAt = now;
            database.log(packageName, safeLogLabel(candidate.label), "CLICK", "Matched skip label; score=" + candidate.score);
        } else {
            database.log(packageName, safeLogLabel(candidate.label), "NO_ACTION", "Control did not accept click");
        }
    }

    private void maybeRunOcr(String expectedPackage, boolean adContext, boolean allowVisualProbe, long generation) {
        if (Build.VERSION.SDK_INT < 30 || ocrInFlight || hasSensitiveInputInCurrentWindow || !isOcrEnabled()) return;
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
        final int sourceWidth = bitmap.getWidth();
        final int sourceHeight = bitmap.getHeight();
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            boolean chineseLocale = java.util.Locale.getDefault().getLanguage().startsWith("zh");
            TextRecognizer recognizer = chineseLocale ? chineseRecognizer : latinRecognizer;
            Task<Text> task = recognizer.process(image);
            task.addOnCompleteListener(completed -> {
                try {
                    if (destroyed || !completed.isSuccessful() || completed.getResult() == null) return;
                    Text result = completed.getResult();
                    String context = result.getText();
                    OcrCandidate best = null;
                    int bestScore = 0;
                    boolean adContext = TextMatcher.hasAdContext(context);
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String lineText = line.getText();
                            Rect box = line.getBoundingBox();
                            if (box == null || (database != null && database.isLabelSuppressed(expectedPackage, lineText))) continue;
                            AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(
                                    lineText, context, true, true, true, box.width(), box.height(),
                                    bitmap.getWidth(), bitmap.getHeight());
                            if (assessment.score >= 85 && assessment.score > bestScore) {
                                best = new OcrCandidate(new Rect(box), lineText, assessment.score);
                                bestScore = assessment.score;
                            }
                        }
                    }
                    if (best != null) {
                        OcrCandidate selected = best;
                        mainHandler.post(() -> executeOcrCandidate(selected, context, sourceWidth,
                                sourceHeight, expectedPackage, generation));
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    ocrInFlight = false;
                }
            });
        } catch (Throwable t) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            ocrInFlight = false;
        }
    }

    private void executeOcrCandidate(OcrCandidate candidate, String context, int bitmapWidth, int bitmapHeight,
                                     String expectedPackage, long generation) {
        if (!isOcrEnabled() || !isActiveWindowSafeForTarget(expectedPackage, generation)
                || database == null || !database.isAppEnabled(expectedPackage)
                || database.isLabelSuppressed(expectedPackage, candidate.label)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickAt < CLICK_COOLDOWN_MS) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        float scaleX = screenWidth / (float) Math.max(1, bitmapWidth);
        float scaleY = screenHeight / (float) Math.max(1, bitmapHeight);
        Rect mapped = new Rect(
                Math.round(candidate.bounds.left * scaleX),
                Math.round(candidate.bounds.top * scaleY),
                Math.round(candidate.bounds.right * scaleX),
                Math.round(candidate.bounds.bottom * scaleY));
        AdConfidenceScorer.Assessment assessment = AdConfidenceScorer.assess(candidate.label, context,
                true, true, true, mapped.width(), mapped.height(), screenWidth, screenHeight);
        if (assessment.score < 85 || !SafetyPolicy.isSafeTarget(mapped, screenWidth, screenHeight,
                true, true, true, candidate.label, context)) {
            database.log(expectedPackage, safeLogLabel(candidate.label), "BLOCKED", "OCR 置信度或安全审核未通过");
            return;
        }
        // Coordinate OCR actions require a second screenshot and matching text/position.
        confirmOcrCandidate(candidate.label, mapped, expectedPackage, generation, assessment.score);
    }

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
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    float scaleX = screenWidth / (float) Math.max(1, bitmap.getWidth());
                    float scaleY = screenHeight / (float) Math.max(1, bitmap.getHeight());
                    int tolerance = dp(48);

                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            if (!AdConfidenceScorer.normalize(expectedLabel)
                                    .equals(AdConfidenceScorer.normalize(line.getText()))) continue;
                            if (database.isLabelSuppressed(expectedPackage, line.getText())) continue;
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
                                    screenWidth, screenHeight);
                            AdConfidenceScorer.Assessment first = new AdConfidenceScorer.Assessment(
                                    firstScore, firstScore >= 90 ? AdConfidenceScorer.Level.HIGH
                                            : AdConfidenceScorer.Level.REVIEW, "首次 OCR 观察");
                            if (second.score < 85
                                    || !AdConfidenceScorer.canProceedAfterSecondObservation(first, second)
                                    || !SafetyPolicy.isSafeTarget(mapped, screenWidth, screenHeight,
                                            true, true, true, line.getText(), context)) continue;

                            if (!isOcrEnabled() || generation != windowGeneration
                                    || !isActiveWindowSafeForTarget(expectedPackage, generation)
                                    || !database.isAppEnabled(expectedPackage)
                                    || database.isLabelSuppressed(expectedPackage, line.getText())
                                    || SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) return;
                            if (dispatchTap(mapped.centerX(), mapped.centerY())) {
                                lastClickAt = SystemClock.elapsedRealtime();
                                database.log(expectedPackage, safeLogLabel(line.getText()), "CLICK",
                                        "OCR 二次截图确认相同文字与位置；置信度=" + second.score);
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
    }

    @Override public void onDestroy() {
        destroyed = true;
        windowGeneration++;
        cancelPendingNodeConfirmation();
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
