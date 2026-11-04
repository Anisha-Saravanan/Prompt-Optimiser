package org.promptoptimizer.segment;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.token.HeuristicTokenCounter;

class ParagraphSegmenterTest {
    private final Canonicalizer canon = new Canonicalizer(1_000_000);
    private final ParagraphSegmenter seg = new ParagraphSegmenter();
    private final HeuristicTokenCounter counter = new HeuristicTokenCounter();

    private List<InformationUnit> run(String text) {
        CanonicalDocument d = canon.canonicalize("doc", text);
        List<InformationUnit> units = seg.segment(d, counter);
        Segmenter.verifyPartition(d, units);
        return units;
    }

    @Test
    void splitsOnBlankLinesAndKeepsTrailingBlanksInPreviousUnit() {
        String text = "para one\nline two\n\n\npara two\n\npara three";
        List<InformationUnit> units = run(text);
        assertEquals(3, units.size());
        assertEquals("para one\nline two\n\n\n", text.substring(units.get(0).start(), units.get(0).end()));
        assertEquals("para two\n\n", text.substring(units.get(1).start(), units.get(1).end()));
        assertEquals("para three", text.substring(units.get(2).start(), units.get(2).end()));
    }

    @Test
    void doesNotSplitInsideFencedCodeBlock() {
        String text = "intro\n\n```java\nint a;\n\nint b;\n```\n\nafter";
        List<InformationUnit> units = run(text);
        assertEquals(3, units.size());
        String fence = text.substring(units.get(1).start(), units.get(1).end());
        assertTrue(fence.contains("int a;\n\nint b;"));
    }

    @Test
    void unterminatedFenceExtendsToEnd() {
        String text = "intro\n\n```\ncode\n\nmore code\n\nstill code";
        List<InformationUnit> units = run(text);
        assertEquals(2, units.size());
    }

    @Test
    void emptyAndWhitespaceOnlyDocuments() {
        assertTrue(run("").isEmpty());
        assertEquals(1, run("\n\n  \n").size());
    }

    @Test
    void unitsReportTokensFromCounter() {
        List<InformationUnit> units = run("hello world");
        assertEquals(counter.count("hello world").tokens(), units.get(0).tokens());
    }

    @Test
    void partitionPropertyHoldsOnRandomText() {
        Random rnd = new Random(42);
        String alphabet = "ab \n\n`x";
        for (int i = 0; i < 300; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(80);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            run(sb.toString()); // run() asserts the partition property
        }
    }

    @Test
    void verifyPartitionDetectsGapAndShortCoverage() {
        CanonicalDocument d = canon.canonicalize("doc", "abcdef");
        List<InformationUnit> gap = List.of(
                new InformationUnit("doc", 0, 0, 2, org.promptoptimizer.model.UnitType.UNKNOWN, 1),
                new InformationUnit("doc", 1, 3, 6, org.promptoptimizer.model.UnitType.UNKNOWN, 1));
        assertThrows(IllegalStateException.class, () -> Segmenter.verifyPartition(d, gap));
        List<InformationUnit> shortCover = List.of(
                new InformationUnit("doc", 0, 0, 4, org.promptoptimizer.model.UnitType.UNKNOWN, 1));
        assertThrows(IllegalStateException.class, () -> Segmenter.verifyPartition(d, shortCover));
    }
}
