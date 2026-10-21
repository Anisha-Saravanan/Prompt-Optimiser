package org.promptoptimizer.recover;

import static org.junit.jupiter.api.Assertions.*;
import static org.promptoptimizer.lossless.LosslessTestSupport.COUNTER;
import static org.promptoptimizer.validate.ValidationTestSupport.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.lossless.ContentFormat;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.task.ExtractedItem;

/** Seeded randomized check of the invariants every recovery outcome must satisfy, whatever route it took. */
class RecoveryPropertyTest {
    private static final String[] WORDS = {"report", "table", "summary", "customer", "review", "module", "student", "service",
            "check", "build", "nightly", "schedule", "column", "answer", "format", "grade", "parser", "printer"};
    private static final String[] TAILS = {"", " ", "   ", "\t"};

    private static String sentence(Random r, int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            sb.append(i == 0 ? "" : " ").append(WORDS[r.nextInt(WORDS.length)]);
        }
        return sb.append('.').toString();
    }

    private static String paragraph(Random r) {
        StringBuilder sb = new StringBuilder();
        int n = 1 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : " ").append(sentence(r, 5 + r.nextInt(8)));
        }
        return sb.toString();
    }

    private static String prompt(Random r) {
        StringBuilder sb = new StringBuilder("TASK: Please write a ").append(WORDS[r.nextInt(WORDS.length)]).append(TAILS[r.nextInt(4)]).append("\n\n");
        if (r.nextBoolean()) {
            sb.append("REQUIREMENTS:\n");
            int n = 1 + r.nextInt(3);
            for (int i = 1; i <= n; i++) {
                sb.append("- Return the ").append(WORDS[r.nextInt(WORDS.length)]).append(TAILS[r.nextInt(4)]).append('\n');
                if (r.nextInt(3) == 0) {
                    sb.append("  with ").append(WORDS[r.nextInt(WORDS.length)]).append(TAILS[r.nextInt(4)]).append('\n');
                }
            }
            sb.append('\n');
        }
        if (r.nextBoolean()) {
            sb.append("CONSTRAINTS:\n- Do not change the ").append(WORDS[r.nextInt(WORDS.length)]).append(".\n\n");
        }
        String dup = paragraph(r);
        int copies = 1 + r.nextInt(3);
        for (int i = 0; i < copies; i++) {
            sb.append(dup).append(i + 1 < copies ? "\n\n" : "\n");
            if (r.nextInt(3) == 0) {
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }

    @Test
    void invariantsHoldForRandomInputsAndBudgets() {
        Random r = new Random(20261017L);
        RecoveryController controller = RecoveryController.standard(COUNTER, new RuleBasedProtectedSpanDetector());
        int[] byStatus = new int[RecoveryStatus.values().length];
        for (int iter = 0; iter < 300; iter++) {
            List<PreparedDocument> files = new ArrayList<>();
            int nf = r.nextInt(3);
            for (int i = 0; i < nf; i++) {
                // one third of the duplicates are sized like the real "larger than the original" case
                String dup = r.nextInt(3) == 0 ? sentence(r, 16 + r.nextInt(8)) : paragraph(r);
                files.add(file("f" + i + ".md", dup + "\n\n" + paragraph(r) + "\n\n" + dup + (r.nextBoolean() ? "\n" : ""),
                        ContentFormat.MARKDOWN));
            }
            Fixture f = fixture(prompt(r), files.toArray(new PreparedDocument[0]));
            RecoveryOutcome free = controller.run(f.docs(), f.profile(), null);
            int original = free.originalTokens();
            // budgets cluster around the size of the first candidate so both sides of the boundary are exercised
            Integer budget = r.nextInt(4) == 0 ? null
                    : free.deliverable() ? Math.max(0, free.tokens() - 3 + r.nextInt(9)) : r.nextInt(original + 6);
            RecoveryOutcome out = budget == null ? free : controller.run(f.docs(), f.profile(), budget);
            byStatus[out.status().ordinal()]++;
            String ctx = "iter " + iter + " budget=" + budget + " status=" + out.status() + " audit=" + out.audit();

            assertFalse(out.audit().isEmpty(), ctx);
            assertTrue(out.restoreRounds() <= 2, ctx);
            if (out.status() == RecoveryStatus.INFEASIBLE_BUDGET) {
                assertNull(out.text(), ctx);
                assertNotNull(budget, ctx);
                assertTrue(original > budget, "infeasible only if the original does not fit: " + ctx);
                Infeasibility inf = out.infeasibility();
                assertTrue(inf.deficit() > 0 && inf.deficit() == inf.minimumTokens() - budget, ctx);
                assertTrue(inf.minimumTokens() <= original, "M never exceeds the original: " + ctx);
                continue;
            }
            // delivered: size, budget, validity, reconstructability, audit, and content
            assertTrue(out.deliverable(), ctx);
            assertTrue(out.tokens() <= original, "never larger than the original: " + ctx);
            assertEquals(COUNTER.count(out.text()).tokens(), out.tokens(), ctx);
            if (budget != null) {
                assertTrue(out.tokens() <= budget, "within budget: " + ctx);
            }
            assertEquals(f.docs().stream().mapToInt(d -> d.units().size()).sum(), out.records().size(), ctx);
            assertTrue(out.records().stream().allMatch(x -> x.action() == Action.KEPT || x.action() == Action.TRANSFORMED
                    || x.action() == Action.MERGED || x.action() == Action.RESTORED), ctx);
            if (out.status() == RecoveryStatus.FALLBACK_ORIGINAL) {
                assertEquals(OriginalDelivery.text(f.docs()), out.text(), ctx);
                assertNull(out.validation(), ctx);
            } else {
                assertTrue(out.validation().mandatoryPass(), ctx + " " + out.validation().summary());
                for (PreparedDocument d : f.docs()) {
                    assertEquals(d.doc().text(), out.result().reconstruct(d.doc().id()), ctx);
                }
                out.result().verify();
            }
            // every extracted task/requirement/constraint item is still present verbatim
            List<ExtractedItem> items = new ArrayList<>();
            f.profile().taskStatement().ifPresent(items::add);
            items.addAll(f.profile().requirements());
            items.addAll(f.profile().constraints());
            for (ExtractedItem item : items) {
                assertTrue(out.text().contains(item.text()), "item lost: '" + item.text() + "' " + ctx);
            }
        }
        // the generator must actually exercise more than one route
        int routes = 0;
        for (int c : byStatus) {
            routes += c > 0 ? 1 : 0;
        }
        assertTrue(routes >= 4, "only " + routes + " distinct statuses were exercised: " + java.util.Arrays.toString(byStatus));
        assertTrue(byStatus[RecoveryStatus.ACCEPT.ordinal()] > 0, "no ACCEPT case");
        assertTrue(byStatus[RecoveryStatus.INFEASIBLE_BUDGET.ordinal()] > 0, "no INFEASIBLE_BUDGET case");
        assertTrue(byStatus[RecoveryStatus.FALLBACK_LESS_LOSSY.ordinal()] > 0, "no FALLBACK_LESS_LOSSY case (larger-than-original route)");
    }

    @Test
    void theSameInputGivesTheSameOutcome() {
        Random r = new Random(7);
        RecoveryController c = RecoveryController.standard(COUNTER, new RuleBasedProtectedSpanDetector());
        Fixture f = fixture(prompt(r), file("f.md", paragraph(r) + "\n\n" + paragraph(r) + "\n", ContentFormat.MARKDOWN));
        RecoveryOutcome a = c.run(f.docs(), f.profile(), null);
        RecoveryOutcome b = c.run(f.docs(), f.profile(), null);
        assertEquals(a.status(), b.status());
        assertEquals(a.text(), b.text());
        assertEquals(a.audit(), b.audit());
        assertEquals(a.records(), b.records());
    }
}
