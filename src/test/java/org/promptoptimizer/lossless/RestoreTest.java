package org.promptoptimizer.lossless;

import static org.junit.jupiter.api.Assertions.*;
import static org.promptoptimizer.lossless.LosslessTestSupport.COUNTER;
import static org.promptoptimizer.validate.ValidationTestSupport.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.Level;

/** Tests of {@link LosslessResult#withRestored}: restore from stored reverse information only. */
class RestoreTest {
    private static final String PARA = "The quick brown fox jumps over the lazy dog near the old river bank today.";
    private static final String TRAILING = "TASK: Write a report.   \n\nSecond paragraph stays exactly here.\n";

    private static LosslessResult noOperators(List<PreparedDocument> docs) {
        return new LosslessPass(COUNTER, new LosslessPass.Config(false, false, 8)).run(docs);
    }

    @Test
    void restoringAnEditedUnitPutsBackTheOriginalAndAuditsIt() {
        Fixture f = fixture(TRAILING);
        LosslessResult r = lossless(f.docs());
        UnitState before = r.states("prompt").get(0);
        assertEquals(Action.TRANSFORMED, before.action());
        assertFalse(before.l1().equals(before.original()));

        LosslessResult restored = r.withRestored(List.of(before.unit().id()), 1, "REQ_MISSING");
        UnitState after = restored.states("prompt").get(0);
        assertEquals(Action.RESTORED, after.action());
        assertEquals(before.original(), after.l1());
        assertTrue(after.script().isEmpty());

        TransformationRecord rec = after.record();
        assertEquals(Action.RESTORED, rec.action());
        assertEquals(LosslessResult.OP_RESTORE, rec.operator());
        assertEquals(Level.LOSSLESS, rec.level());
        assertEquals("REQ_MISSING", rec.reasonCode());
        assertEquals(1, rec.round());
        assertEquals(rec.hashBefore(), rec.hashAfter());
        assertEquals(rec.tokensBefore(), rec.tokensAfter());
        assertEquals(TRAILING, restored.reconstruct("prompt"));
        restored.verify();
    }

    @Test
    void theSourceResultIsNotModified() {
        Fixture f = fixture(TRAILING);
        LosslessResult r = lossless(f.docs());
        List<UnitState> snapshot = List.copyOf(r.states("prompt"));
        r.withRestored(List.of(r.states("prompt").get(0).unit().id()), 1, "X");
        assertEquals(snapshot, r.states("prompt"));
        assertEquals(Action.TRANSFORMED, r.states("prompt").get(0).action());
    }

    @Test
    void restoringAMergedUnitBringsTheTextBackAndDropsTheMarker() {
        Fixture f = fixture("TASK: Summarize the notes.\n", file("notes.md", PARA + "\n\n" + PARA + "\n", ContentFormat.MARKDOWN));
        LosslessResult r = lossless(f.docs());
        UnitState merged = r.states("notes.md").stream().filter(UnitState::merged).findFirst().orElseThrow();
        AssembledInput withMarker = new Assembler(COUNTER).assemble(r);
        assertTrue(withMarker.text().contains("[omitted:"));

        LosslessResult restored = r.withRestored(List.of(merged.unit().id()), 1, "SPAN_LOST");
        UnitState after = restored.state(merged.unit().id());
        assertEquals(Action.RESTORED, after.action());
        assertEquals(merged.removedOriginal(), after.l1());
        assertNull(after.removedOriginal());
        AssembledInput out = new Assembler(COUNTER).assemble(restored);
        assertFalse(out.text().contains("[omitted:"), out.text());
        assertFalse(out.text().contains("repeated block"), out.text());
        assertEquals(2, out.text().split(java.util.regex.Pattern.quote(PARA), -1).length - 1);
        assertEquals(r.reconstruct("notes.md"), restored.reconstruct("notes.md"));
    }

    @Test
    void restoringEverythingEqualsTheNoOperatorResult() {
        Fixture f = fixture("TASK: Summarize the notes.   \n", file("notes.md", PARA + "  \n\n" + PARA + "\n", ContentFormat.MARKDOWN));
        LosslessResult r = lossless(f.docs());
        List<String> all = f.docs().stream().flatMap(d -> d.units().stream()).map(u -> u.id()).toList();
        LosslessResult restored = r.withRestored(all, 1, "ALL");
        String expected = new Assembler(COUNTER).assemble(noOperators(f.docs())).text();
        assertEquals(expected, new Assembler(COUNTER).assemble(restored).text());
    }

    @Test
    void restoringAnOriginalOrUnlistedUnitChangesNothing() {
        Fixture f = fixture(TRAILING);
        LosslessResult r = lossless(f.docs());
        UnitState kept = r.states("prompt").get(1);
        assertEquals(Action.KEPT, kept.action());
        LosslessResult same = r.withRestored(List.of(kept.unit().id()), 1, "X");
        assertEquals(r.states("prompt"), same.states("prompt"));
        assertEquals(r.states("prompt"), r.withRestored(List.of(), 1, "X").states("prompt"));
    }

    @Test
    void restoringTwiceKeepsTheFirstRoundAndAuditRecord() {
        Fixture f = fixture(TRAILING);
        LosslessResult r = lossless(f.docs());
        String id = r.states("prompt").get(0).unit().id();
        LosslessResult once = r.withRestored(List.of(id), 1, "A");
        LosslessResult twice = once.withRestored(List.of(id), 2, "B");
        assertEquals(1, twice.state(id).record().round());
        assertEquals("A", twice.state(id).record().reasonCode());
    }

    @Test
    void invalidArgumentsAreRejected() {
        Fixture f = fixture(TRAILING);
        LosslessResult r = lossless(f.docs());
        String id = r.states("prompt").get(0).unit().id();
        assertThrows(IllegalArgumentException.class, () -> r.withRestored(List.of("nope#1"), 1, "X"));
        assertThrows(IllegalArgumentException.class, () -> r.withRestored(List.of(id), 0, "X"));
        assertThrows(IllegalArgumentException.class, () -> r.withRestored(List.of(id), 1, " "));
        assertThrows(IllegalArgumentException.class, () -> r.state("nope#1"));
    }

    @Test
    void oneRecordPerUnitAndExactlyOneTerminalActionAfterRestore() {
        Fixture f = fixture(TRAILING, file("notes.md", PARA + "\n\n" + PARA + "\n", ContentFormat.MARKDOWN));
        LosslessResult r = lossless(f.docs());
        LosslessResult restored = r.withRestored(
                f.docs().stream().flatMap(d -> d.units().stream()).map(u -> u.id()).toList(), 1, "ALL");
        int units = f.docs().stream().mapToInt(d -> d.units().size()).sum();
        assertEquals(units, restored.records().size());
        assertTrue(restored.records().stream().allMatch(x -> x.action() == Action.RESTORED || x.action() == Action.KEPT));
        assertEquals(restored.tokensBefore(), restored.tokensAfter());
    }
}
