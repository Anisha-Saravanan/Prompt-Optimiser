package org.promptoptimizer.lossless;

import java.util.ArrayList;
import java.util.List;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.UnitType;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.segment.ParagraphSegmenter;
import org.promptoptimizer.token.HeuristicTokenCounter;

/** Shared helpers for lossless/assemble tests (not itself a test class). */
public final class LosslessTestSupport {
    public static final HeuristicTokenCounter COUNTER = new HeuristicTokenCounter();
    /** About 40 heuristic tokens, no digits, quotes or identifiers, so no protected spans. */
    public static final String DUP = "The quarterly report lists revenue growth by region and explains the main drivers of the change in detail.";
    private static final Canonicalizer CANON = new Canonicalizer(10_000_000);

    private LosslessTestSupport() {}

    public static PreparedDocument prepare(String id, String text, DocRole role, ContentFormat format) {
        CanonicalDocument doc = CANON.canonicalize(id, text);
        List<InformationUnit> units = new ParagraphSegmenter().segment(doc, COUNTER);
        return new PreparedDocument(doc, role, format, units, new RuleBasedProtectedSpanDetector().detect(doc));
    }

    public static PreparedDocument prompt(String text) {
        return prepare("prompt", text, DocRole.PROMPT, ContentFormat.PLAIN_TEXT);
    }

    /** Same document with the given unit ordinals retyped. */
    public static PreparedDocument retype(PreparedDocument pd, int ordinal, UnitType type) {
        List<InformationUnit> units = new ArrayList<>();
        for (InformationUnit u : pd.units()) {
            units.add(u.ordinal() == ordinal
                    ? new InformationUnit(u.docId(), u.ordinal(), u.start(), u.end(), type, u.tokens())
                    : u);
        }
        return new PreparedDocument(pd.doc(), pd.role(), pd.format(), units, pd.spans());
    }

    public static LosslessResult run(PreparedDocument... docs) {
        return new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(List.of(docs));
    }
}
