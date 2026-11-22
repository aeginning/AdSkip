package com.aeginning.adskip.engine;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Local, additive custom skip words. A custom entry can only make a candidate
 * recognisable; the normal ad-context, dangerous-action and final safety gates
 * still decide whether an action may run.
 */
public final class CustomSkipWordPolicy {
    public static final int MAX_WORDS = 100;
    public static final int MAX_WORD_LENGTH = 40;

    private CustomSkipWordPolicy() { }

    /** Parse newline-separated preferences into a bounded, immutable, normalized set. */
    public static Set<String> parse(String stored) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        if (stored == null || stored.isEmpty()) return Collections.unmodifiableSet(words);
        String[] lines = stored.split("\\R");
        for (String line : lines) {
            String normalized = normalizeAndValidate(line);
            if (normalized == null) continue;
            words.add(normalized);
            if (words.size() >= MAX_WORDS) break;
        }
        return Collections.unmodifiableSet(words);
    }

    /**
     * Returns a normalized word, or null when it is empty, too long, generic,
     * duplicated by a safety-sensitive action phrase, or contains line breaks.
     */
    public static String normalizeAndValidate(String input) {
        if (input == null || input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0
                || input.indexOf('\t') >= 0) return null;
        String word = TextMatcher.normalize(input);
        if (word.isEmpty()) return null;
        int length = word.codePointCount(0, word.length());
        if (length < 2 || length > MAX_WORD_LENGTH) return null;
        boolean hasLetterOrDigit = false;
        for (int offset = 0; offset < word.length();) {
            int codePoint = word.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                hasLetterOrDigit = true;
                break;
            }
            offset += Character.charCount(codePoint);
        }
        if (!hasLetterOrDigit) return null;
        if ("skip".equals(word) || "跳过".equals(word)
                || "close".equals(word) || "关闭".equals(word)) return null;
        if (TextMatcher.isDangerous(word) || TextMatcher.isNegativeSkip(word)
                || TextMatcher.hasCriticalActionContext(word)) return null;
        return word;
    }

    /** Serialize a validated collection for SharedPreferences. */
    public static String serialize(Collection<String> words) {
        StringBuilder output = new StringBuilder();
        if (words == null) return "";
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String candidate : words) {
            String word = normalizeAndValidate(candidate);
            if (word == null) continue;
            normalized.add(word);
            if (normalized.size() >= MAX_WORDS) break;
        }
        for (String word : normalized) {
            if (output.length() > 0) output.append('\n');
            output.append(word);
        }
        return output.toString();
    }

    /** Exact phrase containment is only considered by the scorer when ad context exists. */
    public static boolean matches(String candidate, Set<String> customWords) {
        if (candidate == null || customWords == null || customWords.isEmpty()) return false;
        String value = TextMatcher.normalize(candidate);
        if (value.isEmpty() || TextMatcher.isDangerous(value)
                || TextMatcher.isNegativeSkip(value)) return false;
        for (String word : customWords) {
            String normalized = normalizeAndValidate(word);
            if (normalized != null && value.contains(normalized)) return true;
        }
        return false;
    }
}
