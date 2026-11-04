package org.promptoptimizer.token;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HeuristicTokenCounterTest {
    private final HeuristicTokenCounter counter = new HeuristicTokenCounter();

    @Test
    void neverClaimsToBeExactAndReportsItsId() {
        TokenCount c = counter.count("hello world");
        assertFalse(c.exact());
        assertEquals(HeuristicTokenCounter.ID, c.tokenizerId());
    }

    @Test
    void emptyAndWhitespaceCostNothing() {
        assertEquals(0, counter.count("").tokens());
        assertEquals(0, counter.count(" \n\t ").tokens());
    }

    @Test
    void isDeterministicAndMonotonicUnderConcatenation() {
        String a = "public int averageGrade(List<Student> s) { return 0; }";
        String b = "another line with 12345 digits";
        assertEquals(counter.count(a).tokens(), counter.count(a).tokens());
        assertTrue(counter.count(a + " " + b).tokens() >= counter.count(a).tokens());
    }

    @Test
    void longIdentifiersCostMoreThanShortWords() {
        assertTrue(counter.count("averageGradeCalculatorService").tokens() > counter.count("cat").tokens());
    }

    @Test
    void safetyFactorBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HeuristicTokenCounter(0.9));
    }
}
