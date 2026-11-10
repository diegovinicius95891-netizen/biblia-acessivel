package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

/** Referência dos versículos selecionados em massa, como "Provérbios 5:1-2, 6-7". */
public final class VerseRangesTest {
    @Test public void joinsConsecutiveVersesIntoRanges() {
        assertEquals("1-2", MainActivity.verseRanges(Arrays.asList("1", "2")));
        assertEquals("1, 6-7", MainActivity.verseRanges(Arrays.asList("1", "6", "7")));
        assertEquals("1-3, 5, 9-10", MainActivity.verseRanges(Arrays.asList("1", "2", "3", "5", "9", "10")));
        assertEquals("4", MainActivity.verseRanges(Arrays.asList("4")));
    }
}
