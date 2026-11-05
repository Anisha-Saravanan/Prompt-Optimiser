package org.promptoptimizer.optimize;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.LosslessResult.ContentFormat;
import org.promptoptimizer.lossless.LosslessResult.DocRole;
import org.promptoptimizer.lossless.LosslessResult.Hashes;
import org.promptoptimizer.lossless.LosslessResult.PreparedDocument;
import org.promptoptimizer.lossless.LosslessResult.TransformationRecord;
import org.promptoptimizer.model.Model.Action;
import org.promptoptimizer.model.Model.CanonicalDocument;
import org.promptoptimizer.model.Model.InformationUnit;
import org.promptoptimizer.model.Model.Level;
import org.promptoptimizer.model.Model.ProtectedSpan;
import org.promptoptimizer.model.Model.SpanKind;
import org.promptoptimizer.model.Model.UnitType;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.recover.RecoveryController;
import org.promptoptimizer.recover.RecoveryOutcome.RecoveryStatus;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.task.TaskProfile.Confidence;
import org.promptoptimizer.task.TaskProfile.ExtractedItem;
import org.promptoptimizer.task.TaskProfile.ItemKind;
import org.promptoptimizer.task.TaskProfile.Provenance;
import org.promptoptimizer.task.TaskProfile.SourceRef;
import org.promptoptimizer.token.HeuristicTokenCounter;
import org.promptoptimizer.token.TokenCounter;
import org.promptoptimizer.validate.PreservationValidator;
import org.promptoptimizer.validate.ValidationResult.CheckId;
import org.promptoptimizer.validate.ValidationResult.FailureClass;
import org.promptoptimizer.validate.ValidationResult.ValidationContext;

import static org.junit.jupiter.api.Assertions.*;

class BudgetControllerTest {
    private static final TokenCounter COUNTER = new HeuristicTokenCounter();

    @Test
    void selectionKeepsTheTaskAndItsTransitiveCodeDependenciesWithinAnExactBudget() {
        Fixture fixture = codeFixture();
        LosslessResult base = new LosslessPass(COUNTER,
                new LosslessPass.Config(false, false, 8)).run(fixture.documents());
        Assembler assembler = new Assembler(COUNTER);
        LosslessResult targetPlan = base.withDropped(Set.of("src/Main.java#2"),
                BudgetController.REASON_SELECTED_OUT);
        int budget = assembler.assemble(targetPlan).tokens();

        BudgetController.SelectionResult selection = new BudgetController().select(
                base, fixture.profile(), budget, COUNTER);

        assertTrue(selection.tokens() <= budget);
        assertTrue(selection.selectedUnitIds().containsAll(Set.of("prompt#0", "src/Main.java#0", "src/Main.java#1")));
        assertTrue(selection.droppedUnitIds().contains("src/Main.java#2"));
        assertTrue(assembler.assemble(selection.result()).text().contains("class Target"));
        assertFalse(assembler.assemble(selection.result()).text().contains("class Unrelated"));
        assertTrue(selection.combinedScores().get("src/Main.java#0") > 0);
    }

    @Test
    void unknownUnitsAndHardSpansAreNeverSelectedOut() {
        String text = "Find bug.\n\nKeep this exact codeName.\n";
        int split = text.indexOf("\n\n") + 2;
        int protectedStart = text.indexOf("codeName");
        CanonicalDocument document = canonical("prompt", text);
        List<InformationUnit> units = List.of(
                unit(document, 0, 0, split, UnitType.TASK),
                unit(document, 1, split, text.length(), UnitType.CONTEXT));
        ProtectedSpan hard = new ProtectedSpan("prompt", protectedStart, protectedStart + "codeName".length(),
                SpanKind.IDENTIFIER, Level.HARD, "test");
        PreparedDocument prepared = new PreparedDocument(document, DocRole.PROMPT, ContentFormat.PLAIN_TEXT,
                units, List.of(hard));
        LosslessResult result = new LosslessPass(COUNTER,
                new LosslessPass.Config(false, false, 8)).run(List.of(prepared));

        assertThrows(IllegalArgumentException.class,
                () -> result.withDropped(Set.of("prompt#1"), BudgetController.REASON_SELECTED_OUT));
        BudgetController.SelectionResult selection = new BudgetController().select(result,
                profile(document, "Find bug."), 0, COUNTER);
        assertTrue(selection.droppedUnitIds().isEmpty());
    }

    @Test
    void recoveryDeliversAuditedDropsOnlyAfterAllMandatoryChecksPass() {
        Fixture fixture = contextFixture();
        LosslessResult base = RecoveryController.standard(COUNTER, new RuleBasedProtectedSpanDetector())
                .run(fixture.documents(), fixture.profile(), null).result();
        int budget = new Assembler(COUNTER).assemble(
                base.withDropped(Set.of("prompt#1"), BudgetController.REASON_SELECTED_OUT)).tokens();

        var outcome = RecoveryController.standard(COUNTER, new RuleBasedProtectedSpanDetector())
                .run(fixture.documents(), fixture.profile(), budget);

        assertEquals(RecoveryStatus.ACCEPT, outcome.status(), outcome.warnings().toString());
        assertTrue(outcome.validation().mandatoryPass(), outcome.validation().summary());
        assertTrue(outcome.records().stream().anyMatch(record -> record.unitId().equals("prompt#1")
                && record.action() == Action.DROPPED && record.level() == Level.LOSSY));
        assertTrue(outcome.audit().stream().anyMatch(entry -> entry.outcome().equals("SELECTED")));
        assertFalse(outcome.text().contains("Background:"));
    }

    @Test
    void recoveryRestoresFreshlyDetectedValuesAndReselectsWithinTheBudget() {
        String text = "Find bug in payments.\n\nPayments build errors need review.\n\nMeeting schedule on 2026-11-04.\n";
        int firstEnd = text.indexOf("\n\n") + 2;
        int secondEnd = text.indexOf("\n\n", firstEnd) + 2;
        CanonicalDocument document = canonical("prompt", text);
        List<InformationUnit> units = List.of(
                unit(document, 0, 0, firstEnd, UnitType.TASK),
                unit(document, 1, firstEnd, secondEnd, UnitType.CONTEXT),
                unit(document, 2, secondEnd, text.length(), UnitType.CONTEXT));
        PreparedDocument prepared = new PreparedDocument(document, DocRole.PROMPT, ContentFormat.PLAIN_TEXT,
                units, List.of());
        Fixture fixture = new Fixture(List.of(prepared), profile(document, "Find bug in payments."));
        LosslessResult base = new LosslessPass(COUNTER,
                new LosslessPass.Config(false, false, 8)).run(fixture.documents());
        Assembler assembler = new Assembler(COUNTER);
        int firstOnly = assembler.assemble(base.withDropped(Set.of("prompt#2"),
                BudgetController.REASON_SELECTED_OUT)).tokens();
        int secondOnly = assembler.assemble(base.withDropped(Set.of("prompt#1"),
                BudgetController.REASON_SELECTED_OUT)).tokens();

        var outcome = RecoveryController.standard(COUNTER, new RuleBasedProtectedSpanDetector())
                .run(fixture.documents(), fixture.profile(), Math.max(firstOnly, secondOnly));

        assertEquals(RecoveryStatus.ACCEPT, outcome.status(), outcome.warnings().toString());
        assertEquals(1, outcome.restoreRounds());
        assertTrue(outcome.text().contains("2026-11-04"));
        assertFalse(outcome.text().contains("build errors"));
        assertTrue(outcome.audit().stream().anyMatch(entry -> entry.outcome().equals("RESELECTED")));
        assertEquals(Action.RESTORED, outcome.records().stream()
                .filter(record -> record.unitId().equals("prompt#2")).findFirst().orElseThrow().action());
        assertEquals(Action.DROPPED, outcome.records().stream()
                .filter(record -> record.unitId().equals("prompt#1")).findFirst().orElseThrow().action());
    }

    @Test
    void droppedUnitsCanBeRestoredVerbatimFromTheirReverseInformation() {
        Fixture fixture = contextFixture();
        LosslessResult base = new LosslessPass(COUNTER,
                new LosslessPass.Config(false, false, 8)).run(fixture.documents());
        LosslessResult dropped = base.withDropped(Set.of("prompt#1"), BudgetController.REASON_SELECTED_OUT);

        LosslessResult restored = dropped.withRestored(Set.of("prompt#1"), 1, "REQ_MISSING");
        TransformationRecord record = restored.records().stream()
                .filter(item -> item.unitId().equals("prompt#1")).findFirst().orElseThrow();

        assertEquals(Action.RESTORED, record.action());
        assertEquals("Background: The weekly team meeting covers project calendars.\n",
                restored.states("prompt").get(1).original());
        assertEquals(base.reconstruct("prompt"), restored.reconstruct("prompt"));
    }

    @Test
    void infersAndClosesJavaTypeReferencesWithoutAParser() {
        Fixture fixture = codeFixture();
        DependencyGraph graph = DependencyGraph.infer(fixture.documents());

        assertTrue(graph.closure(Set.of("src/Main.java#0")).contains("src/Main.java#1"));
        assertFalse(graph.closure(Set.of("prompt#0")).contains("src/Main.java#0"),
                "sentence-final punctuation is not a class-member reference");
    }

    @Test
    void validatorRejectsASelectionThatLeavesAJavaReferenceDangling() {
        Fixture fixture = codeFixture();
        LosslessResult base = new LosslessPass(COUNTER,
                new LosslessPass.Config(false, false, 8)).run(fixture.documents());
        LosslessResult dangling = base.withDropped(Set.of("src/Main.java#1"),
                BudgetController.REASON_SELECTED_OUT);
        var assembled = new Assembler(COUNTER).assemble(dangling);
        var validation = PreservationValidator.standard().validate(new ValidationContext(fixture.documents(),
                fixture.profile(), null, COUNTER, new RuleBasedProtectedSpanDetector(), assembled));

        assertTrue(validation.failingChecks().contains(CheckId.CLOSURE));
        assertTrue(validation.errors().stream().anyMatch(finding -> finding.failureClass() == FailureClass.DANGLING_REF
                && finding.unitIds().containsAll(List.of("src/Main.java#0", "src/Main.java#1"))));
    }

    private static Fixture codeFixture() {
        String promptText = "Find bugs in Caller.\n";
        String code = "class Caller { Target target; }\n\nclass Target { int value; }\n\nclass Unrelated { int x; }\n";
        CanonicalDocument prompt = canonical("prompt", promptText);
        CanonicalDocument source = canonical("src/Main.java", code);
        List<InformationUnit> promptUnits = List.of(unit(prompt, 0, 0, promptText.length(), UnitType.TASK));
        List<InformationUnit> sourceUnits = new ArrayList<>();
        int firstEnd = code.indexOf("\n\n") + 2;
        int secondEnd = code.indexOf("\n\n", firstEnd) + 2;
        sourceUnits.add(unit(source, 0, 0, firstEnd, UnitType.CODE));
        sourceUnits.add(unit(source, 1, firstEnd, secondEnd, UnitType.CODE));
        sourceUnits.add(unit(source, 2, secondEnd, code.length(), UnitType.CODE));
        return new Fixture(List.of(
                new PreparedDocument(prompt, DocRole.PROMPT, ContentFormat.PLAIN_TEXT, promptUnits, List.of()),
                new PreparedDocument(source, DocRole.FILE, ContentFormat.SOURCE_CODE, sourceUnits, List.of())),
                profile(prompt, "Find bugs in Caller."));
    }

    private static Fixture contextFixture() {
        String text = "Find bug.\n\nBackground: The weekly team meeting covers project calendars.\n";
        int split = text.indexOf("\n\n") + 2;
        CanonicalDocument prompt = canonical("prompt", text);
        List<InformationUnit> units = List.of(
                unit(prompt, 0, 0, split, UnitType.TASK),
                unit(prompt, 1, split, text.length(), UnitType.CONTEXT));
        return new Fixture(List.of(new PreparedDocument(prompt, DocRole.PROMPT,
                ContentFormat.PLAIN_TEXT, units, List.of())), profile(prompt, "Find bug."));
    }

    private static TaskProfile profile(CanonicalDocument document, String taskText) {
        int start = document.text().indexOf(taskText);
        SourceRef source = new SourceRef(document.id(), start, start + taskText.length());
        ExtractedItem task = new ExtractedItem("T1", ItemKind.TASK, "task", taskText,
                new Provenance(source, "test-fixture", Provenance.Basis.EXPLICIT_LABEL),
                Confidence.HIGH, List.of());
        return new TaskProfile(document, task, List.of(), List.of(), List.of(), List.of(), List.of(),
                Confidence.HIGH, "test-fixture");
    }

    private static CanonicalDocument canonical(String id, String text) {
        return new CanonicalDocument(id, text, Hashes.sha256(text), false);
    }

    private static InformationUnit unit(CanonicalDocument document, int ordinal, int start, int end, UnitType type) {
        return new InformationUnit(document.id(), ordinal, start, end, type,
                COUNTER.count(document.text().substring(start, end)).tokens());
    }

    private record Fixture(List<PreparedDocument> documents, TaskProfile profile) {}
}
