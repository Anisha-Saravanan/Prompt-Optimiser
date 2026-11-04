package org.promptoptimizer.lossless;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EditScriptTest {
    private static final String SRC = "ab  \ncd\n\n\n\nef";

    private static EditScript script() {
        return new EditScript(SRC.length(), List.of(
                new TextEdit(2, 4, "  ", "", EditKind.TRAILING_WHITESPACE),
                new TextEdit(7, 11, "\n\n\n\n", "\n\n", EditKind.BLANK_LINE_RUN)));
    }

    @Test
    void applyThenRevertRestoresSourceExactly() {
        EditScript s = script();
        String out = s.apply(SRC);
        assertEquals("ab\ncd\n\nef", out);
        assertEquals(out.length(), s.transformedLength());
        assertEquals(SRC, s.revert(out));
    }

    @Test
    void emptyScriptIsIdentity() {
        EditScript s = EditScript.empty(3);
        assertTrue(s.isEmpty());
        assertEquals("abc", s.apply("abc"));
        assertEquals("abc", s.revert("abc"));
    }

    @Test
    void applyRejectsSourceItWasNotBuiltFor() {
        assertThrows(IllegalArgumentException.class, () -> script().apply("short"));
        assertThrows(IllegalArgumentException.class, () -> script().apply(SRC.replace("  \n", "xx\n")));
    }

    @Test
    void revertRejectsTransformedTextThatDoesNotMatch() {
        assertThrows(IllegalArgumentException.class, () -> script().revert("ab\ncd\n\n"));
        assertThrows(IllegalArgumentException.class, () -> script().revert("ab\ncdXXef"));
    }

    @Test
    void offsetsMapAcrossEdits() {
        EditScript s = script();
        assertEquals(0, s.toTransformed(0));
        assertEquals(2, s.toTransformed(2));   // start of the first edit
        assertEquals(2, s.toTransformed(4));   // end of the first edit
        assertEquals(5, s.toTransformed(7));
        assertEquals(7, s.toTransformed(11));
        assertEquals(9, s.toTransformed(SRC.length()));
        assertThrows(IllegalArgumentException.class, () -> s.toTransformed(3));
        assertThrows(IllegalArgumentException.class, () -> s.toTransformed(9));
        assertThrows(IllegalArgumentException.class, () -> s.toTransformed(-1));
    }

    @Test
    void originRangeSeparatesCopiedAndReplacedCharacters() {
        EditScript s = script();
        String out = s.apply(SRC);
        assertEquals(new EditScript.Range(0, 1), s.originRange(0));
        assertEquals(new EditScript.Range(4, 5), s.originRange(2)); // the "\n" after the removed spaces
        assertEquals(new EditScript.Range(7, 11), s.originRange(5)); // replacement characters
        assertEquals(new EditScript.Range(7, 11), s.originRange(6));
        assertEquals(new EditScript.Range(11, 12), s.originRange(7));
        assertThrows(IllegalArgumentException.class, () -> s.originRange(out.length()));
        for (int i = 0; i < out.length(); i++) {
            EditScript.Range r = s.originRange(i);
            if (r.end() - r.start() == 1) {
                assertEquals(out.charAt(i), SRC.charAt(r.start()));
            }
        }
    }

    @Test
    void constructorRejectsInvalidScripts() {
        TextEdit a = new TextEdit(0, 2, "ab", "", EditKind.TRAILING_WHITESPACE);
        TextEdit overlapping = new TextEdit(1, 3, "bc", "", EditKind.TRAILING_WHITESPACE);
        assertThrows(IllegalArgumentException.class, () -> new EditScript(10, List.of(a, overlapping)));
        assertThrows(IllegalArgumentException.class, () -> new EditScript(10, List.of(overlapping, a)));
        assertThrows(IllegalArgumentException.class, () -> new EditScript(1, List.of(a)));
        assertThrows(IllegalArgumentException.class, () -> new EditScript(-1, List.of()));
    }

    @Test
    void editRejectsMalformedRanges() {
        assertThrows(IllegalArgumentException.class, () -> new TextEdit(3, 3, "", "x", EditKind.BLANK_LINE_RUN));
        assertThrows(IllegalArgumentException.class, () -> new TextEdit(0, 2, "a", "", EditKind.BLANK_LINE_RUN));
        assertThrows(IllegalArgumentException.class, () -> new TextEdit(0, 1, "a", "a", EditKind.BLANK_LINE_RUN));
    }

    @Test
    void kindsAreDistinctInOrderOfAppearance() {
        assertEquals(List.of(EditKind.TRAILING_WHITESPACE, EditKind.BLANK_LINE_RUN), script().kinds());
    }
}
