package org.promptoptimizer.optimize;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Bm25JaccardScorerTest {
    private final Bm25JaccardScorer scorer = new Bm25JaccardScorer();

    @Test
    void taskRelevantUnitRanksAboveUnrelatedText() {
        List<Bm25JaccardScorer.UnitScore> scores = scorer.score(List.of(
                new Bm25JaccardScorer.UnitText("bug", "Fix the NullPointerException in GradeService."),
                new Bm25JaccardScorer.UnitText("notes", "The weekly meeting covers project calendars.")),
                "Fix the NullPointerException in GradeService");

        assertEquals("bug", scores.get(0).unitId());
        assertTrue(scores.get(0).bm25() > 0);
        assertTrue(scores.get(0).jaccard() > scores.get(1).jaccard());
        assertTrue(scores.get(0).combined() > scores.get(1).combined());
    }

    @Test
    void pairwiseJaccardExposesDirectionalCoverageWithoutDeclaringEquivalence() {
        Bm25JaccardScorer.PairScore score = scorer.compare(
                new Bm25JaccardScorer.UnitText("short", "retain the exact file path"),
                new Bm25JaccardScorer.UnitText("long", "retain the exact file path and add a short note"));

        assertTrue(score.leftCoveredByRight() > score.rightCoveredByLeft());
        assertTrue(score.jaccard() > 0 && score.jaccard() < 1);
    }

    @Test
    void nearDuplicateCandidatesAreOnlyLexicalEvidence() {
        List<Bm25JaccardScorer.PairScore> candidates = scorer.nearDuplicateCandidates(List.of(
                new Bm25JaccardScorer.UnitText("a", "retain the file path exactly"),
                new Bm25JaccardScorer.UnitText("b", "retain the file path exactly please"),
                new Bm25JaccardScorer.UnitText("c", "write a release summary")), 0.6);

        assertEquals(1, candidates.size());
        assertEquals("a", candidates.get(0).leftUnitId());
        assertEquals("b", candidates.get(0).rightUnitId());
    }

    @Test
    void equalScoresHaveStableUnitIdOrderAndEmptyQueriesDoNotInventRelevance() {
        List<Bm25JaccardScorer.UnitScore> scores = scorer.score(List.of(
                new Bm25JaccardScorer.UnitText("z", "alpha"),
                new Bm25JaccardScorer.UnitText("a", "beta")), "");

        assertEquals(List.of("a", "z"), scores.stream().map(Bm25JaccardScorer.UnitScore::unitId).toList());
        assertTrue(scores.stream().allMatch(score -> score.combined() == 0));
    }

    @Test
    void rejectsDuplicateIdsAndInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> scorer.score(List.of(
                new Bm25JaccardScorer.UnitText("same", "one"),
                new Bm25JaccardScorer.UnitText("same", "two")), "one"));
        assertThrows(IllegalArgumentException.class, () -> new Bm25JaccardScorer.Config(1.2, 1.1, 1, 0));
    }
}
