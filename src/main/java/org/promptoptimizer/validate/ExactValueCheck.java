package org.promptoptimizer.validate;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.model.SpanKind;

/**
 * Check 3: numbers, identifiers, paths, error strings and quoted strings re-extracted from the original
 * must appear unchanged, whatever their protection level (HARD or SOFT). Blueprint 7.1 says "from
 * ESSENTIAL and hard-keep units"; no importance model exists yet, so every unit is treated as essential.
 */
public final class ExactValueCheck implements Check {
    static final Set<SpanKind> VALUE_KINDS = EnumSet.of(SpanKind.NUMBER_WITH_UNIT, SpanKind.IDENTIFIER,
            SpanKind.PATH_OR_URL, SpanKind.ERROR_TEXT, SpanKind.QUOTED_STRING);

    @Override
    public CheckId id() {
        return CheckId.EXACT_VALUES;
    }

    @Override
    public CheckResult run(ValidationContext ctx) {
        List<ProtectedSpan> values = SpanCarrier.originalSpans(ctx).stream()
                .filter(s -> VALUE_KINDS.contains(s.kind())).collect(Collectors.toList());
        return CheckResult.of(id(), SpanCarrier.verify(OutputIndex.build(ctx), values, id(), FailureClass.VALUE_LOST,
                "exact value"));
    }
}
