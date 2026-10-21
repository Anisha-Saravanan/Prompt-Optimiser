package org.promptoptimizer.assemble;

import static org.junit.jupiter.api.Assertions.*;
import static org.promptoptimizer.lossless.LosslessTestSupport.COUNTER;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.agents.ClassificationAgent;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.lossless.ContentFormat;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.UnitState;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.model.UnitType;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.segment.ProfileSegmenter;
import org.promptoptimizer.task.LlmTaskProfiler;
import org.promptoptimizer.task.TaskProfile;

/** Milestone 1 + 2 + 3 chained: canonicalize, profile, typed segmentation, protection, lossless pass, assembly. */
class PipelineIntegrationTest {
    private static final Canonicalizer CANON = new Canonicalizer(1 << 20);

    /** Blueprint section 10.1 example (an invented example, not evidence) with deliberate noise added. */
    private static final String PROMPT = """
            Hi! Thanks so much in advance. I'm a second-year student working on a grade management
            project for my Data Structures course.   \s


            TASK: Please debug the attached project. Find all compilation errors and logical errors,
            explain the root cause of each, and suggest minimal fixes.

            REQUIREMENTS:
            1. Identify compilation errors.
            2. Identify logical errors.
            3. Explain the root cause of each problem.
            4. Suggest minimal fixes.

            CONSTRAINTS:
            - Do not change the public method signatures of StudentService.
            - Do not rewrite modules that already work.
            - Keep the code compatible with Java 17.



            The report printer prints a header row followed by one line per student and then a footer that summarises everything.

            The report printer prints a header row followed by one line per student and then a footer that summarises everything.
            """;

    private static PreparedDocument typedPrompt() {
        CanonicalDocument doc = CANON.canonicalize("prompt", PROMPT);
        ClassificationAgent fixtureAgent = (text, instructions) -> """
                {"items":[
                  {"kind":"TASK","text":"Please debug the attached project. Find all compilation errors and logical errors,\\nexplain the root cause of each, and suggest minimal fixes."},
                  {"kind":"REQUIREMENT","text":"1. Identify compilation errors.\\n2. Identify logical errors.\\n3. Explain the root cause of each problem.\\n4. Suggest minimal fixes."},
                  {"kind":"CONSTRAINT","text":"- Do not change the public method signatures of StudentService.\\n- Do not rewrite modules that already work.\\n- Keep the code compatible with Java 17."}
                ]}
                """;
        TaskProfile profile = new LlmTaskProfiler(fixtureAgent).profile(doc);
        List<InformationUnit> units = new ProfileSegmenter(profile).segment(doc, COUNTER);
        return new PreparedDocument(doc, DocRole.PROMPT, ContentFormat.PLAIN_TEXT, units,
                new RuleBasedProtectedSpanDetector().detect(doc));
    }

    @Test
    void typedUnitsStayUntouchedAndOnlyTheUntypedRepeatIsMerged() {
        PreparedDocument pd = typedPrompt();
        assertTrue(pd.units().stream().anyMatch(u -> u.type() == UnitType.TASK));
        assertTrue(pd.units().stream().anyMatch(u -> u.type() == UnitType.REQUIREMENT));
        assertTrue(pd.units().stream().anyMatch(u -> u.type() == UnitType.CONSTRAINT));

        LosslessResult r = new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(List.of(pd));
        for (UnitState s : r.states("prompt")) {
            UnitType t = s.unit().type();
            if (t == UnitType.TASK || t == UnitType.REQUIREMENT || t == UnitType.CONSTRAINT) {
                assertTrue(s.action() != Action.MERGED, "typed unit merged: " + s.unit().id());
            }
        }
        assertEquals(1, r.states("prompt").stream().filter(UnitState::merged).count());
        assertEquals(PROMPT, r.reconstruct("prompt"));
        assertTrue(r.tokensAfter() < r.tokensBefore());
    }

    @Test
    void assembledPromptKeepsTaskRequirementsConstraintsAndProtectedTextVerbatim() {
        PreparedDocument pd = typedPrompt();
        LosslessResult r = new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(List.of(pd));
        AssembledInput in = new Assembler(COUNTER).assemble(r);
        String out = in.text();
        assertTrue(out.contains("TASK: Please debug the attached project."));
        assertTrue(out.contains("1. Identify compilation errors.\n2. Identify logical errors."));
        assertTrue(out.contains("- Do not change the public method signatures of StudentService."));
        assertTrue(out.contains("- Keep the code compatible with Java 17."));
        assertTrue(out.contains("[omitted: exact repeat of block"));
        assertFalse(out.contains("\n\n\n"), "blank-line runs should have collapsed");
        assertFalse(out.contains("course.   "), "trailing spaces should have been removed");

        for (ProtectedSpan s : pd.hardSpans()) {
            String t = pd.doc().text().substring(s.start(), s.end());
            assertTrue(out.contains(t), "HARD span missing from output: " + t);
        }
        assertTrue(in.tokens() < in.tokensBefore());
        in.verifyProvenance();
    }

    @Test
    void replayGivesIdenticalOutput() {
        String a = new Assembler(COUNTER).assemble(new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(List.of(typedPrompt()))).text();
        String b = new Assembler(COUNTER).assemble(new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(List.of(typedPrompt()))).text();
        assertEquals(a, b);
    }
}
