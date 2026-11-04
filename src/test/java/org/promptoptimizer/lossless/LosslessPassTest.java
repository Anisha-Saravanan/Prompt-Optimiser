package org.promptoptimizer.lossless;

import static org.junit.jupiter.api.Assertions.*;
import static org.promptoptimizer.lossless.LosslessTestSupport.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.model.SpanKind;
import org.promptoptimizer.model.UnitType;
import org.promptoptimizer.token.TokenCount;
import org.promptoptimizer.token.TokenCounter;

class LosslessPassTest {

    private static List<Action> actions(LosslessResult r, String docId) {
        return r.states(docId).stream().map(UnitState::action).toList();
    }

    @Test
    void cleanDocumentIsKeptUnchanged() {
        String text = "First paragraph.\n\nSecond paragraph.\n";
        LosslessResult r = run(prompt(text));
        assertEquals(List.of(Action.KEPT, Action.KEPT), actions(r, "prompt"));
        assertEquals(text, r.reconstruct("prompt"));
        assertEquals(r.tokensBefore(), r.tokensAfter());
        for (TransformationRecord rec : r.records()) {
            assertEquals("UNCHANGED", rec.reasonCode());
            assertEquals(rec.hashBefore(), rec.hashAfter());
        }
    }

    @Test
    void whitespaceIsNormalizedAndOriginalIsRecoverable() {
        String text = "Intro line   \nsecond line\n\n\n\nNext paragraph\t\n";
        LosslessResult r = run(prompt(text));
        List<UnitState> st = r.states("prompt");
        assertEquals(Action.TRANSFORMED, st.get(0).action());
        assertEquals("Intro line\nsecond line\n\n", st.get(0).l1());
        assertEquals("Next paragraph\n", st.get(1).l1());
        assertEquals(text, r.reconstruct("prompt"));
        TransformationRecord rec = st.get(0).record();
        assertEquals(LosslessPass.OP_WHITESPACE, rec.operator());
        assertEquals("BLANK_LINE_RUN+TRAILING_WHITESPACE", rec.reasonCode());
        assertEquals(Level.LOSSLESS, rec.level());
        assertNotEquals(rec.hashBefore(), rec.hashAfter());
    }

    @Test
    void exactDuplicateIsMergedIntoFirstOccurrenceWithMarker() {
        String text = DUP + "\n\nOther text that stays.\n\n" + DUP + "\n";
        LosslessResult r = run(prompt(text));
        assertEquals(List.of(Action.KEPT, Action.KEPT, Action.MERGED), actions(r, "prompt"));
        UnitState merged = r.states("prompt").get(2);
        assertEquals("prompt#0", merged.mergedInto());
        assertEquals("", merged.l1());
        assertEquals(DUP + "\n", merged.original());
        TransformationRecord rec = merged.record();
        assertEquals(LosslessPass.OP_DEDUP, rec.operator());
        assertEquals("prompt#0", rec.relatedUnitId());
        assertEquals(COUNTER.count(OmissionMarker.exactRepeat("prompt", 0)).tokens(), rec.tokensAfter());
        assertTrue(rec.tokensAfter() < rec.tokensBefore());
        assertTrue(r.tokensAfter() < r.tokensBefore());
        assertEquals(text, r.reconstruct("prompt"));
    }

    @Test
    void duplicateIsFoundDespiteDifferentTrailingBlankLines() {
        String text = DUP + "\n\n\n\nmiddle paragraph\n\n" + DUP;
        LosslessResult r = run(prompt(text));
        assertEquals(Action.MERGED, r.states("prompt").get(2).action());
        assertEquals(text, r.reconstruct("prompt"));
    }

    @Test
    void nearDuplicateIsNotMerged() {
        String text = DUP + "\n\n" + DUP.replace("quarterly", "monthly") + "\n";
        LosslessResult r = run(prompt(text));
        assertEquals(List.of(Action.KEPT, Action.KEPT), actions(r, "prompt"));
    }

    @Test
    void shortDuplicatesBelowThresholdAreKept() {
        String text = "Yes.\n\nNo.\n\nYes.\n";
        LosslessResult r = run(prompt(text));
        assertEquals(List.of(Action.KEPT, Action.KEPT, Action.KEPT), actions(r, "prompt"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.code().equals("DUPLICATE_NOT_MERGED")));
    }

    @Test
    void duplicateWhoseMarkerIsNotCheaperIsKept() {
        // ~10 tokens, below the marker's cost, but above a minDuplicateTokens of 1
        String text = "Alpha beta gamma delta epsilon zeta.\n\nAlpha beta gamma delta epsilon zeta.\n";
        LosslessPass pass = new LosslessPass(COUNTER, new LosslessPass.Config(true, true, 1));
        LosslessResult r = pass.run(List.of(prompt(text)));
        assertEquals(List.of(Action.KEPT, Action.KEPT), actions(r, "prompt"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.message().contains("marker costs")));
    }

    @Test
    void protectedUnitTypesAreNeverMerged() {
        String text = DUP + "\n\n" + DUP + "\n";
        for (UnitType t : new UnitType[] {UnitType.TASK, UnitType.REQUIREMENT, UnitType.CONSTRAINT}) {
            PreparedDocument pd = retype(prompt(text), 1, t);
            LosslessResult r = run(pd);
            assertEquals(Action.KEPT, r.states("prompt").get(1).action());
        }
        // an UNKNOWN or CONTEXT duplicate is still merged
        assertEquals(Action.MERGED, run(retype(prompt(text), 1, UnitType.CONTEXT)).states("prompt").get(1).action());
    }

    @Test
    void aTaskUnitMayBeTheSurvivorOfAnUntypedDuplicate() {
        String text = DUP + "\n\n" + DUP + "\n";
        LosslessResult r = run(retype(prompt(text), 0, UnitType.TASK));
        assertEquals(List.of(Action.KEPT, Action.MERGED), actions(r, "prompt"));
    }

    @Test
    void promptUnitSurvivesOverIdenticalFileUnitEvenWhenFileIsListedFirst() {
        PreparedDocument file = prepare("notes.txt", "Notes header\n\n" + DUP + "\n", DocRole.FILE, ContentFormat.PLAIN_TEXT);
        PreparedDocument prompt = prompt("Please review.\n\n" + DUP + "\n");
        LosslessResult r = run(file, prompt);
        assertEquals(Action.KEPT, r.states("prompt").get(1).action());
        UnitState fileUnit = r.states("notes.txt").get(1);
        assertEquals(Action.MERGED, fileUnit.action());
        assertEquals("prompt#1", fileUnit.mergedInto());
        assertTrue(r.records().stream().filter(x -> x.action() == Action.MERGED).count() == 1);
    }

    @Test
    void duplicatesAcrossTwoFilesKeepTheEarlierFile() {
        PreparedDocument a = prepare("a.txt", DUP + "\n", DocRole.FILE, ContentFormat.PLAIN_TEXT);
        PreparedDocument b = prepare("b.txt", DUP + "\n", DocRole.FILE, ContentFormat.PLAIN_TEXT);
        LosslessResult r = run(a, b);
        assertEquals(Action.KEPT, r.states("a.txt").get(0).action());
        assertEquals(Action.MERGED, r.states("b.txt").get(0).action());
        assertEquals("a.txt#0", r.states("b.txt").get(0).mergedInto());
    }

    @Test
    void codeLogAndDataDocumentsAreNeverEditedOrDeduplicated() {
        String text = "int a = 1;   \n\n\n\n" + DUP + "\n\n" + DUP + "\n";
        for (ContentFormat f : new ContentFormat[] {ContentFormat.SOURCE_CODE, ContentFormat.LOG, ContentFormat.STRUCTURED_DATA}) {
            PreparedDocument pd = prepare("f.txt", text, DocRole.FILE, f);
            LosslessResult r = run(pd);
            for (UnitState s : r.states("f.txt")) {
                assertEquals(Action.KEPT, s.action());
            }
            assertEquals(text, r.states("f.txt").stream().map(UnitState::l1).reduce("", String::concat));
        }
    }

    @Test
    void codeFileUnitIsNotMergedIntoIdenticalPromptText() {
        PreparedDocument prompt = prompt(DUP + "\n");
        PreparedDocument code = prepare("A.java", DUP + "\n", DocRole.FILE, ContentFormat.SOURCE_CODE);
        LosslessResult r = run(prompt, code);
        assertEquals(Action.KEPT, r.states("A.java").get(0).action());
    }

    @Test
    void fencedCodeInsideProseIsNotEdited() {
        String text = "Intro   \n\n```java\nint a;  \n\n\n\nint b;\n```\n\nEnd\n";
        LosslessResult r = run(prompt(text));
        assertEquals(text, r.reconstruct("prompt"));
        String fence = r.states("prompt").get(1).l1();
        assertEquals("```java\nint a;  \n\n\n\nint b;\n```\n\n", fence);
    }

    @Test
    void hardSpanTextSurvivesByteForByte() {
        String text = "Do not use \"quoted   text  \" here   \n\n\n\nVersion 2.1.0 must stay.\n";
        PreparedDocument pd = prompt(text);
        LosslessResult r = run(pd);
        String l1 = r.states("prompt").stream().map(UnitState::l1).reduce("", String::concat);
        for (ProtectedSpan s : pd.hardSpans()) {
            assertTrue(l1.contains(text.substring(s.start(), s.end())), "lost span " + s);
        }
        assertEquals(text, r.reconstruct("prompt"));
    }

    @Test
    void editInsideAHardSpanIsSkippedNotApplied() {
        String text = "keep this  \n\n\n\ntail";
        // a HARD span covering the trailing spaces and the blank lines
        ProtectedSpan span = new ProtectedSpan("prompt", 4, 17, SpanKind.QUOTED_STRING, Level.HARD, "test");
        PreparedDocument base = prompt(text);
        PreparedDocument pd = new PreparedDocument(base.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, base.units(), List.of(span));
        LosslessResult r = run(pd);
        assertEquals(Action.KEPT, r.states("prompt").get(0).action());
        assertTrue(r.warnings().stream().anyMatch(w -> w.code().equals("EDIT_SKIPPED")));
    }

    @Test
    void duplicateWithExtraHardSpanTextIsNotMerged() {
        String text = DUP + "\n\n" + DUP + "\n";
        PreparedDocument base = prompt(text);
        InformationUnit second = base.units().get(1);
        // pretend the second copy has a HARD span that the first copy lacks
        ProtectedSpan extra = new ProtectedSpan("prompt", second.start(), second.start() + 3, SpanKind.IDENTIFIER, Level.HARD, "test");
        PreparedDocument pd = new PreparedDocument(base.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, base.units(), List.of(extra));
        LosslessResult r = run(pd);
        assertEquals(Action.KEPT, r.states("prompt").get(1).action());
        assertTrue(r.warnings().stream().anyMatch(w -> w.message().contains("not contained")));
    }

    @Test
    void duplicateWithMatchingHardSpansIsMerged() {
        String text = DUP + "\n\n" + DUP + "\n";
        PreparedDocument base = prompt(text);
        InformationUnit a = base.units().get(0);
        InformationUnit b = base.units().get(1);
        List<ProtectedSpan> spans = List.of(
                new ProtectedSpan("prompt", a.start(), a.start() + 3, SpanKind.IDENTIFIER, Level.HARD, "t"),
                new ProtectedSpan("prompt", b.start(), b.start() + 3, SpanKind.IDENTIFIER, Level.HARD, "t"));
        PreparedDocument pd = new PreparedDocument(base.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, base.units(), spans);
        assertEquals(Action.MERGED, run(pd).states("prompt").get(1).action());
    }

    @Test
    void hardSpanStraddlingUnitBoundaryBlocksMerge() {
        String text = DUP + "\n\n" + DUP + "\n";
        PreparedDocument base = prompt(text);
        InformationUnit second = base.units().get(1);
        ProtectedSpan straddle = new ProtectedSpan("prompt", second.start() - 2, second.start() + 3, SpanKind.QUOTED_STRING, Level.HARD, "t");
        PreparedDocument pd = new PreparedDocument(base.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, base.units(), List.of(straddle));
        assertEquals(Action.KEPT, run(pd).states("prompt").get(1).action());
    }

    @Test
    void softSpansDoNotBlockEditsOrMerges() {
        String text = DUP + "  \n\n" + DUP + "\n";
        PreparedDocument base = prompt(text);
        ProtectedSpan soft = new ProtectedSpan("prompt", 0, 5, SpanKind.IDENTIFIER, Level.SOFT, "t");
        PreparedDocument pd = new PreparedDocument(base.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, base.units(), List.of(soft));
        LosslessResult r = run(pd);
        assertEquals(Action.MERGED, r.states("prompt").get(1).action());
    }

    @Test
    void whitespaceEditThatWouldRaiseTokensIsRejected() {
        TokenCounter odd = new TokenCounter() {
            @Override public TokenCount count(String t) { return new TokenCount(t.contains("\n\n\n") ? 3 : 9, false, "odd"); }
            @Override public String id() { return "odd"; }
        };
        String text = "a\n\n\n\nb\n";
        PreparedDocument base = prompt(text);
        LosslessResult r = new LosslessPass(odd, LosslessPass.Config.defaults()).run(List.of(base));
        assertEquals(Action.KEPT, r.states("prompt").get(0).action());
        assertTrue(r.warnings().stream().anyMatch(w -> w.code().equals("TOKENS_INCREASED")));
        assertEquals(text, r.reconstruct("prompt"));
    }

    @Test
    void featuresCanBeSwitchedOff() {
        String text = "a  \n\n\n\n" + DUP + "\n\n" + DUP + "\n";
        LosslessResult off = new LosslessPass(COUNTER, new LosslessPass.Config(false, false, 8)).run(List.of(prompt(text)));
        for (UnitState s : off.states("prompt")) {
            assertEquals(Action.KEPT, s.action());
        }
        LosslessResult wsOnly = new LosslessPass(COUNTER, new LosslessPass.Config(true, false, 8)).run(List.of(prompt(text)));
        assertEquals(Action.TRANSFORMED, wsOnly.states("prompt").get(0).action());
        assertEquals(Action.KEPT, wsOnly.states("prompt").get(2).action());
    }

    @Test
    void exactlyOneRecordPerUnitAndRecordsMatchStates() {
        String text = "A  \n\n\n\n" + DUP + "\n\nB\n\n" + DUP + "\n";
        LosslessResult r = run(prompt(text));
        List<TransformationRecord> recs = r.records();
        assertEquals(r.states("prompt").size(), recs.size());
        for (int i = 0; i < recs.size(); i++) {
            assertEquals(r.states("prompt").get(i).unit().id(), recs.get(i).unitId());
            assertEquals(r.states("prompt").get(i).action(), recs.get(i).action());
        }
    }

    @Test
    void emptyAndWhitespaceOnlyDocumentsAreHandled() {
        LosslessResult empty = run(prompt(""));
        assertTrue(empty.states("prompt").isEmpty());
        assertEquals("", empty.reconstruct("prompt"));
        assertEquals(0, empty.tokensBefore());
        LosslessResult blank = run(prompt("   \n\n  \n"));
        assertEquals("   \n\n  \n", blank.reconstruct("prompt"));
    }

    @Test
    void noDocumentsGivesEmptyResult() {
        LosslessResult r = new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(List.of());
        assertTrue(r.records().isEmpty());
    }

    @Test
    void duplicateDocumentIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> run(prompt("a"), prompt("b")));
    }

    @Test
    void configAndConstructorRejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new LosslessPass.Config(true, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new LosslessPass(null, LosslessPass.Config.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new LosslessPass(COUNTER, null));
    }

    @Test
    void preparedDocumentRejectsBadPartitionAndForeignSpans() {
        PreparedDocument ok = prompt("abc\n\ndef\n");
        List<InformationUnit> broken = List.of(ok.units().get(0));
        assertThrows(IllegalStateException.class,
                () -> new PreparedDocument(ok.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, broken, List.of()));
        ProtectedSpan foreign = new ProtectedSpan("other", 0, 1, SpanKind.IDENTIFIER, Level.HARD, "t");
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedDocument(ok.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, ok.units(), List.of(foreign)));
        ProtectedSpan tooFar = new ProtectedSpan("prompt", 0, 999, SpanKind.IDENTIFIER, Level.HARD, "t");
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedDocument(ok.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, ok.units(), List.of(tooFar)));
    }

    @Test
    void verifyDetectsATamperedState() {
        String text = "Intro   \n\n\n\nBody\n";
        LosslessResult r = run(prompt(text));
        List<UnitState> states = new ArrayList<>(r.states("prompt"));
        UnitState s0 = states.get(0);
        // same length, different content: reverse map can no longer reproduce the original
        String tampered = s0.l1().replace('I', 'X');
        states.set(0, new UnitState(s0.unit(), s0.action(), tampered, s0.script(), null, null, s0.record()));
        Map<String, List<UnitState>> map = new LinkedHashMap<>();
        map.put("prompt", states);
        LosslessResult bad = new LosslessResult(r.documents(), map, r.warnings(), r.tokenizerId());
        assertThrows(IllegalStateException.class, bad::verify);
    }

    @Test
    void verifyDetectsAHardSpanThatWasEdited() {
        String text = "must keep   \nnext\n";
        PreparedDocument base = prompt(text);
        ProtectedSpan span = new ProtectedSpan("prompt", 5, 12, SpanKind.QUOTED_STRING, Level.HARD, "t");
        PreparedDocument pd = new PreparedDocument(base.doc(), DocRole.PROMPT, ContentFormat.PLAIN_TEXT, base.units(), List.of(span));
        // forge a state whose edit removes text inside the HARD span
        InformationUnit u = pd.units().get(0);
        String l0 = text.substring(u.start(), u.end());
        EditScript forged = new EditScript(l0.length(), List.of(new TextEdit(9, 12, "   ", "", EditKind.TRAILING_WHITESPACE)));
        TransformationRecord rec = new TransformationRecord(u.id(), Action.TRANSFORMED, "forged", Level.LOSSLESS, "X", 1, 1, "a", "b", null, 0);
        UnitState st = new UnitState(u, Action.TRANSFORMED, forged.apply(l0), forged, null, null, rec);
        Map<String, List<UnitState>> map = new LinkedHashMap<>();
        map.put("prompt", List.of(st));
        LosslessResult bad = new LosslessResult(List.of(pd), map, List.of(), COUNTER.id());
        IllegalStateException e = assertThrows(IllegalStateException.class, bad::verify);
        assertTrue(e.getMessage().contains("HARD span"));
    }

    @Test
    void mergedStateRequiresItsReverseInformation() {
        PreparedDocument pd = prompt(DUP + "\n");
        InformationUnit u = pd.units().get(0);
        TransformationRecord rec = new TransformationRecord(u.id(), Action.MERGED, "x", Level.LOSSLESS, "x", 1, 1, "a", "b", "p#0", 0);
        assertThrows(IllegalArgumentException.class,
                () -> new UnitState(u, Action.MERGED, "", EditScript.empty(1), null, "p#0", rec));
        assertThrows(IllegalArgumentException.class,
                () -> new UnitState(u, Action.KEPT, "x", EditScript.empty(1), "orig", null, rec));
    }

    @Test
    void randomizedRoundTripAndProtectionProperty() {
        Random rnd = new Random(20261017L);
        String[] pieces = {"word", "Do not", "must", "42", "3.5 ms", "\"quoted  text\"", "foo.bar()", "C:\\tmp\\x.txt", "https://e.com/a",
                "```", "~~~", "    ", "\t", " ", "  ", "\n", "\n", "\n\n", "\n\n\n", ".", "NullPointerException at A.b(A.java:12)", DUP};
        for (int iter = 0; iter < 300; iter++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(40);
            for (int i = 0; i < n; i++) {
                sb.append(pieces[rnd.nextInt(pieces.length)]);
                if (rnd.nextInt(3) == 0) {
                    sb.append(' ');
                }
            }
            String text = sb.toString();
            ContentFormat fmt = rnd.nextBoolean() ? ContentFormat.PLAIN_TEXT : ContentFormat.MARKDOWN;
            PreparedDocument pd = prepare("prompt", text, DocRole.PROMPT, fmt);
            LosslessResult r = run(pd);               // run() self-verifies
            r.verify();
            assertEquals(pd.doc().text(), r.reconstruct("prompt"), "iteration " + iter);
            assertTrue(r.tokensAfter() <= r.tokensBefore(), "tokens rose in iteration " + iter);
            String l1 = r.states("prompt").stream().map(s -> s.merged() ? "" : s.l1()).reduce("", String::concat);
            for (ProtectedSpan s : pd.hardSpans()) {
                boolean inMerged = false;
                for (UnitState st : r.states("prompt")) {
                    if (st.merged() && s.start() >= st.unit().start() && s.end() <= st.unit().end()) {
                        inMerged = true;
                    }
                }
                if (!inMerged) {
                    assertTrue(l1.contains(pd.doc().text().substring(s.start(), s.end())), "iteration " + iter + " lost " + s);
                }
            }
        }
    }
}
