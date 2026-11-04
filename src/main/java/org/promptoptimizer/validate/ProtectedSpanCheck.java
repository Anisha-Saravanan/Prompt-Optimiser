package org.promptoptimizer.validate;

import java.util.List;
import java.util.stream.Collectors;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;

/**
 * Check 2: every HARD span of the original (supplied spans plus a fresh detector run) is byte-identical in
 * the output, or, for a removed duplicate, contained in the surviving unit (blueprint 5.4 duplicate rule).
 */
public final class ProtectedSpanCheck implements Check {
    @Override
    public CheckId id() {
        return CheckId.PROTECTED_SPANS;
    }

    @Override
    public CheckResult run(ValidationContext ctx) {
        List<ProtectedSpan> hard = SpanCarrier.originalSpans(ctx).stream()
                .filter(s -> s.level() == Level.HARD).collect(Collectors.toList());
        return CheckResult.of(id(), SpanCarrier.verify(OutputIndex.build(ctx), hard, id(), FailureClass.SPAN_LOST,
                "HARD span"));
    }
}
