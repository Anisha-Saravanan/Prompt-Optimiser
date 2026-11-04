package org.promptoptimizer.validate;

/** The eleven checks of blueprint section 7.1, numbered as in the blueprint. */
public enum CheckId {
    BUDGET(1, "Token-budget compliance"),
    PROTECTED_SPANS(2, "Protected-span preservation"),
    EXACT_VALUES(3, "Exact-value preservation"),
    REQUIREMENTS(4, "Requirement and constraint recall"),
    IMPORTANT_FACTS(5, "Important-fact preservation"),
    STRUCTURE(6, "Structural validity"),
    CLOSURE(7, "Reference and dependency closure"),
    SEMANTIC(8, "Semantic consistency"),
    TASK_RISK(9, "Task-specific risk checks"),
    PROVENANCE(10, "Provenance"),
    PROBE(11, "Reconstruction probe");

    private final int number;
    private final String title;

    CheckId(int number, String title) {
        this.number = number;
        this.title = title;
    }

    public int number() {
        return number;
    }

    public String title() {
        return title;
    }
}
