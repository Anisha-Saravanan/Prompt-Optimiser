package org.promptoptimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.Model.CanonicalDocument;
import org.promptoptimizer.model.Model.InformationUnit;
import org.promptoptimizer.segment.ProfileSegmenter;
import org.promptoptimizer.task.RuleBasedTaskProfiler;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.HeuristicTokenCounter;

class PipelineDemoTest {
    @Test
    void formatsGroupedJsonWithArraysAndNoRepeatedRequirementKeys() {
        String text = "Create a Python library management system. "
                + "Allow librarians to add and remove books. "
                + "Each book must include title, author, ISBN, publication year, and available copies. "
                + "Enable book searches by title, author, or ISBN. "
                + "Allow librarians to add and remove books. "
                + "Use Python without external libraries. Store data persistently. "
                + "Return complete Python source code.";
        CanonicalDocument prompt = new Canonicalizer(100_000).canonicalize("prompt", text);
        TaskProfile profile = new RuleBasedTaskProfiler().profile(prompt);
        List<InformationUnit> units = new ProfileSegmenter(profile).segment(prompt, new HeuristicTokenCounter());

        assertEquals("""
                {
                  "TASK": "Create a Python library management system.",
                  "REQUIREMENTS": [
                    "Allow librarians to add and remove books.",
                    "Each book must include title, author, ISBN, publication year, and available copies.",
                    "Enable book searches by title, author, or ISBN."
                  ],
                  "CONSTRAINTS": [
                    "Use Python without external libraries.",
                    "Store data persistently."
                  ],
                  "OUTPUT FORMAT": "Return complete Python source code."
                }""", PipelineDemo.formatSplit(prompt, units, profile));
    }
}
