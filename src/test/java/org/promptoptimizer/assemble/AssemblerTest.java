package org.promptoptimizer.assemble;

import static org.junit.jupiter.api.Assertions.*;
import static org.promptoptimizer.lossless.LosslessTestSupport.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.lossless.ContentFormat;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.UnitState;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.token.TokenCount;
import org.promptoptimizer.token.TokenCounter;

class AssemblerTest {
    private final Assembler assembler = new Assembler(COUNTER);

    private AssembledInput assemble(PreparedDocument... docs) {
        return assembler.assemble(run(docs));
    }

    private static PreparedDocument file(String id, String text) {
        return prepare(id, text, DocRole.FILE, ContentFormat.PLAIN_TEXT);
    }

    @Test
    void promptAloneIsEmittedWithoutScaffolding() {
        String text = "First paragraph.\n\nSecond paragraph.\n";
        AssembledInput in = assemble(prompt(text));
        assertEquals(text, in.text());
        assertEquals(0, in.scaffoldTokens());
        assertEquals(in.tokensBefore(), in.tokens());
        assertTrue(in.segments().stream().allMatch(s -> s.kind() == SegmentKind.UNIT));
        assertEquals(2, in.segments().size());
        assertEquals(2, in.records().size());
    }

    @Test
    void filesFollowThePromptWithDelimiterAndBlankLine() {
        AssembledInput in = assemble(prompt("Please review.\n"), file("notes.txt", "line one\nline two\n"));
        assertEquals("Please review.\n\n=== FILE: notes.txt (2 lines) ===\nline one\nline two\n", in.text());
        assertTrue(in.scaffoldTokens() > 0);
        assertTrue(in.tokens() > in.tokensBefore(), "scaffolding must show up as a net cost");
        assertTrue(in.netTokenChange() > 0);
    }

    @Test
    void promptIsFirstEvenWhenListedAfterFilesAndFilesKeepGivenOrder() {
        AssembledInput in = assemble(file("b.txt", "bbb\n"), prompt("ask\n"), file("a.txt", "aaa\n"));
        String t = in.text();
        assertTrue(t.startsWith("ask\n"));
        assertTrue(t.indexOf("=== FILE: b.txt") < t.indexOf("=== FILE: a.txt"));
    }

    @Test
    void fileWithoutTrailingNewlineStillGetsItsNextDelimiterOnItsOwnLine() {
        AssembledInput in = assemble(file("a.txt", "no newline"), file("b.txt", "second\n"));
        assertEquals("=== FILE: a.txt (1 line) ===\nno newline\n\n=== FILE: b.txt (1 line) ===\nsecond\n", in.text());
    }

    @Test
    void emptyFileAndEmptyPromptAreHandled() {
        AssembledInput in = assemble(prompt(""), file("empty.txt", ""), file("x.txt", "x\n"));
        assertEquals("=== FILE: empty.txt (0 lines) ===\n\n=== FILE: x.txt (1 line) ===\nx\n", in.text());
        in.verifyProvenance();
    }

    @Test
    void removedDuplicateBecomesAVisibleMarkerAndIsCountedInTheHeader() {
        String text = DUP + "\n\nKeep me.\n\n" + DUP + "\n";
        AssembledInput in = assemble(prompt(text));
        assertTrue(in.text().contains("[omitted: exact repeat of block 1 of prompt]"));
        assertEquals(1, in.segments().stream().filter(s -> s.kind() == SegmentKind.OMISSION_MARKER).count());
        assertTrue(in.tokens() < in.tokensBefore());
        assertTrue(in.netTokenChange() < 0);

        AssembledInput withFile = assemble(prompt("Intro.\n\n" + DUP + "\n"), file("dup.txt", "Header\n\n" + DUP + "\n"));
        assertTrue(withFile.text().contains("=== FILE: dup.txt (3 lines; 1 repeated block omitted) ==="));
        assertTrue(withFile.text().contains("[omitted: exact repeat of block 2 of prompt]"));
    }

    @Test
    void provenanceMapsEveryNonScaffoldCharacterBackToTheOriginal() {
        String promptText = "Intro   \n\n\n\n" + DUP + "\n\nTail text.\n";
        String fileText = "alpha   \nbeta\n\n\n\n" + DUP + "\n";
        PreparedDocument p = prompt(promptText);
        PreparedDocument f = file("f.txt", fileText);
        AssembledInput in = assemble(p, f);
        in.verifyProvenance();
        int scaffold = 0;
        for (int i = 0; i < in.text().length(); i++) {
            Optional<Origin> o = in.origin(i);
            if (o.isEmpty()) {
                scaffold++;
                continue;
            }
            Origin origin = o.get();
            String orig = origin.docId().equals("prompt") ? promptText : fileText;
            assertTrue(origin.start() >= 0 && origin.end() <= orig.length() && origin.start() < origin.end(), "range at " + i);
            if (origin.kind() == SegmentKind.UNIT && origin.end() - origin.start() == 1) {
                assertEquals(orig.charAt(origin.start()), in.text().charAt(i), "char at " + i);
            }
        }
        assertTrue(scaffold > 0);
        // scaffold characters are exactly those of the segments of kind SCAFFOLD
        int expected = in.segments().stream().filter(s -> s.kind() == SegmentKind.SCAFFOLD).mapToInt(s -> s.outEnd() - s.outStart()).sum();
        assertEquals(expected, scaffold);
    }

    @Test
    void markerOriginIsTheWholeRemovedUnit() {
        String text = DUP + "\n\nmid text here\n\n" + DUP + "\n";
        AssembledInput in = assemble(prompt(text));
        int at = in.text().indexOf("[omitted:");
        Origin o = in.origin(at).orElseThrow();
        assertEquals(SegmentKind.OMISSION_MARKER, o.kind());
        assertEquals(text.lastIndexOf(DUP), o.start());
        assertEquals(text.length(), o.end());
    }

    @Test
    void unitTextInsideTheOutputEqualsTheLosslessText() {
        PreparedDocument p = prompt("Intro   \n\n\n\nBody line  \n");
        LosslessResult r = run(p);
        AssembledInput in = assembler.assemble(r);
        StringBuilder rebuilt = new StringBuilder();
        for (ProvenanceSegment s : in.segments()) {
            rebuilt.append(in.text(), s.outStart(), s.outEnd());
        }
        assertEquals(in.text(), rebuilt.toString());
        String l1 = r.states("prompt").stream().map(UnitState::l1).reduce("", String::concat);
        assertEquals(l1, in.text());
    }

    @Test
    void codeFilesAreEmittedVerbatimAndFencesSurvive() {
        String java = "class A {\n\n\n\n    int x;   \n}\n";
        PreparedDocument code = prepare("A.java", java, DocRole.FILE, ContentFormat.SOURCE_CODE);
        String prose = "See:\n\n```java\nint a;  \n\n\n\nint b;\n```\n";
        AssembledInput in = assemble(prompt(prose), code);
        assertTrue(in.text().contains(prose));
        assertTrue(in.text().endsWith("=== FILE: A.java (6 lines) ===\n" + java));
    }

    @Test
    void delimiterAndMarkerCollisionsAreRejected() {
        assertThrows(AssemblyException.class, () -> assemble(prompt("ok\n\n=== FILE: fake ===\nx\n")));
        assertThrows(AssemblyException.class, () -> assemble(prompt("ok"), file("a.txt", "[omitted: exact repeat of block 1 of prompt]\n")));
        // the same text in the middle of a line is not a collision
        assertEquals("see === FILE: x and [omitted: y]\n", assemble(prompt("see === FILE: x and [omitted: y]\n")).text());
    }

    @Test
    void rejectsMoreThanOnePromptAndMismatchedCounters() {
        PreparedDocument a = prepare("p1", "a\n", DocRole.PROMPT, ContentFormat.PLAIN_TEXT);
        PreparedDocument b = prepare("p2", "b\n", DocRole.PROMPT, ContentFormat.PLAIN_TEXT);
        assertThrows(IllegalArgumentException.class, () -> assemble(a, b));
        TokenCounter other = new TokenCounter() {
            @Override public TokenCount count(String t) { return new TokenCount(t.length(), false, "other"); }
            @Override public String id() { return "other"; }
        };
        assertThrows(IllegalArgumentException.class, () -> new Assembler(other).assemble(run(prompt("x"))));
        assertThrows(IllegalArgumentException.class, () -> new Assembler(null));
    }

    @Test
    void verifyProvenanceDetectsGapsAndWrongLengths() {
        AssembledInput good = assemble(prompt("abc\n\ndef\n"));
        List<ProvenanceSegment> gap = new ArrayList<>(good.segments());
        gap.remove(0);
        AssembledInput bad = new AssembledInput(good.text(), good.tokens(), 0, good.tokensBefore(), good.tokenizerId(), gap, good.records());
        assertThrows(IllegalStateException.class, bad::verifyProvenance);
        AssembledInput shortText = new AssembledInput(good.text() + "extra", good.tokens(), 0, good.tokensBefore(), good.tokenizerId(), good.segments(), good.records());
        assertThrows(IllegalStateException.class, shortText::verifyProvenance);
        assertThrows(IllegalArgumentException.class, () -> good.origin(good.text().length()));
    }

    @Test
    void segmentRejectsInconsistentConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceSegment(0, 3, SegmentKind.SCAFFOLD, "x", -1, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceSegment(0, 3, SegmentKind.UNIT, "x", 0, 3, null));
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceSegment(0, 0, SegmentKind.SCAFFOLD, null, -1, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceSegment(0, 3, SegmentKind.OMISSION_MARKER, null, 0, 3, null));
    }

    @Test
    void assemblyIsDeterministic() {
        String text = "A   \n\n\n\n" + DUP + "\n\nB\n\n" + DUP + "\n";
        AssembledInput one = assemble(prompt(text), file("f.txt", DUP + "\n"));
        AssembledInput two = assemble(prompt(text), file("f.txt", DUP + "\n"));
        assertEquals(one.text(), two.text());
        assertEquals(one.tokens(), two.tokens());
        assertEquals(one.records(), two.records());
        assertEquals(one.segments().size(), two.segments().size());
    }

    @Test
    void endToEndKeepsEveryHardSpanOfEveryEmittedUnit() {
        String promptText = "TASK: Find the bugs in Student.java and suggest minimal fixes.   \n\n"
                + "CONSTRAINTS:\n- Do not rewrite working modules.\n- Keep the public API of GradeBook.compute() unchanged.\n\n\n\n"
                + "The build fails with java.lang.NullPointerException at Student.java:42 under Java 17.\n\n"
                + DUP + "\n\n" + DUP + "   \n";
        String readme = "# Notes\n\n" + DUP + "\n\nRun mvn test --fail-at-end before pushing.\n";
        String java = "public class Student {\n\n\n\n    String name;   \n}\n";
        PreparedDocument p = prompt(promptText);
        PreparedDocument r = prepare("README.md", readme, DocRole.FILE, ContentFormat.MARKDOWN);
        PreparedDocument j = prepare("Student.java", java, DocRole.FILE, ContentFormat.SOURCE_CODE);
        LosslessResult res = new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(List.of(p, r, j));
        AssembledInput in = assembler.assemble(res);
        in.verifyProvenance();

        // every document is exactly recoverable
        assertEquals(promptText, res.reconstruct("prompt"));
        assertEquals(readme, res.reconstruct("README.md"));
        assertEquals(java, res.reconstruct("Student.java"));

        // every HARD span of every emitted (non-merged) unit appears in the output, in order
        int cursor = 0;
        for (PreparedDocument pd : List.of(p, r, j)) {
            List<UnitState> states = res.states(pd.doc().id());
            for (ProtectedSpan s : pd.hardSpans()) {
                boolean merged = states.stream().anyMatch(st -> st.merged() && s.start() >= st.unit().start() && s.end() <= st.unit().end());
                if (merged) {
                    continue;
                }
                String t = pd.doc().text().substring(s.start(), s.end());
                int at = in.text().indexOf(t, cursor);
                assertTrue(at >= 0, "missing HARD span text: " + t);
                cursor = at;
            }
        }
        assertTrue(res.records().stream().anyMatch(x -> x.action() == Action.MERGED));
        assertTrue(in.text().contains("Do not rewrite working modules."));
        assertTrue(in.text().contains("java.lang.NullPointerException at Student.java:42"));
        assertTrue(in.text().contains("=== FILE: Student.java (6 lines) ===\n" + java));
    }
}
