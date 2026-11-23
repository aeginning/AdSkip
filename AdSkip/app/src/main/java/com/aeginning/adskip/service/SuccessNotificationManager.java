package com.aeginning.adskip.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.aeginning.adskip.MainActivity;

/**
 * Posts a compact, updating notification when AdSkip confirms a real interception.
 * A stable notification ID per event type keeps frequent DNS matches from flooding
 * the notification shade; every event still refreshes the visible count and detail.
 */
public final class SuccessNotificationManager {
    private static final String CHANNEL_ID = "adskip_interception_success";
    private static final int AD_SKIP_NOTIFICATION_ID = 1741;
    private static final int DNS_BLOCK_NOTIFICATION_ID = 1742;
    private static final String PREFS = "adskip_settings";
    private static final String ENABLED_KEY = "interception_success_notifications_enabled";

    private SuccessNotificationManager() { }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED_KEY, true);
    }

    public static void notifyAdSkip(Context context, String packageName) {
        if (context == null) return;
        String appLabel = resolveAppLabel(context, packageName);
        post(context, "ad_skip_success_count", AD_SKIP_NOTIFICATION_ID,
                "AdSkip · 广告跳过成功", appLabel + " 的广告页面已关闭或发生变化");
    }

    public static void notifyDnsBlocked(Context context, String domain) {
        if (context == null) return;
        String safeDomain = domain == null || domain.trim().isEmpty()
                ? "未知域名" : domain.trim();
        if (safeDomain.length() > 140) safeDomain = safeDomain.substring(0, 140);
        post(context, "dns_block_success_count", DNS_BLOCK_NOTIFICATION_ID,
                "AdSkip · DNS 域名已拦截", safeDomain);
    }

    private static void post(Context context, String countKey, int notificationId,
                             String title, String detail) {
        if (!isEnabled(context)) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;

        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            ensureChannel(manager);

            int count;
            synchronized (SuccessNotificationManager.class) {
                android.content.SharedPreferences prefs =
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                count = prefs.getInt(countKey, 0) + 1;
                prefs.edit().putInt(countKey, count).apply();
            }

            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(context, notificationId, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setContentIntent(pending)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setNumber(count)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .build();
            // Two stable IDs (ad skip / DNS) update in place instead of creating
            // hundreds of duplicate notifications during ad-heavy DNS traffic.
            manager.notify(notificationId, notification);
        } catch (SecurityException ignored) {
            // Notification permission may be revoked while a service is running.
        } catch (RuntimeException ignored) {
            // Notification failures must never interrupt ad recognition or DNS filtering.
        }
    }

    private static void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL_ID, "AdSkip 拦截成功",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("广告跳过成功和 DNS 域名拦截成功提醒；通知会合并更新，避免刷屏");
            channel.setShowBadge(true);
            manager.createNotificationChannel(channel);
        }
    }

    private static String resolveAppLabel(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return "目标应用";
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // Fall back to the package name when the target app was uninstalled.
        } catch (RuntimeException ignored) { }
        return packageName;
    }
}
