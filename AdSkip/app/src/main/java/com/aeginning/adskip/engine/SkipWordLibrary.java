package com.aeginning.adskip.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Normalizes and bounds user-defined positive skip labels.
 * Custom entries are advisory phrases only; the scorer still requires ad context
 * and the final SafetyPolicy remains mandatory.
 */
public final class SkipWordLibrary {
    public static final int MAX_CUSTOM_WORDS = 100;
    public static final int MIN_WORD_LENGTH = 2;
    public static final int MAX_WORD_LENGTH = 48;

    private SkipWordLibrary() { }

    /** Parse newline/semicolon separated entries, dropping invalid and duplicate values. */
    public static List<String> parse(String raw) {
        Set<String> unique = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String[] entries = raw.split("[\\r\\n;；]+");
        for (String entry : entries) {
            String word = TextMatcher.normalize(entry);
            if (word.length() < MIN_WORD_LENGTH || word.length() > MAX_WORD_LENGTH) continue;
            if (!word.matches(".*[\\p{L}\\p{N}].*")) continue;
            if (TextMatcher.isDangerous(word) || TextMatcher.isNegativeSkip(word)
                    || TextMatcher.isDefaultSkipTerm(word)) continue;
            unique.add(word);
            if (unique.size() >= MAX_CUSTOM_WORDS) break;
        }
        return new ArrayList<>(unique);
    }

    /** Preserve insertion order and the hard 100-entry limit. */
    public static List<String> merge(List<String> existing, String additions) {
        StringBuilder combined = new StringBuilder();
        if (existing != null) {
            for (String item : existing) {
                if (item != null && !item.trim().isEmpty()) combined.append(item).append('\n');
            }
        }
        if (additions != null) combined.append(additions);
        return parse(combined.toString());
    }

    public static String serialize(List<String> words) {
        List<String> safe = parse(join(words));
        return String.join("\n", safe);
    }

    public static boolean matches(String candidate, List<String> words) {
        String normalizedCandidate = TextMatcher.normalize(candidate);
        if (normalizedCandidate.isEmpty() || words == null || words.isEmpty()) return false;
        for (String rawWord : words) {
            String word = TextMatcher.normalize(rawWord);
            if (word.length() >= MIN_WORD_LENGTH && word.length() <= MAX_WORD_LENGTH
                    && normalizedCandidate.contains(word)) return true;
        }
        return false;
    }

    private static String join(List<String> words) {
        if (words == null || words.isEmpty()) return "";
        StringBuilder value = new StringBuilder();
        for (String word : words) {
            if (word != null) value.append(word).append('\n');
        }
        return value.toString();
    }
}
