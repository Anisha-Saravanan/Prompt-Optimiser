package org.promptoptimizer.segment;

import java.util.ArrayList;
import java.util.List;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.UnitType;
import org.promptoptimizer.token.TokenCounter;

/**
 * Splits text into paragraph units (content followed by its trailing blank lines). Fenced code
 * blocks (lines starting with three backticks) are never split, even across blank lines. An
 * unterminated fence extends to the end of the document. Units are typed UNKNOWN; typed
 * segmentation is a later milestone.
 */
public final class ParagraphSegmenter implements Segmenter {

    @Override
    public List<InformationUnit> segment(CanonicalDocument doc, TokenCounter counter) {
        String text = doc.text();
        List<InformationUnit> units = new ArrayList<>();
        if (text.isEmpty()) {
            return units;
        }
        int unitStart = 0;
        boolean sawContent = false;
        boolean sawBlankAfterContent = false;
        boolean inFence = false;
        int pos = 0;
        while (pos < text.length()) {
            int nl = text.indexOf('\n', pos);
            int lineEnd = nl < 0 ? text.length() : nl;
            int next = nl < 0 ? text.length() : nl + 1;
            String line = text.substring(pos, lineEnd);
            boolean blank = line.isBlank();

            if (!blank && sawBlankAfterContent && !inFence) {
                units.add(unit(doc, units.size(), unitStart, pos, counter));
                unitStart = pos;
                sawContent = false;
                sawBlankAfterContent = false;
            }
            if (blank) {
                if (sawContent && !inFence) {
                    sawBlankAfterContent = true;
                }
            } else {
                sawContent = true;
                if (line.stripLeading().startsWith("```")) {
                    inFence = !inFence;
                }
            }
            pos = next;
        }
        units.add(unit(doc, units.size(), unitStart, text.length(), counter));
        return units;
    }

    private static InformationUnit unit(CanonicalDocument doc, int ordinal, int start, int end, TokenCounter counter) {
        int tokens = counter.count(doc.text().substring(start, end)).tokens();
        return new InformationUnit(doc.id(), ordinal, start, end, UnitType.UNKNOWN, tokens);
    }
}
