package org.promptoptimizer.protect;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.model.SpanKind;

class MaskerTest {
    private final Canonicalizer canon = new Canonicalizer(1_000_000);
    private final Masker masker = new Masker();
    private final RuleBasedProtectedSpanDetector detector = new RuleBasedProtectedSpanDetector();

    @Test
    void maskThenUnmaskRestoresExactOriginal() {
        CanonicalDocument d = canon.canonicalize("d",
                "Do not change StudentService.\nLatency must stay under 250 ms.\nHello friend.\n[ERROR] Foo.java:[1,2] cannot find symbol\n");
        List<ProtectedSpan> spans = detector.detect(d);
        Masker.Masked m = masker.mask(d, spans);
        assertNotEquals(d.text(), m.text());
        assertFalse(m.text().contains("StudentService"));
        assertFalse(m.text().contains("250"));
        assertTrue(m.text().contains("Hello friend."));
        assertEquals(d.text(), masker.unmask(m.text(), m.originals()));
    }

    @Test
    void operatorEditingBetweenPlaceholdersStillUnmasksProtectedContentIntact() {
        CanonicalDocument d = canon.canonicalize("d", "Never exceed 10 MB. Some chatter here that can go.");
        List<ProtectedSpan> spans = detector.detect(d);
        Masker.Masked m = masker.mask(d, spans);
        String edited = m.text().replace("Some chatter here that can go.", "");
        String restored = masker.unmask(edited, m.originals());
        assertTrue(restored.contains("Never exceed 10 MB."));
        assertFalse(restored.contains("chatter"));
    }

    @Test
    void unmaskRejectsMissingDuplicatedReorderedOrAlteredPlaceholders() {
        CanonicalDocument d = canon.canonicalize("d", "Never use A_B. Never use C_D. end");
        Masker.Masked m = masker.mask(d, detector.detect(d));
        assertTrue(m.originals().size() >= 2);
        String p0 = Masker.PREFIX + 0 + Masker.SUFFIX;
        String p1 = Masker.PREFIX + 1 + Masker.SUFFIX;
        assertTrue(m.text().contains(p0) && m.text().contains(p1));

        assertThrows(Masker.PlaceholderIntegrityException.class,
                () -> masker.unmask(m.text().replace(p1, ""), m.originals()));
        assertThrows(Masker.PlaceholderIntegrityException.class,
                () -> masker.unmask(m.text() + p0, m.originals()));
        assertThrows(Masker.PlaceholderIntegrityException.class,
                () -> masker.unmask(m.text().replace(p0, "@@").replace(p1, p0).replace("@@", p1), m.originals()));
        assertThrows(Masker.PlaceholderIntegrityException.class,
                () -> masker.unmask(m.text().replace(p0, Masker.PREFIX + "x" + Masker.SUFFIX), m.originals()));
        assertThrows(Masker.PlaceholderIntegrityException.class,
                () -> masker.unmask(m.text().replace(Masker.SUFFIX, ""), m.originals()));
    }

    @Test
    void maskRejectsDocumentsThatAlreadyContainTheMarker() {
        CanonicalDocument d = canon.canonicalize("d", "text " + Masker.PREFIX + "0" + Masker.SUFFIX);
        assertThrows(IllegalArgumentException.class, () -> masker.mask(d, List.of()));
    }

    @Test
    void maskRejectsOverlappingOrOutOfRangeSpans() {
        CanonicalDocument d = canon.canonicalize("d", "abcdefghij");
        List<ProtectedSpan> overlapping = List.of(
                new ProtectedSpan("d", 0, 5, SpanKind.IDENTIFIER, Level.HARD, "t"),
                new ProtectedSpan("d", 3, 8, SpanKind.IDENTIFIER, Level.HARD, "t"));
        assertThrows(IllegalArgumentException.class, () -> masker.mask(d, overlapping));
        List<ProtectedSpan> outOfRange = List.of(new ProtectedSpan("d", 5, 50, SpanKind.IDENTIFIER, Level.HARD, "t"));
        assertThrows(IllegalArgumentException.class, () -> masker.mask(d, outOfRange));
    }

    @Test
    void softSpansAreNotMasked() {
        CanonicalDocument d = canon.canonicalize("d", "abcdefghij");
        Masker.Masked m = masker.mask(d, List.of(new ProtectedSpan("d", 2, 4, SpanKind.IDENTIFIER, Level.SOFT, "t")));
        assertEquals("abcdefghij", m.text());
        assertTrue(m.originals().isEmpty());
    }
}
