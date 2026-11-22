package com.aeginning.adskip.engine;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class CustomSkipWordPolicyTest {
    @Test public void normalizesAndDeduplicatesStoredWords() {
        Set<String> words = CustomSkipWordPolicy.parse("  Skip Later  \nskip later\n跳过此片头");
        assertEquals(2, words.size());
        assertTrue(words.contains("skip later"));
        assertTrue(words.contains("跳过此片头"));
    }

    @Test public void rejectsEmptyGenericDangerousAndNegativeWords() {
        assertNull(CustomSkipWordPolicy.normalizeAndValidate(""));
        assertNull(CustomSkipWordPolicy.normalizeAndValidate("skip"));
        assertNull(CustomSkipWordPolicy.normalizeAndValidate("跳过"));
        assertNull(CustomSkipWordPolicy.normalizeAndValidate("立即购买"));
        assertNull(CustomSkipWordPolicy.normalizeAndValidate("不要跳过"));
        assertNull(CustomSkipWordPolicy.normalizeAndValidate("允许访问"));
        assertNull(CustomSkipWordPolicy.normalizeAndValidate("too\nlong"));
    }

    @Test public void enforcesWordLengthAndCountLimits() {
        assertNull(CustomSkipWordPolicy.normalizeAndValidate("abcdefghijklmnopqrstuvwxyz123456789012345"));
        StringBuilder stored = new StringBuilder();
        for (int i = 0; i < CustomSkipWordPolicy.MAX_WORDS + 20; i++) {
            if (stored.length() > 0) stored.append('\n');
            stored.append("custom").append(i);
        }
        assertEquals(CustomSkipWordPolicy.MAX_WORDS,
                CustomSkipWordPolicy.parse(stored.toString()).size());
    }

    @Test public void customWordsOnlyMatchCandidateLabelsWithoutOverridingSafetyPhrases() {
        Set<String> words = new LinkedHashSet<>(Arrays.asList("跳过片头"));
        assertTrue(CustomSkipWordPolicy.matches("现在跳过片头", words));
        assertFalse(CustomSkipWordPolicy.matches("立即购买后跳过片头", words));
        assertFalse(CustomSkipWordPolicy.matches("不可跳过片头", words));
    }

    @Test public void serializationRoundTripsValidatedEntries() {
        Set<String> words = new LinkedHashSet<>(Arrays.asList("Skip Opening", "跳过片头", "购买"));
        String saved = CustomSkipWordPolicy.serialize(words);
        assertEquals(2, CustomSkipWordPolicy.parse(saved).size());
        assertTrue(CustomSkipWordPolicy.parse(saved).contains("skip opening"));
    }
}
