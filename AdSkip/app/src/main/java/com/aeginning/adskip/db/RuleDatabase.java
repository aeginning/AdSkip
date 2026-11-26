package com.aeginning.adskip.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.aeginning.adskip.engine.TextMatcher;
import com.aeginning.adskip.engine.DomainRuleValidator;

/** Local-only rule database. No screen text or network traffic is uploaded. */
public final class RuleDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "adskip_rules.db";
    private static final int DB_VERSION = 5;
    private static final long DOMAIN_CACHE_TTL_MS = 3_000L;
    private static final int DOMAIN_CACHE_MAX_ENTRIES = 2_048;
    private static final int MAX_SUBSCRIPTION_RULES = 120_000;
    private static final int MAX_DOMAIN_RULES = 100_000;
    private static final int MAX_LOG_ENTRIES = 2_000;
    private static final int LOG_TRIM_INTERVAL = 64;
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

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
            "adjust.com",

            // Selected OEM and mobile-ad endpoints from maintained mobile ad filters.
            // Exact hosts only; avoid broad vendor root domains that carry normal services.
            "api.ad.xiaomi.com",
            "adv.sec.miui.com",
            "adv.sec.intl.miui.com",
            "adx-open-service.youku.com",
            "ad.youku.com",
            "ykad-data.youku.com",
            "cad.youku.com",
            "sspapi-prd.samsungrs.com",
            "admin.appnext.com",
            "global.appnext.com",
            "cdn.appnext.com",
            "api.appnxt.net",
            "apis.appnxt.net",
            "banners-slb.mobile.yandex.net",
            "banners.mobile.yandex.net",
            "pangle.io",
            "dm.toutiao.com",
            "tradplusad.com",
            "ad.tencentmusic.com",
            "appodeal.com",
            "appbaqend.com"
    };

    private final LinkedHashMap<String, DomainCacheEntry> domainLookupCache =
            new LinkedHashMap<String, DomainCacheEntry>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, DomainCacheEntry> eldest) {
                    return size() > DOMAIN_CACHE_MAX_ENTRIES;
                }
            };

    public RuleDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE app_rules (package_name TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE domain_rules (domain TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE action_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT, matched_text TEXT, action TEXT NOT NULL, reason TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_action_logs_created ON action_logs(created_at DESC)");
        db.execSQL("CREATE TABLE suppressed_labels (package_name TEXT NOT NULL, phrase TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(package_name, phrase))");
        db.execSQL("CREATE INDEX idx_suppressed_labels_package ON suppressed_labels(package_name)");
        createDnsSubscriptionTables(db);
        insertDefaultDomainRules(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS suppressed_labels (package_name TEXT NOT NULL, phrase TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(package_name, phrase))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_suppressed_labels_package ON suppressed_labels(package_name)");
        }
        if (oldVersion < 4) {
            // Append newly curated defaults without deleting or replacing user-defined rules.
            insertDefaultDomainRules(db);
        }
        if (oldVersion < 5) {
            createDnsSubscriptionTables(db);
        }
    }

    private static void createDnsSubscriptionTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dns_subscriptions ("
                + "feed_id TEXT PRIMARY KEY NOT NULL, feed_name TEXT NOT NULL, "
                + "feed_version TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1, "
                + "rule_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS dns_subscription_rules ("
                + "feed_id TEXT NOT NULL, domain TEXT NOT NULL, PRIMARY KEY(feed_id, domain))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dns_subscription_rules_domain "
                + "ON dns_subscription_rules(domain)");
    }

    private static void insertDefaultDomainRules(SQLiteDatabase db) {
        for (String domain : DEFAULT_BLOCKED_DOMAINS) {
            ContentValues values = new ContentValues();
            values.put("domain", domain);
            values.put("enabled", 1);
            db.insertWithOnConflict("domain_rules", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    public static final class DnsFeedInfo {
        public final String feedId;
        public final String feedName;
        public final String version;
        public final boolean enabled;
        public final int ruleCount;
        public final long updatedAt;

        DnsFeedInfo(String feedId, String feedName, String version,
                    boolean enabled, int ruleCount, long updatedAt) {
            this.feedId = feedId;
            this.feedName = feedName;
            this.version = version;
            this.enabled = enabled;
            this.ruleCount = ruleCount;
            this.updatedAt = updatedAt;
        }

        public String toDisplayText() {
            return feedName + " · " + ruleCount + " 条 · "
                    + (enabled ? "已启用" : "已停用")
                    + (updatedAt > 0 ? " · " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", updatedAt) : "");
        }
    }

    public static final class DnsFeedImportResult {
        public final boolean success;
        public final int savedCount;
        public final String error;

        DnsFeedImportResult(boolean success, int savedCount, String error) {
            this.success = success;
            this.savedCount = savedCount;
            this.error = error == null ? "" : error;
        }
    }

    /**
     * Atomically replace one downloaded feed only after strict validation.
     * Existing manual rules and other feed subscriptions are never changed.
     * An existing feed's enabled/disabled preference survives an update.
     */
    public synchronized DnsFeedImportResult replaceDnsFeed(
            String feedId, String feedName, String version, List<String> rawDomains) {
        if (!("hagezi-light".equals(feedId) || "stevenblack-base".equals(feedId))
                || feedName == null || feedName.trim().isEmpty() || rawDomains == null) {
            return new DnsFeedImportResult(false, 0, "不支持的 DNS 订阅来源");
        }
        if (rawDomains.size() < 1_000 || rawDomains.size() > MAX_SUBSCRIPTION_RULES) {
            return new DnsFeedImportResult(false, 0, "订阅条目数量超出安全范围");
        }

        Set<String> normalized = new HashSet<>();
        int invalid = 0;
        for (String raw : rawDomains) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String domain = normalizeDomain(raw);
            if (domain.isEmpty()) invalid++;
            else normalized.add(domain);
            if (normalized.size() > MAX_SUBSCRIPTION_RULES) {
                return new DnsFeedImportResult(false, normalized.size(), "订阅超过最大规则数量");
            }
        }
        if (invalid > 0 || normalized.size() < 1_000) {
            return new DnsFeedImportResult(false, normalized.size(),
                    "订阅校验失败：包含 " + invalid + " 条无效域名或有效条目不足；原订阅未修改");
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int previouslyEnabled = 1;
            try (Cursor existing = db.query("dns_subscriptions", new String[]{"enabled"},
                    "feed_id=?", new String[]{feedId}, null, null, null)) {
                if (existing.moveToFirst()) previouslyEnabled = existing.getInt(0);
            }

            ContentValues metadata = new ContentValues();
            metadata.put("feed_id", feedId);
            metadata.put("feed_name", feedName.trim());
            metadata.put("feed_version", version == null ? "" : version.trim());
            metadata.put("enabled", previouslyEnabled);
            metadata.put("rule_count", normalized.size());
            metadata.put("updated_at", System.currentTimeMillis());
            long metadataRow = db.insertWithOnConflict(
                    "dns_subscriptions", null, metadata, SQLiteDatabase.CONFLICT_REPLACE);
            if (metadataRow == -1) throw new IllegalStateException("subscription metadata insert failed");

            db.delete("dns_subscription_rules", "feed_id=?", new String[]{feedId});
            SQLiteStatement insert = db.compileStatement(
                    "INSERT OR IGNORE INTO dns_subscription_rules(feed_id, domain) VALUES (?, ?)");
            try {
                for (String domain : normalized) {
                    insert.clearBindings();
                    insert.bindString(1, feedId);
                    insert.bindString(2, domain);
                    if (insert.executeInsert() == -1) {
                        throw new IllegalStateException("subscription rule insert failed");
                    }
                }
            } finally {
                insert.close();
            }
            db.setTransactionSuccessful();
            return new DnsFeedImportResult(true, normalized.size(), "");
        } catch (RuntimeException failure) {
            return new DnsFeedImportResult(false, 0,
                    "订阅写入失败：" + failure.getClass().getSimpleName());
        } finally {
            db.endTransaction();
            clearDomainLookupCache();
        }
    }

    public synchronized List<DnsFeedInfo> getDnsFeeds() {
        List<DnsFeedInfo> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("dns_subscriptions",
                new String[]{"feed_id", "feed_name", "feed_version", "enabled", "rule_count", "updated_at"},
                null, null, null, null, "feed_name ASC")) {
            while (c.moveToNext()) {
                result.add(new DnsFeedInfo(c.getString(0), c.getString(1), c.getString(2),
                        c.getInt(3) == 1, c.getInt(4), c.getLong(5)));
            }
        }
        return result;
    }

    public synchronized boolean setDnsFeedEnabled(String feedId, boolean enabled) {
        if (!isKnownFeedId(feedId)) return false;
        ContentValues values = new ContentValues();
        values.put("enabled", enabled ? 1 : 0);
        boolean updated = getWritableDatabase().update("dns_subscriptions", values,
                "feed_id=?", new String[]{feedId}) > 0;
        if (updated) clearDomainLookupCache();
        return updated;
    }

    public synchronized boolean removeDnsFeed(String feedId) {
        if (!isKnownFeedId(feedId)) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("dns_subscription_rules", "feed_id=?", new String[]{feedId});
            db.delete("dns_subscriptions", "feed_id=?", new String[]{feedId});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
            clearDomainLookupCache();
        }
    }

    /** Fast approximate total using indexed/manual count plus stored subscription counts. */
    public synchronized int getActiveDomainRuleCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT (SELECT COUNT(*) FROM domain_rules WHERE enabled=1) + "
                        + "(SELECT COALESCE(SUM(rule_count), 0) FROM dns_subscriptions WHERE enabled=1)", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int getManualDomainRuleCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM domain_rules WHERE enabled=1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void invalidateDomainCache() { clearDomainLookupCache(); }

    private static boolean isKnownFeedId(String feedId) {
        return "hagezi-light".equals(feedId) || "stevenblack-base".equals(feedId);
    }

    private void clearDomainLookupCache() {
        domainLookupCache.clear();
    }

    private static final class DomainCacheEntry {
        final boolean blocked;
        final long expiresAt;
        DomainCacheEntry(boolean blocked, long expiresAt) {
            this.blocked = blocked;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Persist the visible launcher selection as one SQLite transaction.
     * Packages not present in the current launcher list are intentionally left untouched.
     */
    public synchronized boolean updateAppRules(List<String> packageNames, Set<String> enabledPackages) {
        if (packageNames == null || enabledPackages == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String packageName : packageNames) {
                if (packageName == null || packageName.length() > 255
                        || !PACKAGE_NAME.matcher(packageName).matches()) continue;
                ContentValues values = new ContentValues();
                values.put("package_name", packageName);
                values.put("enabled", enabledPackages.contains(packageName) ? 1 : 0);
                long rowId = db.insertWithOnConflict("app_rules", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
                if (rowId == -1) return false;
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
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

    /**
     * Replace the DNS rule set atomically. Any invalid non-empty input aborts
     * before the existing user rules are touched.
     */
    public synchronized DomainRuleReplaceResult replaceDomainRules(List<String> domains) {
        if (domains == null) return new DomainRuleReplaceResult(false, 0, 1);
        Set<String> normalizedDomains = new HashSet<>();
        int invalidCount = 0;
        for (String raw : domains) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String domain = normalizeDomain(raw);
            if (domain.isEmpty()) invalidCount++;
            else normalizedDomains.add(domain);
            if (normalizedDomains.size() > MAX_DOMAIN_RULES) {
                return new DomainRuleReplaceResult(false, normalizedDomains.size(), invalidCount + 1);
            }
        }
        if (invalidCount > 0) {
            return new DomainRuleReplaceResult(false, normalizedDomains.size(), invalidCount);
        }
        // Empty input is not an implicit deletion. The UI must call clearDomainRules()
        // only after the user explicitly confirms that destructive action.
        if (normalizedDomains.isEmpty()) {
            return new DomainRuleReplaceResult(true, 0, 0);
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("domain_rules", null, null);
            for (String domain : normalizedDomains) {
                ContentValues values = new ContentValues();
                values.put("domain", domain);
                values.put("enabled", 1);
                db.insertWithOnConflict("domain_rules", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            clearDomainLookupCache();
        }
        return new DomainRuleReplaceResult(true, normalizedDomains.size(), 0);
    }

    /**
     * Merge imported manual DNS rules without deleting or changing existing rules.
     * Disabled existing entries remain disabled; new entries are enabled.
     */
    public synchronized DomainRuleMergeResult mergeDomainRules(List<String> domains) {
        if (domains == null) return new DomainRuleMergeResult(false, 0, 0, 1);
        Set<String> normalizedDomains = new HashSet<>();
        int invalidCount = 0;
        for (String raw : domains) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String domain = normalizeDomain(raw);
            if (domain.isEmpty()) invalidCount++;
            else normalizedDomains.add(domain);
            if (normalizedDomains.size() > MAX_DOMAIN_RULES) {
                return new DomainRuleMergeResult(false, 0, normalizedDomains.size(), invalidCount + 1);
            }
        }
        if (invalidCount > 0) {
            return new DomainRuleMergeResult(false, 0, normalizedDomains.size(), invalidCount);
        }
        if (normalizedDomains.isEmpty()) {
            return new DomainRuleMergeResult(true, 0, 0, 0);
        }

        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            int existingCount;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM domain_rules", null)) {
                existingCount = c.moveToFirst() ? c.getInt(0) : 0;
            }
            Set<String> existing = new HashSet<>();
            try (Cursor c = db.query("domain_rules", new String[]{"domain"},
                    null, null, null, null, null)) {
                while (c.moveToNext()) existing.add(c.getString(0));
            }
            int newCount = 0;
            for (String domain : normalizedDomains) {
                if (!existing.contains(domain)) newCount++;
            }
            if (existingCount + newCount > MAX_DOMAIN_RULES) {
                return new DomainRuleMergeResult(false, 0, normalizedDomains.size(),
                        existingCount + newCount - MAX_DOMAIN_RULES);
            }
            for (String domain : normalizedDomains) {
                if (existing.contains(domain)) continue;
                ContentValues values = new ContentValues();
                values.put("domain", domain);
                values.put("enabled", 1);
                long rowId = db.insertWithOnConflict(
                        "domain_rules", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (rowId != -1L) added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            if (added > 0) clearDomainLookupCache();
        }
        return new DomainRuleMergeResult(true, added, normalizedDomains.size(), 0);
    }

    public static final class DomainRuleMergeResult {
        public final boolean success;
        public final int addedCount;
        public final int importedCount;
        public final int errorCount;

        DomainRuleMergeResult(boolean success, int addedCount, int importedCount, int errorCount) {
            this.success = success;
            this.addedCount = addedCount;
            this.importedCount = importedCount;
            this.errorCount = errorCount;
        }
    }

    /** Clear rules only after an explicit confirmation in the UI. */
    public synchronized void clearDomainRules() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("domain_rules", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            clearDomainLookupCache();
        }
    }

    public static final class DomainRuleReplaceResult {
        public final boolean success;
        public final int savedCount;
        public final int invalidCount;

        DomainRuleReplaceResult(boolean success, int savedCount, int invalidCount) {
            this.success = success;
            this.savedCount = savedCount;
            this.invalidCount = invalidCount;
        }
    }

    /** Return enabled manual and subscribed DNS domains for complete portable export. */
    public synchronized List<String> getAllEnabledDomainRules() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT domain FROM domain_rules WHERE enabled=1 "
                + "UNION SELECT r.domain FROM dns_subscription_rules r "
                + "INNER JOIN dns_subscriptions s ON s.feed_id=r.feed_id WHERE s.enabled=1 "
                + "ORDER BY domain ASC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    /** Return only manually editable rules for the local-rule management screen. */
    public synchronized List<String> getBlockedDomains() {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("domain_rules", new String[]{"domain"},
                "enabled=1", null, null, null, "domain ASC")) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    public synchronized boolean isDomainBlocked(String rawDomain) {
        String domain = normalizeDomain(rawDomain);
        if (domain.isEmpty()) return false;

        long now = SystemClock.elapsedRealtime();
        DomainCacheEntry cached = domainLookupCache.get(domain);
        if (cached != null && cached.expiresAt > now) return cached.blocked;

        List<String> candidates = new ArrayList<>();
        candidates.add(domain);
        int dot = domain.indexOf('.');
        while (dot >= 0 && dot < domain.length() - 1) {
            String suffix = domain.substring(dot + 1);
            if (suffix.indexOf('.') >= 0) candidates.add(suffix);
            dot = domain.indexOf('.', dot + 1);
        }

        String placeholders = String.join(",", Collections.nCopies(candidates.size(), "?"));
        String sql = "SELECT 1 FROM domain_rules WHERE enabled=1 AND domain IN (" + placeholders + ") "
                + "UNION ALL SELECT 1 FROM dns_subscription_rules r "
                + "INNER JOIN dns_subscriptions s ON s.feed_id=r.feed_id "
                + "WHERE s.enabled=1 AND r.domain IN (" + placeholders + ") LIMIT 1";
        String[] args = new String[candidates.size() * 2];
        for (int i = 0; i < candidates.size(); i++) {
            args[i] = candidates.get(i);
            args[i + candidates.size()] = candidates.get(i);
        }
        boolean blocked;
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            blocked = c.moveToFirst();
        }
        domainLookupCache.put(domain, new DomainCacheEntry(blocked, now + DOMAIN_CACHE_TTL_MS));
        return blocked;
    }

    public synchronized void log(String packageName, String matchedText, String action, String reason) {
        ContentValues values = new ContentValues();
        values.put("package_name", packageName == null ? "" : packageName);
        values.put("matched_text", matchedText == null ? "" : matchedText);
        values.put("action", action);
        values.put("reason", reason == null ? "" : reason);
        values.put("created_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        long insertedId = db.insert("action_logs", null, values);
        // Avoid a full table sort and delete on every log write.
        if (insertedId > 0 && insertedId % LOG_TRIM_INTERVAL == 0) {
            db.execSQL("DELETE FROM action_logs WHERE id NOT IN (SELECT id FROM action_logs"
                    + " ORDER BY created_at DESC, id DESC LIMIT " + MAX_LOG_ENTRIES + ")");
        }
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
        public final int scanCount;
        public final int noCandidateCount;
        public final int ocrErrorCount;
        Stats24h(int clicks, int blocked, int noAction, int dnsBlocks, int errors,
                 int scans, int noCandidates, int ocrErrors) {
            clickCount = clicks; blockedCount = blocked; noActionCount = noAction;
            dnsBlockCount = dnsBlocks; errorCount = errors;
            scanCount = scans; noCandidateCount = noCandidates; ocrErrorCount = ocrErrors;
        }
        public String toDisplayText() {
            return "近 24 小时：自动点击 " + clickCount + " 次，安全拦截 " + blockedCount
                    + " 次，未执行 " + noActionCount + " 次，DNS 拦截 " + dnsBlockCount
                    + " 次，错误 " + errorCount + " 次。\n"
                    + "诊断：控件扫描 " + scanCount + " 次，无候选 " + noCandidateCount
                    + " 次，OCR 错误 " + ocrErrorCount + " 次。";
        }
    }

    public synchronized Stats24h getStats24h() {
        int clicks = 0, blocked = 0, noAction = 0, dnsBlocks = 0, errors = 0;
        int scans = 0, noCandidates = 0, ocrErrors = 0;
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
                else if ("SCAN".equals(action)) scans = count;
                else if ("NO_CANDIDATE".equals(action)) noCandidates = count;
                else if (action != null && action.startsWith("OCR_")
                        && action.endsWith("ERROR")) ocrErrors += count;
            }
        }
        return new Stats24h(clicks, blocked, noAction, dnsBlocks, errors,
                scans, noCandidates, ocrErrors);
    }

    public synchronized List<String> getRecentLogs(int limit) {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT package_name, matched_text, action, reason, created_at FROM action_logs ORDER BY created_at DESC, id DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, Math.min(limit, 300)))})) {
            while (c.moveToNext()) {
                result.add(formatLogRow(c));
            }
        }
        return result;
    }

    /** Returns every log row currently retained in the local database, newest first. */
    public synchronized List<String> getAllLogs() {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT package_name, matched_text, action, reason, created_at FROM action_logs ORDER BY created_at DESC, id DESC",
                null)) {
            while (c.moveToNext()) {
                result.add(formatLogRow(c));
            }
        }
        return result;
    }

    /** Return only DNS import diagnostics for a shareable local report. */
    public synchronized List<String> getDnsImportLogs() {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT package_name, matched_text, action, reason, created_at FROM action_logs "
                        + "WHERE package_name='DNS_IMPORT' OR action LIKE 'DNS_IMPORT%' "
                        + "ORDER BY created_at DESC, id DESC", null)) {
            while (c.moveToNext()) result.add(formatLogRow(c));
        }
        return result;
    }

    private static String formatLogRow(Cursor c) {
        return android.text.format.DateFormat.format("MM-dd HH:mm:ss", c.getLong(4))
                + "  [" + c.getString(2) + "]  " + c.getString(0)
                + "  " + c.getString(1) + "\n     " + c.getString(3);
    }

    public synchronized void clearLogs() { getWritableDatabase().delete("action_logs", null, null); }

    public static String normalizeDomain(String raw) { return DomainRuleValidator.normalize(raw); }
}
