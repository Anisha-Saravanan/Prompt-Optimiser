package org.promptoptimizer.recover;

import static org.junit.jupiter.api.Assertions.*;
import static org.promptoptimizer.lossless.LosslessTestSupport.COUNTER;
import static org.promptoptimizer.validate.ValidationTestSupport.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.lossless.ContentFormat;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.TransformationRecord;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.validate.Check;
import org.promptoptimizer.validate.CheckId;
import org.promptoptimizer.validate.CheckResult;
import org.promptoptimizer.validate.FailureClass;
import org.promptoptimizer.validate.Finding;
import org.promptoptimizer.validate.PreservationValidator;
import org.promptoptimizer.validate.ValidationContext;

class RecoveryControllerTest {
    private static final RuleBasedProtectedSpanDetector DETECTOR = new RuleBasedProtectedSpanDetector();
    /** Two copies of this paragraph in a file make the primary output LARGER than the original under the heuristic counter. */
    private static final String PARA = "The quick brown fox jumps over the lazy dog near the old river bank today.";
    /** Trailing spaces inside a list item: the lossless pass strips them, check 4 then refuses the edited carrier. */
    private static final String EDITED_ITEM = "TASK: Write a report.\n\nREQUIREMENTS:\n- Return the answer as a table   \n  with three columns.\n\nClosing note about the team.\n";
    /** A long paragraph: removing its duplicate clearly saves more tokens than the marker and the longer header cost. */
    private static final String LONG_PARA = PARA + " Please read this very careful note about the weekly build and nightly schedule again."
            + " Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu.";
    private static final String TRAILING_FIRST = "TASK: Summarize.   \n\nSecond paragraph stays here.\n";

    private static RecoveryController standard() {
        return RecoveryController.standard(COUNTER, DETECTOR);
    }

    private static RecoveryController with(PreservationValidator v, CandidatePlan.Ladder ladder, RecoveryPolicy p) {
        return new RecoveryController(v, COUNTER, DETECTOR, ladder, p);
    }

    private static Fixture largerThanOriginal() {
        return fixture("TASK: Summarize the notes.\n", file("notes.md", PARA + "\n\n" + PARA + "\n", ContentFormat.MARKDOWN));
    }

    private static int unitCount(Fixture f) {
        return f.docs().stream().mapToInt(d -> d.units().size()).sum();
    }

    // ---- ACCEPT -------------------------------------------------------------------------------------------------

    @Test
    void cleanInputIsAcceptedUnchangedByTheController() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), 100_000);
        assertEquals(RecoveryStatus.ACCEPT, out.status());
        assertEquals(0, out.restoreRounds());
        assertEquals(assemble(f.docs()).text(), out.text());
        assertTrue(out.validation().mandatoryPass());
        assertTrue(out.tokens() <= out.originalTokens());
        assertEquals(out.tokens() - out.originalTokens(), out.netTokenChange());
        assertEquals(unitCount(f), out.records().size());
        assertEquals(1, out.audit().size());
        assertEquals("ACCEPTED", out.audit().get(0).outcome());
        assertTrue(out.deliverable());
        assertNull(out.infeasibility());
    }

    @Test
    void anAcceptedOutcomeStatesWhichChecksWereNotVerified() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), null);
        String w = String.join(" | ", out.warnings());
        assertTrue(w.contains("deterministic checks only"), w);
        assertTrue(w.contains("Semantic consistency"), w);
        assertTrue(w.contains("NOT_RUN"), w);
    }

    @Test
    void emptyInputIsAcceptedEvenWithBudgetZero() {
        Fixture f = fixture("");
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), 0);
        assertEquals(RecoveryStatus.ACCEPT, out.status());
        assertEquals("", out.text());
        assertEquals(0, out.tokens());
    }

    // ---- restore-only recovery ---------------------------------------------------------------------------------

    @Test
    void aRepairableFailureIsFixedByRestoringTheCarrierUnit() {
        Fixture f = fixture(EDITED_ITEM);
        // the pass really produces the defect, and the validator really reports it:
        assertFalse(validate(f, null, assemble(f.docs())).mandatoryPass());

        RecoveryOutcome out = standard().run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.ACCEPT, out.status());
        assertEquals(1, out.restoreRounds());
        assertTrue(out.text().contains("table   \n"), "the restored unit must be verbatim: " + out.text());
        assertTrue(out.validation().mandatoryPass(), out.validation().summary());

        List<TransformationRecord> restored = out.records().stream().filter(r -> r.action() == Action.RESTORED).toList();
        assertEquals(1, restored.size());
        assertEquals(1, restored.get(0).round());
        assertEquals("REQ_MISSING", restored.get(0).reasonCode());
        assertEquals(LosslessResult.OP_RESTORE, restored.get(0).operator());

        List<String> phases = out.audit().stream().map(a -> a.phase() + ":" + a.outcome()).toList();
        assertEquals(List.of("PRIMARY:REJECTED", "RESTORE_ROUND_1:RESTORED", "RESTORE_ROUND_1:ACCEPTED"), phases);
        assertEquals(1, out.audit().get(1).restored().size());
    }

    @Test
    void restoreNeverAddsATransformationAndStillReconstructsTheOriginal() {
        Fixture f = fixture(EDITED_ITEM, file("notes.md", LONG_PARA + "\n\n" + LONG_PARA + "\n", ContentFormat.MARKDOWN));
        LosslessResult primary = lossless(f.docs());
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), null);
        assertTrue(assemble(f.docs()).tokens() <= out.originalTokens(), "precondition: the duplicate removal must save tokens");
        assertTrue(primary.states("notes.md").stream().anyMatch(st -> st.action() == Action.MERGED), "precondition: a merged unit");
        assertEquals(RecoveryStatus.ACCEPT, out.status());
        assertTrue(out.text().contains("[omitted:"), "the merge survives: restore touched only the failing unit");
        for (PreparedDocument d : f.docs()) {
            String id = d.doc().id();
            assertEquals(d.doc().text(), out.result().reconstruct(id));
            for (int i = 0; i < d.units().size(); i++) {
                Action before = primary.states(id).get(i).action();
                Action after = out.result().states(id).get(i).action();
                assertTrue(after == before || after == Action.RESTORED, before + " -> " + after);
            }
        }
        out.result().verify();
    }

    @Test
    void restoreRoundsCanBeDisabledAndTheLadderTakesOver() {
        Fixture f = fixture(EDITED_ITEM);
        RecoveryOutcome out = with(PreservationValidator.standard(), CandidatePlan.lossless(COUNTER),
                new RecoveryPolicy(0, true)).run(f.docs(), f.profile(), null);
        // whitespace-only still edits the item; only the no-operator plan keeps it verbatim
        assertEquals(RecoveryStatus.FALLBACK_LOSSLESS, out.status());
        assertEquals(0, out.restoreRounds());
        assertTrue(out.audit().get(0).detail().contains("restore rounds are disabled"), out.audit().get(0).detail());
        assertEquals(EDITED_ITEM, out.text());
    }

    @Test
    void policyRejectsMoreRoundsThanTheBlueprintAllowsWithoutScoring() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryPolicy(3, true));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryPolicy(-1, true));
    }

    // ---- larger than the original -----------------------------------------------------------------------------

    @Test
    void anOutputLargerThanTheOriginalIsRejectedAndTheLessLossyPlanIsUsed() {
        Fixture f = largerThanOriginal();
        var primary = assemble(f.docs());
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), null);
        assertTrue(primary.tokens() > out.originalTokens(), "fixture must make the primary output larger: "
                + primary.tokens() + " vs " + out.originalTokens());
        assertTrue(validate(f, null, primary).mandatoryPass(), "the primary output is valid, only larger");
        assertEquals(RecoveryStatus.FALLBACK_LESS_LOSSY, out.status());
        assertTrue(out.tokens() <= out.originalTokens());
        assertFalse(out.text().contains("[omitted:"));
        assertTrue(out.audit().get(0).detail().contains("more than the original"), out.audit().get(0).detail());
    }

    @Test
    void theLargerThanOriginalGateCanBeSwitchedOff() {
        Fixture f = largerThanOriginal();
        RecoveryOutcome out = with(PreservationValidator.standard(), CandidatePlan.lossless(COUNTER),
                new RecoveryPolicy(2, false)).run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.ACCEPT, out.status());
        assertTrue(out.netTokenChange() > 0);
    }

    @Test
    void whenNoCandidateIsSmallerOrEqualTheOriginalIsReturned() {
        Fixture f = largerThanOriginal();
        CandidatePlan.Ladder onlyPrimary = new CandidatePlan.Ladder(CandidatePlan.lossless(COUNTER).primary(), null, null);
        RecoveryOutcome out = with(PreservationValidator.standard(), onlyPrimary, RecoveryPolicy.defaults())
                .run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.FALLBACK_ORIGINAL, out.status());
        assertEquals(out.originalTokens(), out.tokens());
        assertEquals(0, out.netTokenChange());
        assertNull(out.validation());
        assertEquals(unitCount(f), out.records().size());
        assertTrue(out.records().stream().allMatch(r -> r.action() == Action.KEPT && r.reasonCode().equals("FALLBACK_ORIGINAL")));
        assertTrue(out.warnings().get(0).contains("ORIGINAL"), out.warnings().get(0));
        assertTrue(String.join("|", out.warnings()).contains("rung not configured"));
    }

    @Test
    void theOriginalIsReturnedWhenItJustFitsAndIsInfeasibleWhenItIsOneTokenOver() {
        Fixture f = largerThanOriginal();
        CandidatePlan.Ladder onlyPrimary = new CandidatePlan.Ladder(CandidatePlan.lossless(COUNTER).primary(), null, null);
        RecoveryController c = with(PreservationValidator.standard(), onlyPrimary, RecoveryPolicy.defaults());
        int original = c.run(f.docs(), f.profile(), null).originalTokens();

        RecoveryOutcome fits = c.run(f.docs(), f.profile(), original);
        assertEquals(RecoveryStatus.FALLBACK_ORIGINAL, fits.status());
        assertEquals(original, fits.tokens());

        RecoveryOutcome over = c.run(f.docs(), f.profile(), original - 1);
        assertEquals(RecoveryStatus.INFEASIBLE_BUDGET, over.status());
        assertEquals(1, over.infeasibility().deficit());
        assertEquals(original, over.infeasibility().minimumTokens(), "the larger primary candidate is not the minimum");
        assertEquals("original input", over.infeasibility().minimumSource());
        assertEquals(OriginalDelivery.text(f.docs()), over.infeasibility().minimumText());
    }

    // ---- fallback ladder ---------------------------------------------------------------------------------------

    private static CandidatePlan throwing(String name) {
        return new CandidatePlan(name, d -> {
            throw new IllegalStateException("simulated failure of " + name);
        });
    }

    @Test
    void aFailingPrimaryFallsBackToTheLessLossyPlan() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        CandidatePlan.Ladder base = CandidatePlan.lossless(COUNTER);
        RecoveryOutcome out = with(PreservationValidator.standard(),
                new CandidatePlan.Ladder(throwing("p"), base.lessLossy(), base.losslessOnly()), RecoveryPolicy.defaults())
                .run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.FALLBACK_LESS_LOSSY, out.status());
        assertEquals("BUILD_FAILED", out.audit().get(0).outcome());
        assertTrue(out.audit().get(0).detail().contains("simulated failure of p"));
        assertTrue(out.warnings().get(0).contains("FALLBACK_LESS_LOSSY"), out.warnings().get(0));
        assertFalse(out.text().contains("[omitted:"), "less-lossy plan has dedup off");
        assertTrue(out.validation().mandatoryPass());
    }

    @Test
    void aFailingLessLossyPlanFallsBackToTheLosslessOnlyPlan() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        CandidatePlan.Ladder base = CandidatePlan.lossless(COUNTER);
        RecoveryOutcome out = with(PreservationValidator.standard(),
                new CandidatePlan.Ladder(throwing("p"), throwing("l"), base.losslessOnly()), RecoveryPolicy.defaults())
                .run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.FALLBACK_LOSSLESS, out.status());
        assertEquals(f.prompt().doc().text(), out.text(), "no operator: the prompt is delivered as it was");
        assertEquals(3, out.audit().size());
        assertEquals(List.of("PRIMARY", "FALLBACK_LESS_LOSSY", "FALLBACK_LOSSLESS"),
                out.audit().stream().map(AuditEntry::phase).toList());
    }

    @Test
    void whenEveryPlanFailsTheOriginalIsReturnedWithAnExplanation() {
        Fixture f = fixture(BLUEPRINT_PROMPT, file("notes.md", PARA + "\n", ContentFormat.MARKDOWN));
        RecoveryOutcome out = with(PreservationValidator.standard(),
                new CandidatePlan.Ladder(throwing("p"), throwing("l"), throwing("z")), RecoveryPolicy.defaults())
                .run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.FALLBACK_ORIGINAL, out.status());
        assertEquals(OriginalDelivery.text(f.docs()), out.text());
        assertEquals(new Assembler(COUNTER).assemble(new LosslessPass(COUNTER,
                new LosslessPass.Config(false, false, 8)).run(f.docs())).text(), out.text(),
                "the raw original equals the no-operator assembly");
        String w = String.join(" | ", out.warnings());
        assertTrue(w.contains("simulated failure of p") && w.contains("simulated failure of z"), w);
        assertEquals("FALLBACK_ORIGINAL", out.audit().get(out.audit().size() - 1).phase());
    }

    // ---- INFEASIBLE_BUDGET --------------------------------------------------------------------------------------

    @Test
    void aBudgetBelowTheMinimumSafeRepresentationIsInfeasibleAndNothingIsTruncated() {
        Fixture f = fixture(BLUEPRINT_PROMPT, file("notes.md", PARA + "\n", ContentFormat.MARKDOWN));
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), 5);
        assertEquals(RecoveryStatus.INFEASIBLE_BUDGET, out.status());
        assertNull(out.text());
        assertFalse(out.deliverable());
        assertEquals(0, out.netTokenChange());
        assertTrue(out.records().isEmpty());
        Infeasibility inf = out.infeasibility();
        assertEquals(5, inf.budget());
        assertTrue(inf.deficit() > 0);
        assertEquals(inf.minimumTokens() - 5, inf.deficit());
        assertFalse(inf.exact(), "the heuristic counter is an upper bound");
        assertEquals(5, inf.options().size());
        assertTrue(inf.options().get(0).contains(String.valueOf(inf.minimumTokens())));
        assertTrue(inf.options().get(1).contains("NOT AVAILABLE"));
        assertTrue(inf.composition().containsKey("TASK") && inf.composition().containsKey("CONSTRAINT"), inf.composition().toString());
        assertTrue(inf.composition().containsKey("SCAFFOLD"), "file header counts as scaffold: " + inf.composition());
        assertTrue(inf.minimumText().contains("Do not change the public method signatures of StudentService."),
                "the minimum safe representation keeps every constraint");
        assertEquals("INFEASIBLE_BUDGET", out.audit().get(out.audit().size() - 1).phase());
    }

    @Test
    void theBudgetBoundaryIsExact() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        RecoveryOutcome inf = standard().run(f.docs(), f.profile(), 1);
        int m = inf.infeasibility().minimumTokens();
        RecoveryOutcome atM = standard().run(f.docs(), f.profile(), m);
        assertTrue(atM.deliverable(), atM.status().toString());
        assertTrue(atM.tokens() <= m);
        RecoveryOutcome below = standard().run(f.docs(), f.profile(), m - 1);
        assertEquals(RecoveryStatus.INFEASIBLE_BUDGET, below.status());
        assertEquals(1, below.infeasibility().deficit());
        assertEquals(m, below.infeasibility().minimumTokens());
    }

    @Test
    void aCandidateThatFitsWhereTheOriginalDoesNotIsAccepted() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        int primary = assemble(f.docs()).tokens();
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), primary);
        assertTrue(out.originalTokens() > primary, "the fixture must save tokens");
        assertEquals(RecoveryStatus.ACCEPT, out.status());
        assertTrue(out.text().contains("[omitted:"));
    }

    @Test
    void overBudgetIsNotRepairableByRestoringAndIsReportedInTheAudit() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        int primary = assemble(f.docs()).tokens();
        RecoveryOutcome out = standard().run(f.docs(), f.profile(), primary - 1);
        assertEquals(RecoveryStatus.INFEASIBLE_BUDGET, out.status());
        assertTrue(out.audit().get(0).detail().contains("OVERBUDGET cannot be cured by restoring units"), out.audit().get(0).detail());
        assertEquals(primary, out.infeasibility().minimumTokens());
        assertEquals("lossless-pass:whitespace+dedup", out.infeasibility().minimumSource());
    }

    // ---- loop safety (scripted checks) -------------------------------------------------------------------------

    /** A check whose failures are scripted per candidate. */
    private interface Script {
        List<Finding> findings(ValidationContext ctx);
    }

    private static Check scripted(CheckId id, Script s) {
        return new Check() {
            @Override
            public CheckId id() {
                return id;
            }

            @Override
            public CheckResult run(ValidationContext ctx) {
                return CheckResult.of(id, s.findings(ctx));
            }
        };
    }

    private static boolean restored(ValidationContext ctx, String unitId) {
        return ctx.candidate().records().stream().anyMatch(r -> r.unitId().equals(unitId) && r.action() == Action.RESTORED);
    }

    private static Finding finding(CheckId id, FailureClass cls, String unit) {
        return new Finding(id, cls, Finding.Severity.ERROR, "scripted " + cls + " on " + unit, "prompt", List.of(unit), null);
    }

    @Test
    void aRoundThatCreatesANewFailureAbortsInsteadOfOscillating() {
        Fixture f = fixture(TRAILING_FIRST);
        String u1 = f.prompt().units().get(0).id();
        String u2 = f.prompt().units().get(1).id();
        PreservationValidator v = new PreservationValidator(List.of(
                scripted(CheckId.PROTECTED_SPANS, c -> restored(c, u1) ? List.of()
                        : List.of(finding(CheckId.PROTECTED_SPANS, FailureClass.SPAN_LOST, u1))),
                scripted(CheckId.EXACT_VALUES, c -> restored(c, u1) && !restored(c, u2)
                        ? List.of(finding(CheckId.EXACT_VALUES, FailureClass.VALUE_LOST, u2)) : List.of())));
        RecoveryOutcome out = with(v, CandidatePlan.lossless(COUNTER), RecoveryPolicy.defaults()).run(f.docs(), f.profile(), null);
        assertTrue(out.audit().stream().anyMatch(a -> a.outcome().equals("ABORTED") && a.detail().contains("no progress")),
                out.audit().toString());
        assertEquals(1, out.audit().stream().filter(a -> a.outcome().equals("RESTORED")).count(), "no second restore after the abort");
        assertEquals(RecoveryStatus.FALLBACK_ORIGINAL, out.status(), "the scripted check also fails every fallback rung");
    }

    @Test
    void aFailureThatRestoringCannotChangeGoesStraightToTheLadder() {
        Fixture f = fixture(TRAILING_FIRST);
        String u2 = f.prompt().units().get(1).id(); // KEPT: already original
        PreservationValidator v = new PreservationValidator(List.of(
                scripted(CheckId.PROTECTED_SPANS, c -> List.of(finding(CheckId.PROTECTED_SPANS, FailureClass.SPAN_LOST, u2)))));
        RecoveryOutcome out = with(v, CandidatePlan.lossless(COUNTER), RecoveryPolicy.defaults()).run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.FALLBACK_ORIGINAL, out.status());
        assertTrue(out.audit().get(0).detail().contains("already in its original form"), out.audit().get(0).detail());
        assertEquals(0, out.audit().stream().filter(a -> a.outcome().equals("RESTORED")).count());
    }

    @Test
    void unrestorableFailureClassesAndFindingsWithoutAUnitAreNotRepaired() {
        Fixture f = fixture(TRAILING_FIRST);
        String u1 = f.prompt().units().get(0).id();
        PreservationValidator prov = new PreservationValidator(List.of(
                scripted(CheckId.PROVENANCE, c -> List.of(finding(CheckId.PROVENANCE, FailureClass.PROVENANCE, u1)))));
        RecoveryOutcome a = with(prov, CandidatePlan.lossless(COUNTER), RecoveryPolicy.defaults()).run(f.docs(), f.profile(), null);
        assertTrue(a.audit().get(0).detail().contains("PROVENANCE cannot be cured"), a.audit().get(0).detail());

        PreservationValidator noUnit = new PreservationValidator(List.of(
                scripted(CheckId.PROTECTED_SPANS, c -> List.of(new Finding(CheckId.PROTECTED_SPANS, FailureClass.SPAN_LOST,
                        Finding.Severity.ERROR, "no unit", null, List.of(), null)))));
        RecoveryOutcome b = with(noUnit, CandidatePlan.lossless(COUNTER), RecoveryPolicy.defaults()).run(f.docs(), f.profile(), null);
        assertTrue(b.audit().get(0).detail().contains("names no unit"), b.audit().get(0).detail());

        PreservationValidator bare = new PreservationValidator(List.of(new Check() {
            @Override
            public CheckId id() {
                return CheckId.STRUCTURE;
            }

            @Override
            public CheckResult run(ValidationContext ctx) {
                return new CheckResult(CheckId.STRUCTURE, CheckResult.Status.FAIL, "failed without findings", List.of());
            }
        }));
        RecoveryOutcome c = with(bare, CandidatePlan.lossless(COUNTER), RecoveryPolicy.defaults()).run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.FALLBACK_ORIGINAL, c.status());
    }

    @Test
    void warningFindingsDoNotTriggerRecovery() {
        Fixture f = fixture(TRAILING_FIRST);
        String u1 = f.prompt().units().get(0).id();
        PreservationValidator v = new PreservationValidator(List.of(scripted(CheckId.REQUIREMENTS, c -> List.of(
                new Finding(CheckId.REQUIREMENTS, FailureClass.REQ_MISSING, Finding.Severity.WARNING, "only a warning", "prompt",
                        List.of(u1), null)))));
        RecoveryOutcome out = with(v, CandidatePlan.lossless(COUNTER), RecoveryPolicy.defaults()).run(f.docs(), f.profile(), null);
        assertEquals(RecoveryStatus.ACCEPT, out.status());
        assertEquals(0, out.restoreRounds());
    }

    // ---- input validation ---------------------------------------------------------------------------------------

    @Test
    void invalidInputsAreRejectedNotTurnedIntoFallbacks() {
        Fixture f = fixture(BLUEPRINT_PROMPT);
        Fixture other = fixture("TASK: Another prompt entirely.\n");
        assertThrows(IllegalArgumentException.class, () -> standard().run(f.docs(), f.profile(), -1));
        assertThrows(IllegalArgumentException.class, () -> standard().run(f.docs(), other.profile(), null));
        assertThrows(IllegalArgumentException.class, () -> standard().run(List.of(f.docs().get(0), f.docs().get(0)), null, null));
        assertThrows(NullPointerException.class, () -> standard().run(null, null, null));
        assertEquals(RecoveryStatus.ACCEPT, standard().run(f.docs(), null, null).status(), "a null profile is allowed");
    }

    @Test
    void originalDeliveryMatchesTheAssemblerForFilesAndMissingTrailingNewlines() {
        Fixture f = fixture("TASK: Check these.", file("a.txt", "no trailing newline", ContentFormat.PLAIN_TEXT),
                file("b.txt", "second\n", ContentFormat.PLAIN_TEXT));
        String viaAssembler = new Assembler(COUNTER).assemble(new LosslessPass(COUNTER,
                new LosslessPass.Config(false, false, 8)).run(f.docs())).text();
        assertEquals(viaAssembler, OriginalDelivery.text(f.docs()));
        assertEquals(Set.of(), Set.of(), "sanity");
    }
}
