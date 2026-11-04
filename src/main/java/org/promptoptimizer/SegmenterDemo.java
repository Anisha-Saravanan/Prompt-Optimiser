
package org.promptoptimizer;

import java.util.List;

import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.segment.ParagraphSegmenter;
import org.promptoptimizer.segment.Segmenter;
import org.promptoptimizer.token.HeuristicTokenCounter;
import org.promptoptimizer.token.TokenCounter;

public class SegmenterDemo {

    public static void main(String[] args) {
        // 1. Create the input-processing and segmentation components
        Canonicalizer canonicalizer = new Canonicalizer(100_000);
        Segmenter segmenter = new ParagraphSegmenter();
        TokenCounter counter = new HeuristicTokenCounter();

        // 2. Sample prompt with three paragraphs
        String input = "Hello\r\nworld\r";

        // 3. Canonicalize the input
        CanonicalDocument doc =
                canonicalizer.canonicalize("demo-prompt", input);

        System.out.println("=== CANONICAL DOCUMENT ===");
        System.out.println(doc.text());

        // 4. Split the document into information units
        List<InformationUnit> units = segmenter.segment(doc, counter);

        // 5. Verify that the units cover the document correctly
        Segmenter.verifyPartition(doc, units);

        // 6. Display each unit
        System.out.println("\n=== SEGMENTATION RESULTS ===");
        System.out.println("Document ID: " + doc.id());
        System.out.println("Total units: " + units.size());

        for (InformationUnit unit : units) {
            System.out.println("\nUnit ID: " + unit.id());
            System.out.println("Ordinal: " + unit.ordinal());
            System.out.println("Start offset: " + unit.start());
            System.out.println("End offset: " + unit.end());
            System.out.println("Type: " + unit.type());
            System.out.println("Estimated tokens: " + unit.tokens());
            System.out.println("Unit text: [" + unit.slice(doc) + "]");
        }

        System.out.println("\nPartition verification: PASSED");
    }
}
