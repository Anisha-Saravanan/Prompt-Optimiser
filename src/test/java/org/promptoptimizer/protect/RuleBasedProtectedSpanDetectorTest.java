package org.promptoptimizer.protect;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.model.SpanKind;

class RuleBasedProtectedSpanDetectorTest {
    private final Canonicalizer canon = new Canonicalizer(1_000_000);
    private final RuleBasedProtectedSpanDetector detector = new RuleBasedProtectedSpanDetector();

    private CanonicalDocument doc(String text) {
        return canon.canonicalize("d", text);
    }

    private static boolean covers(CanonicalDocument d, List<ProtectedSpan> spans, String needle) {
        int i = d.text().indexOf(needle);
        assertTrue(i >= 0, "test bug: needle not in text: " + needle);
        int e = i + needle.length();
        return spans.stream().anyMatch(s -> s.start() <= i && s.end() >= e);
    }

    @Test
    void protectsConstraintSentencesIncludingNegation() {
        CanonicalDocument d = doc("Please help.\nDo not change the public method signatures of StudentService.\nThanks!");
        List<ProtectedSpan> spans = detector.detect(d);
        assertTrue(covers(d, spans, "Do not change the public method signatures of StudentService."));
        assertFalse(covers(d, spans, "Please help."));
    }

    @Test
    void protectsNumbersUnitsAndVersions() {
        CanonicalDocument d = doc("Keep latency under 250 ms and memory below 2 GB, on Java 17, ratio 85.5, version 1.2.3.");
        List<ProtectedSpan> spans = detector.detect(d);
        for (String n : List.of("250 ms", "2 GB", "17", "85.5", "1.2.3")) {
            assertTrue(covers(d, spans, n), n);
        }
    }

    @Test
    void protectsIdentifiersPathsUrlsAndQuotes() {
        CanonicalDocument d = doc(
                "Call averageGrade in StudentService via student_repo at src/main/java/app/StudentService.java "
                        + "see https://example.com/docs and \"exact string\" or `cmd --flag`.");
        List<ProtectedSpan> spans = detector.detect(d);
        for (String n : List.of("averageGrade", "StudentService", "student_repo",
                "src/main/java/app/StudentService.java", "https://example.com/docs", "\"exact string\"", "`cmd --flag`")) {
            assertTrue(covers(d, spans, n), n);
        }
    }

    @Test
    void protectsWholeErrorLinesAndStackFrames() {
        String err = "[ERROR] /src/main/java/app/StudentService.java:[14,9] cannot find symbol";
        String frame = "    at app.StudentService.averageGrade(StudentService.java:60)";
        String exc = "java.lang.NullPointerException: boom";
        CanonicalDocument d = doc("log:\n" + err + "\n" + frame + "\n" + exc + "\nok done");
        List<ProtectedSpan> spans = detector.detect(d);
        assertTrue(covers(d, spans, err));
        assertTrue(covers(d, spans, frame.strip()));
        assertTrue(covers(d, spans, exc));
        assertFalse(covers(d, spans, "ok done"));
    }

    @Test
    void ordinaryProseIsNotProtected() {
        CanonicalDocument d = doc("Hi there, I am a student working on a course project and I am stuck.");
        assertTrue(detector.detect(d).isEmpty());
    }

    @Test
    void resultIsSortedNonOverlappingAndAllHard() {
        CanonicalDocument d = doc("You must not use ArrayList in StudentService.java at most 3 times.\n"
                + "[ERROR] Foo.java:[1,2] cannot find symbol\n\"quoted 5 things\"");
        List<ProtectedSpan> spans = detector.detect(d);
        assertFalse(spans.isEmpty());
        for (int i = 0; i < spans.size(); i++) {
            assertEquals(Level.HARD, spans.get(i).level());
            if (i > 0) {
                assertTrue(spans.get(i - 1).end() <= spans.get(i).start(), "overlap at " + i);
            }
        }
    }

    @Test
    void mergeUnionsOverlapsAndKeepsLongestKind() {
        List<ProtectedSpan> merged = RuleBasedProtectedSpanDetector.merge(List.of(
                new ProtectedSpan("d", 0, 10, SpanKind.CONSTRAINT_SENTENCE, Level.HARD, "a"),
                new ProtectedSpan("d", 4, 6, SpanKind.NEGATION_OR_MODAL, Level.HARD, "b"),
                new ProtectedSpan("d", 8, 14, SpanKind.IDENTIFIER, Level.SOFT, "c"),
                new ProtectedSpan("d", 20, 22, SpanKind.NUMBER_WITH_UNIT, Level.SOFT, "d")));
        assertEquals(2, merged.size());
        assertEquals(0, merged.get(0).start());
        assertEquals(14, merged.get(0).end());
        assertEquals(Level.HARD, merged.get(0).level());
        assertEquals(SpanKind.CONSTRAINT_SENTENCE, merged.get(0).kind());
        assertEquals(Level.SOFT, merged.get(1).level());
    }

    @Test
    void detectionIsDeterministic() {
        CanonicalDocument d = doc("Never exceed 10 MB. See `a.b` and Foo.java.");
        assertEquals(detector.detect(d), detector.detect(d));
    }
}
