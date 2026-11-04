package org.promptoptimizer.lossless;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WhitespaceNormalizerTest {
    private final WhitespaceNormalizer n = new WhitespaceNormalizer();

    private String normalize(String text, ContentFormat f) {
        return normalize(text, f, List.of());
    }

    private String normalize(String text, ContentFormat f, List<int[]> extraExcluded) {
        List<int[]> excluded = new ArrayList<>(WhitespaceNormalizer.fencedRanges(text));
        excluded.addAll(extraExcluded);
        WhitespaceNormalizer.Result r = n.compute(text, f, 0, text.length(), excluded);
        String out = r.script().apply(text);
        assertEquals(text, r.script().revert(out), "round trip");
        return out;
    }

    @Test
    void stripsTrailingSpacesAndTabs() {
        assertEquals("a\nb\nc", normalize("a  \nb\t \nc \t", ContentFormat.PLAIN_TEXT));
    }

    @Test
    void collapsesRunsOfBlankLinesToOne() {
        assertEquals("a\n\nb", normalize("a\n\n\n\nb", ContentFormat.PLAIN_TEXT));
        assertEquals("a\n\nb\n\n", normalize("a\n\n\nb\n\n\n\n\n", ContentFormat.PLAIN_TEXT));
    }

    @Test
    void whitespaceOnlyLinesCountAsBlank() {
        assertEquals("a\n\nb", normalize("a\n  \n\t\n \nb", ContentFormat.PLAIN_TEXT));
        assertEquals("a\n\nb", normalize("a\n  \nb", ContentFormat.PLAIN_TEXT));
    }

    @Test
    void singleBlankLineAndCleanTextAreUntouched() {
        String clean = "a\n\nb\nc\n";
        WhitespaceNormalizer.Result r = n.compute(clean, ContentFormat.PLAIN_TEXT, 0, clean.length(), List.of());
        assertTrue(r.script().isEmpty());
        assertTrue(r.skipped().isEmpty());
    }

    @Test
    void leadingBlankLinesCollapseToOne() {
        assertEquals("\n\nabc", normalize("\n\n\n\nabc", ContentFormat.PLAIN_TEXT));
    }

    @Test
    void trailingWhitespaceAtEndOfDocumentWithoutNewlineIsStripped() {
        assertEquals("a\n\n", normalize("a\n\n\n   ", ContentFormat.PLAIN_TEXT));
    }

    @Test
    void fencedBlockIsNeverEdited() {
        String text = "intro  \n\n```java\nint a;   \n\n\n\nint b;\n```\n\n\n\nafter  ";
        assertEquals("intro\n\n```java\nint a;   \n\n\n\nint b;\n```\n\nafter", normalize(text, ContentFormat.PLAIN_TEXT));
    }

    @Test
    void tildeFenceAndUnterminatedFenceAreProtected() {
        String tilde = "x\n\n~~~\na   \n\n\n\nb\n~~~\n\ny";
        assertEquals(tilde, normalize(tilde, ContentFormat.MARKDOWN));
        String open = "x\n\n```\na   \n\n\n\nb";
        assertEquals(open, normalize(open, ContentFormat.PLAIN_TEXT));
    }

    @Test
    void editsOverlappingAnExcludedRangeAreSkippedAndReported() {
        String text = "must keep   \n\n\n\nnext";
        // exclude the trailing spaces (positions 9..12) and the blank run (12..16)
        WhitespaceNormalizer.Result r = n.compute(text, ContentFormat.PLAIN_TEXT, 0, text.length(),
                List.of(new int[] {9, 12}, new int[] {13, 15}));
        assertTrue(r.script().isEmpty());
        assertEquals(2, r.skipped().size());
    }

    @Test
    void markdownKeepsTwoSpaceHardLineBreakButPlainTextStripsIt() {
        String text = "line one  \nline two\n";
        assertEquals(text, normalize(text, ContentFormat.MARKDOWN));
        assertEquals("line one\nline two\n", normalize(text, ContentFormat.PLAIN_TEXT));
    }

    @Test
    void markdownStillStripsSingleTrailingSpaceAndTabs() {
        assertEquals("a\nb\n", normalize("a \nb\t\n", ContentFormat.MARKDOWN));
    }

    @Test
    void blankRunNextToIndentedTextIsLeftAlone() {
        String text = "para\n\n\n\n    indented code\n";
        WhitespaceNormalizer.Result r = n.compute(text, ContentFormat.PLAIN_TEXT, 0, text.length(), List.of());
        assertTrue(r.script().isEmpty());
        assertEquals(1, r.skipped().size());
        String before = "    indented\n\n\n\nafter\n";
        assertEquals(before, normalize(before, ContentFormat.PLAIN_TEXT));
        String tab = "a\n\n\n\n\tb\n";
        assertEquals(tab, normalize(tab, ContentFormat.PLAIN_TEXT));
    }

    @Test
    void rejectsNonProseFormats() {
        for (ContentFormat f : new ContentFormat[] {ContentFormat.SOURCE_CODE, ContentFormat.LOG, ContentFormat.STRUCTURED_DATA}) {
            assertThrows(IllegalArgumentException.class, () -> n.compute("a  \n", f, 0, 4, List.of()));
        }
    }

    @Test
    void scriptOffsetsAreRelativeToTheUnit() {
        String text = "first\n\nsecond   \n\n\n\nthird";
        int unitStart = text.indexOf("second");
        int unitEnd = text.indexOf("third");
        WhitespaceNormalizer.Result r = n.compute(text, ContentFormat.PLAIN_TEXT, unitStart, unitEnd, List.of());
        String unit = text.substring(unitStart, unitEnd);
        assertEquals("second\n\n", r.script().apply(unit));
    }

    @Test
    void doesNotEditOutsideTheUnitRegion() {
        String text = "aaa   \n\nbbb   \n\nccc   ";
        int s = text.indexOf("bbb");
        int e = text.indexOf("ccc");
        WhitespaceNormalizer.Result r = n.compute(text, ContentFormat.PLAIN_TEXT, s, e, List.of());
        for (TextEdit edit : r.script().edits()) {
            assertTrue(edit.start() >= 0 && edit.end() <= e - s);
        }
        assertEquals("bbb\n\n", r.script().apply(text.substring(s, e)));
    }

    @Test
    void fencedRangesCoverOpeningToClosingLine() {
        String text = "a\n```\nx\n```\nb";
        List<int[]> ranges = WhitespaceNormalizer.fencedRanges(text);
        assertEquals(1, ranges.size());
        assertEquals("```\nx\n```\n", text.substring(ranges.get(0)[0], ranges.get(0)[1]));
    }
}
