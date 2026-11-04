package org.promptoptimizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.agents.categorize_agent;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.lossless.ContentFormat;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.UnitState;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.recover.AuditEntry;
import org.promptoptimizer.recover.RecoveryController;
import org.promptoptimizer.recover.RecoveryOutcome;
import org.promptoptimizer.segment.ProfileSegmenter;
import org.promptoptimizer.segment.Segmenter;
import org.promptoptimizer.task.ExtractedItem;
import org.promptoptimizer.task.LlmTaskProfiler;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.HeuristicTokenCounter;
import org.promptoptimizer.token.TokenCount;
import org.promptoptimizer.token.TokenCounter;

/**
 * End-to-end demo of the stages that exist in this project. It is NOT part of the original code base:
 * the project has no main method of its own, so this class wires the real classes together in the same
 * order the tests do (see ValidationTestSupport.fixture and PipelineIntegrationTest.typedPrompt).
 *
 * Usage:  java -cp target/classes org.promptoptimizer.demo.PipelineDemo [path-to-utf8-text-file]
 * With no argument the example prompt below is used.
 */
public final class PipelineDemo {

    private static final String DEFAULT_PROMPT =
            "Write a Java program that calculates the factorial of a number. "
                    + "Do not use external libraries. Include input validation and return only the code.";

    private PipelineDemo() {}

    public static void main(String[] args) throws Exception {
        String prompt = args.length > 0
                ? Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)
                : DEFAULT_PROMPT;

        // The counter is created ONCE and passed to every stage that needs it.
        TokenCounter counter = new HeuristicTokenCounter();

        // ---- Stage 1: canonicalize ------------------------------------------------------------
        Canonicalizer canonicalizer = new Canonicalizer(1 << 20);
        CanonicalDocument doc = canonicalizer.canonicalize("prompt", prompt);
        banner("1. Canonicalizer.canonicalize(String, String)");
        System.out.println("id               = " + doc.id());
        System.out.println("text length      = " + doc.text().length());
        System.out.println("crlfNormalized   = " + doc.crlfNormalized());
        System.out.println("originalSha256   = " + doc.originalSha256());

        // ---- Stage 2: task profiling ------------------------------------------------------------
        LlmTaskProfiler profiler = new LlmTaskProfiler(new categorize_agent());
        TaskProfile profile = profiler.profile(doc);
        banner("2. LlmTaskProfiler.profile(CanonicalDocument)");
        System.out.println("extractorVersion = " + profile.extractorVersion());
        System.out.println("confidence       = " + profile.confidence());
        profile.taskStatement().ifPresentOrElse(
                t -> printItem("task", t),
                () -> System.out.println("task             = (none)"));
        profile.requirements().forEach(i -> printItem("requirement", i));
        profile.constraints().forEach(i -> printItem("constraint", i));
        profile.outputs().forEach(i -> printItem("output", i));
        profile.candidates().forEach(i -> printItem("candidate", i));
        profile.warnings().forEach(w -> System.out.println("warning          = "
                + w.code() + " (" + w.severity() + "): " + w.message()));

        // ---- Stage 3: split at LLM-classified component boundaries -------------------------------
        List<InformationUnit> units = new ProfileSegmenter(profile).segment(doc, counter);
        banner("3. ProfileSegmenter.segment(...)  [LLM component boundaries and types]");
        printUnits(doc, units);

        // ---- Stage 4: partition verification -----------------------------------------------------
        Segmenter.verifyPartition(doc, units); // throws IllegalStateException on a gap/overlap
        StringBuilder rebuilt = new StringBuilder();
        for (InformationUnit u : units) {
            rebuilt.append(u.slice(doc));
        }
        banner("4. Segmenter.verifyPartition(doc, units)");
        System.out.println("verifyPartition  = OK (no exception)");
        System.out.println("concat(units) equals doc.text() = " + rebuilt.toString().equals(doc.text()));

        // ---- Stage 5: calling the token counter directly --------------------------------------
        TokenCount whole = counter.count(doc.text());
        int sumOfUnits = units.stream().mapToInt(InformationUnit::tokens).sum();
        banner("5. HeuristicTokenCounter.count(String)");
        System.out.println("whole document  = " + whole);
        System.out.println("sum of unit.tokens = " + sumOfUnits);

        // ---- Stage 6: protected spans + PreparedDocument ------------------------------------------
        RuleBasedProtectedSpanDetector detector = new RuleBasedProtectedSpanDetector();
        List<ProtectedSpan> spans = detector.detect(doc);
        banner("6. RuleBasedProtectedSpanDetector.detect(CanonicalDocument)");
        for (ProtectedSpan s : spans) {
            System.out.println("[" + s.start() + "," + s.end() + ") " + s.kind() + " " + s.level()
                    + " rule=" + s.reason() + " text=" + show(doc.text().substring(s.start(), s.end())));
        }
        PreparedDocument pd = new PreparedDocument(doc, DocRole.PROMPT, ContentFormat.PLAIN_TEXT, units, spans);

        // ---- Stage 7: lossless pass ---------------------------------------------------------------
        LosslessResult lossless = new LosslessPass(counter, LosslessPass.Config.defaults()).run(List.of(pd));
        banner("7. LosslessPass.run(List<PreparedDocument>)");
        for (UnitState st : lossless.states("prompt")) {
            System.out.println(st.unit().id() + " action=" + st.action() + " reason=" + st.record().reasonCode()
                    + " tokens " + st.record().tokensBefore() + " -> " + st.record().tokensAfter());
        }
        for (LosslessResult.Warning w : lossless.warnings()) {
            System.out.println("warning " + w.code() + " " + w.unitId() + ": " + w.message());
        }
        System.out.println("reconstruct(\"prompt\") equals original = " + lossless.reconstruct("prompt").equals(doc.text()));

        // ---- Stage 8: assembly ----------------------------------------------------------------------
        AssembledInput assembled = new Assembler(counter).assemble(lossless);
        banner("8. Assembler.assemble(LosslessResult)");
        System.out.println("tokens = " + assembled.tokens() + "  tokensBefore = " + assembled.tokensBefore()
                + "  netTokenChange = " + assembled.netTokenChange());
        System.out.println("text   = " + show(assembled.text()));

        // ---- Stage 9: validation + recovery (the last implemented stage) --------------------------------
        RecoveryController controller = RecoveryController.standard(counter, detector);
        RecoveryOutcome outcome = controller.run(List.of(pd), profile, null);
        banner("9. RecoveryController.run(docs, profile, budget=null)");
        System.out.println("status         = " + outcome.status());
        System.out.println("tokens         = " + outcome.tokens() + " (original " + outcome.originalTokens() + ")");
        System.out.println("restoreRounds  = " + outcome.restoreRounds());
        if (outcome.validation() != null) {
            System.out.println("validation     = " + outcome.validation().summary());
        }
        for (AuditEntry a : outcome.audit()) {
            System.out.println("audit " + a.step() + ": " + a.phase() + " / " + a.plan() + " -> " + a.outcome()
                    + " : " + a.detail());
        }
        outcome.warnings().forEach(w -> System.out.println("warning: " + w));

        banner("FINAL OUTPUT (RecoveryOutcome.text())");
        System.out.println(outcome.deliverable() ? outcome.text() : "(nothing delivered: " + outcome.status() + ")");
    }

    // ---------------------------------------------------------------- helpers

    private static void banner(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    private static void printItem(String label, ExtractedItem it) {
        System.out.println(label + " " + it.id() + " kind=" + it.kind() + " label=" + it.label()
                + " [" + it.source().start() + "," + it.source().end() + ")"
                + " rule=" + it.provenance().rule() + " basis=" + it.provenance().basis()
                + " confidence=" + it.confidence());
        System.out.println("    text = " + show(it.text()));
    }

    private static void printUnits(CanonicalDocument doc, List<InformationUnit> units) {
        for (InformationUnit u : units) {
            System.out.println(u.id() + " ordinal=" + u.ordinal() + " [" + u.start() + "," + u.end() + ")"
                    + " type=" + u.type() + " tokens=" + u.tokens());
            System.out.println("    slice = " + show(u.slice(doc)));
        }
    }

    private static String show(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }
}
