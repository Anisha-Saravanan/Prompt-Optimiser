package org.promptoptimizer.validate;

import java.util.List;
import org.promptoptimizer.token.TokenCount;

/** Check 1: exact recount of the candidate with the context's counter must not exceed the budget. */
public final class BudgetCheck implements Check {
    @Override
    public CheckId id() {
        return CheckId.BUDGET;
    }

    @Override
    public CheckResult run(ValidationContext ctx) {
        if (ctx.budget() == null) {
            return CheckResult.notApplicable(id(), "no budget supplied");
        }
        TokenCount tc = ctx.counter().count(ctx.candidate().text());
        if (tc.tokens() <= ctx.budget()) {
            return CheckResult.of(id(), List.of());
        }
        String msg = "output needs " + tc.tokens() + " tokens under '" + tc.tokenizerId() + "'"
                + (tc.exact() ? "" : " (upper-bound estimate, not the target tokenizer)")
                + " but the budget is " + ctx.budget() + "; deficit " + (tc.tokens() - ctx.budget());
        return CheckResult.of(id(), List.of(new Finding(id(), FailureClass.OVERBUDGET, Finding.Severity.ERROR, msg,
                null, List.of(), null)));
    }
}
