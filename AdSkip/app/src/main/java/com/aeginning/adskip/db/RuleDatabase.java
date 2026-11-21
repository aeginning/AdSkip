package com.aeginning.adskip.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.aeginning.adskip.engine.TextMatcher;

/** Local-only rule database. No screen text or network traffic is uploaded. */
public final class RuleDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "adskip_rules.db";
    private static final int DB_VERSION = 3;
    private static final long DOMAIN_CACHE_TTL_MS = 2_000L;

    /**
     * Conservative starter rules compiled from common ad-provider and tracker hostnames
     * represented by maintained Android/DNS blocklists. This is deliberately a small,
     * editable starter set, not a full third-party list subscription.
     */
    private static final String[] DEFAULT_BLOCKED_DOMAINS = {
            // Google advertising and measurement endpoints
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "adservice.google.com",
            "app-measurement.com",

            // Microsoft, Amazon, Yahoo and TikTok advertising endpoints
            "ads.msn.com",
            "amazon-adsystem.com",
            "ads.yahoo.com",
            "ads.tiktok.com",
            "analytics.tiktok.com",

            // Meta ad measurement endpoints (not facebook.com or instagram.com)
            "an.facebook.com",
            "pixel.facebook.com",

            // Tencent, Baidu and Alibaba advertising / measurement endpoints
            "e.qq.com",
            "gdt.qq.com",
            "pgdt.gtimg.cn",
            "cpro.baidu.com",
            "pos.baidu.com",
            "mobads.baidu.com",
            "hm.baidu.com",
            "tanx.com",
            "mmstat.com",

            // Common global advertising exchanges and analytics providers
            "adnxs.com",
            "criteo.com",
            "taboola.com",
            "outbrain.com",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "smartadserver.com",
            "adform.net",
            "moatads.com",
            "quantserve.com",
            "scorecardresearch.com",

            // Common mobile advertising and attribution SDK providers
            "applovin.com",
            "ironsrc.com",
            "vungle.com",
            "chartboost.com",
            "tapjoy.com",
            "adcolony.com",
            "inmobi.com",
            "adjust.com"
    };

    private Set<String> blockedDomainCache;
    private long blockedDomainCacheAt;

    public RuleDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE app_rules (package_name TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE domain_rules (domain TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE action_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT, matched_text TEXT, action TEXT NOT NULL, reason TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_action_logs_created ON action_logs(created_at DESC)");
        db.execSQL("CREATE TABLE suppressed_labels (package_name TEXT NOT NULL, phrase TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(package_name, phrase))");
        db.execSQL("CREATE INDEX idx_suppressed_labels_package ON suppressed_labels(package_name)");
        insertDefaultDomainRules(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS suppressed_labels (package_name TEXT NOT NULL, phrase TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(package_name, phrase))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_suppressed_labels_package ON suppressed_labels(package_name)");
        }
        if (oldVersion < 3) {
            // Append missing defaults without deleting or replacing user-defined rules.
            insertDefaultDomainRules(db);
        }
    }

    private static void insertDefaultDomainRules(SQLiteDatabase db) {
        for (String domain : DEFAULT_BLOCKED_DOMAINS) {
            ContentValues values = new ContentValues();
            values.put("domain", domain);
            values.put("enabled", 1);
            db.insertWithOnConflict("domain_rules", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    public synchronized void setAppEnabled(String packageName, boolean enabled) {
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("enabled", enabled ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("app_rules", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized boolean isAppEnabled(String packageName) {
        try (Cursor c = getReadableDatabase().query("app_rules", new String[]{"enabled"},
                "package_name=?", new String[]{packageName}, null, null, null)) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }

    public synchronized List<String> getEnabledApps() {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("app_rules", new String[]{"package_name"},
                "enabled=1", null, null, null, "package_name ASC")) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    public synchronized void replaceDomainRules(List<String> domains) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("domain_rules", null, null);
            for (String raw : domains) {
                String domain = normalizeDomain(raw);
                if (domain.isEmpty()) continue;
                ContentValues values = new ContentValues();
                values.put("domain", domain);
                values.put("enabled", 1);
                db.insertWithOnConflict("domain_rules", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            blockedDomainCache = null;
            blockedDomainCacheAt = 0;
        }
    }

    public synchronized List<String> getBlockedDomains() {
        List<String> result = new ArrayList<>(getBlockedDomainSnapshot());
        Collections.sort(result);
        return result;
    }

    public synchronized boolean isDomainBlocked(String rawDomain) {
        String domain = normalizeDomain(rawDomain);
        if (domain.isEmpty()) return false;
        Set<String> rules = getBlockedDomainSnapshot();
        if (rules.contains(domain)) return true;
        int dot = domain.indexOf('.');
        while (dot >= 0 && dot < domain.length() - 1) {
            if (rules.contains(domain.substring(dot + 1))) return true;
            dot = domain.indexOf('.', dot + 1);
        }
        return false;
    }

    /** Cache the small, user-managed domain rule set to avoid a DB cursor per DNS packet. */
    private Set<String> getBlockedDomainSnapshot() {
        long now = System.currentTimeMillis();
        if (blockedDomainCache != null && now - blockedDomainCacheAt < DOMAIN_CACHE_TTL_MS) {
            return blockedDomainCache;
        }
        Set<String> refreshed = new HashSet<>();
        try (Cursor c = getReadableDatabase().query("domain_rules", new String[]{"domain"},
                "enabled=1", null, null, null, null)) {
            while (c.moveToNext()) {
                String domain = c.getString(0);
                if (domain != null) refreshed.add(domain);
            }
        }
        blockedDomainCache = Collections.unmodifiableSet(refreshed);
        blockedDomainCacheAt = now;
        return blockedDomainCache;
    }

    public synchronized void log(String packageName, String matchedText, String action, String reason) {
        ContentValues values = new ContentValues();
        values.put("package_name", packageName == null ? "" : packageName);
        values.put("matched_text", matchedText == null ? "" : matchedText);
        values.put("action", action);
        values.put("reason", reason == null ? "" : reason);
        values.put("created_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.insert("action_logs", null, values);
        // Keep the local diagnostic database bounded.
        db.execSQL("DELETE FROM action_logs WHERE id NOT IN (SELECT id FROM action_logs ORDER BY created_at DESC LIMIT 2000)");
    }



    /** Add a local false-positive suppression phrase for one app. */
    public synchronized boolean addSuppressedLabel(String packageName, String phrase) {
        if (packageName == null || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) return false;
        String normalized = TextMatcher.normalize(phrase);
        if (normalized.length() < 2 || normalized.length() > 48 || TextMatcher.isDangerous(normalized)) return false;
        try (Cursor count = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM suppressed_labels WHERE package_name=? AND phrase<>?",
                new String[]{packageName, normalized})) {
            if (count.moveToFirst() && count.getInt(0) >= 100) return false;
        }
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("phrase", normalized);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("suppressed_labels", null, values,
                SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    public synchronized boolean isLabelSuppressed(String packageName, String candidateLabel) {
        if (packageName == null || candidateLabel == null || candidateLabel.isEmpty()) return false;
        String candidate = TextMatcher.normalize(candidateLabel);
        try (Cursor c = getReadableDatabase().query("suppressed_labels", new String[]{"phrase"},
                "package_name=?", new String[]{packageName}, null, null, null)) {
            while (c.moveToNext()) {
                String phrase = c.getString(0);
                if (phrase != null && candidate.contains(phrase)) return true;
            }
        }
        return false;
    }

    public synchronized List<String> getSuppressedLabels(String packageName) {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("suppressed_labels", new String[]{"phrase"},
                "package_name=?", new String[]{packageName}, null, null, "phrase ASC", "100")) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    public synchronized void removeSuppressedLabel(String packageName, String phrase) {
        if (packageName == null || phrase == null) return;
        getWritableDatabase().delete("suppressed_labels", "package_name=? AND phrase=?",
                new String[]{packageName, TextMatcher.normalize(phrase)});
    }

    public static final class Stats24h {
        public final int clickCount;
        public final int blockedCount;
        public final int noActionCount;
        public final int dnsBlockCount;
        public final int errorCount;
        Stats24h(int clicks, int blocked, int noAction, int dnsBlocks, int errors) {
            clickCount = clicks; blockedCount = blocked; noActionCount = noAction;
            dnsBlockCount = dnsBlocks; errorCount = errors;
        }
        public String toDisplayText() {
            return "近 24 小时：自动点击 " + clickCount + " 次，安全拦截 " + blockedCount
                    + " 次，未执行 " + noActionCount + " 次，DNS 拦截 " + dnsBlockCount
                    + " 次，错误 " + errorCount + " 次。";
        }
    }

    public synchronized Stats24h getStats24h() {
        int clicks = 0, blocked = 0, noAction = 0, dnsBlocks = 0, errors = 0;
        long since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT action, COUNT(*) FROM action_logs WHERE created_at>=? GROUP BY action",
                new String[]{String.valueOf(since)})) {
            while (c.moveToNext()) {
                String action = c.getString(0);
                int count = c.getInt(1);
                if ("CLICK".equals(action)) clicks = count;
                else if ("BLOCKED".equals(action)) blocked = count;
                else if ("NO_ACTION".equals(action)) noAction = count;
                else if ("BLOCK".equals(action)) dnsBlocks = count;
                else if ("ERROR".equals(action)) errors = count;
            }
        }
        return new Stats24h(clicks, blocked, noAction, dnsBlocks, errors);
    }

    public synchronized List<String> getRecentLogs(int limit) {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT package_name, matched_text, action, reason, created_at FROM action_logs ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, Math.min(limit, 300)))})) {
            while (c.moveToNext()) {
                result.add(android.text.format.DateFormat.format("MM-dd HH:mm:ss", c.getLong(4))
                        + "  [" + c.getString(2) + "]  " + c.getString(0)
                        + "  " + c.getString(1) + "\n     " + c.getString(3));
            }
        }
        return result;
    }

    public synchronized void clearLogs() { getWritableDatabase().delete("action_logs", null, null); }

    public static String normalizeDomain(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith(".")) value = value.substring(1);
        if (value.startsWith("http://")) value = value.substring(7);
        if (value.startsWith("https://")) value = value.substring(8);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        if (value.startsWith("*.")) value = value.substring(2);
        if (value.length() > 253 || !value.matches("(?i)(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return "";
        return value;
    }
}
