package com.aeginning.adskip.engine;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SkipWordLibraryTest {
    @Test public void parsesAndNormalizesCustomWordsInStableOrder() {
        List<String> words = SkipWordLibrary.parse("稍后跳过\n稍后跳过\nＳｋｉｐ Ｎｏｗ\n结束推广");
        assertEquals(Arrays.asList("稍后跳过", "skip now", "结束推广"), words);
    }

    @Test public void rejectsDangerousNegativeAndBuiltInDuplicates() {
        List<String> words = SkipWordLibrary.parse(
                "购买\n立即下载\n不可跳过\nDon't skip\n跳过广告\nSkip Ad\n关闭推广\n稍后跳过");
        assertEquals(Collections.singletonList("稍后跳过"), words);
    }

    @Test public void enforcesEntryLengthAndCountLimits() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 105; i++) raw.append("自定义词").append(i).append('\n');
        raw.append("x\n").append("超长".repeat(30));
        List<String> words = SkipWordLibrary.parse(raw.toString());
        assertEquals(SkipWordLibrary.MAX_CUSTOM_WORDS, words.size());
        assertTrue(words.stream().noneMatch(w -> w.length() > SkipWordLibrary.MAX_WORD_LENGTH));
    }

    @Test public void matchesOnlyUserSuppliedEntries() {
        List<String> words = SkipWordLibrary.parse("稍后跳过\n结束推广");
        assertTrue(SkipWordLibrary.matches("立即稍后跳过广告", words));
        assertTrue(SkipWordLibrary.matches("结束推广", words));
        assertFalse(SkipWordLibrary.matches("跳过广告", words));
        assertFalse(SkipWordLibrary.matches("广告稍后", words));
    }

    @Test public void mergeKeepsExistingEntriesAndDeduplicatesAdditions() {
        List<String> merged = SkipWordLibrary.merge(
                Arrays.asList("稍后跳过", "结束推广"), "结束推广\n马上略过");
        assertEquals(Arrays.asList("稍后跳过", "结束推广", "马上略过"), merged);
        assertEquals("稍后跳过\n结束推广\n马上略过", SkipWordLibrary.serialize(merged));
    }
}
