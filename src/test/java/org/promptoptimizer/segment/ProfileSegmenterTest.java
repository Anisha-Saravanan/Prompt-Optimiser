package org.promptoptimizer.segment;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.UnitType;
import org.promptoptimizer.task.LlmTaskProfiler;
import org.promptoptimizer.token.HeuristicTokenCounter;

class ProfileSegmenterTest {
    private static final String PROMPT = "Intro.\n\nWrite a report.\n\n"
            + "Include totals.\n\nDo not use external tools.\n\nReturn JSON.";
    private final Canonicalizer canonicalizer = new Canonicalizer(100_000);
    private final HeuristicTokenCounter counter = new HeuristicTokenCounter();

    private List<InformationUnit> segment(CanonicalDocument doc) {
        var profiler = new LlmTaskProfiler((input, instructions) ->
                "{\"items\":["
                        + "{\"kind\":\"TASK\",\"text\":\"Write a report.\"},"
                        + "{\"kind\":\"REQUIREMENT\",\"text\":\"Include totals.\"},"
                        + "{\"kind\":\"CONSTRAINT\",\"text\":\"Do not use external tools.\"},"
                        + "{\"kind\":\"OUTPUT_EXPECTATION\",\"text\":\"Return JSON.\"}]}");
        return new ProfileSegmenter(profiler.profile(doc)).segment(doc, counter);
    }

    @Test
    void segmentsAtModelComponentBoundariesAndKeepsClassifiedTypes() {
        CanonicalDocument doc = canonicalizer.canonicalize("prompt", PROMPT);
        List<InformationUnit> units = segment(doc);

        assertEquals(List.of(UnitType.UNKNOWN, UnitType.TASK, UnitType.UNKNOWN, UnitType.REQUIREMENT,
                        UnitType.UNKNOWN, UnitType.CONSTRAINT, UnitType.UNKNOWN, UnitType.REQUIREMENT),
                units.stream().map(InformationUnit::type).toList());
        assertEquals(0, units.get(0).start());
        assertEquals(doc.text().length(), units.get(units.size() - 1).end());
        assertEquals(doc.text(), units.stream().map(unit -> unit.slice(doc)).reduce("", String::concat));
        assertTrue(units.stream().allMatch(unit ->
                unit.tokens() == counter.count(unit.slice(doc)).tokens()));
        Segmenter.verifyPartition(doc, units);
    }

    @Test
    void emptyProfilePreservesWholeNonemptyDocumentAsUnknown() {
        CanonicalDocument doc = canonicalizer.canonicalize("prompt", "unclassified input");
        var profile = new LlmTaskProfiler((input, instructions) -> "{\"items\":[]}").profile(doc);
        List<InformationUnit> units = new ProfileSegmenter(profile).segment(doc, counter);
        assertEquals(1, units.size());
        assertEquals(UnitType.UNKNOWN, units.get(0).type());
        assertEquals(doc.text(), units.get(0).slice(doc));
    }

    @Test
    void rejectsProfileFromAnotherDocument() {
        CanonicalDocument original = canonicalizer.canonicalize("prompt", "Task text.");
        var profile = new LlmTaskProfiler((input, instructions) -> "{\"items\":[]}").profile(original);
        CanonicalDocument other = canonicalizer.canonicalize("prompt", "Other text.");
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileSegmenter(profile).segment(other, counter));
    }
}
