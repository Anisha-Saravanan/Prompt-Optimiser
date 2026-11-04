package org.promptoptimizer.task;

/**
 * Ordinal, rule-derived confidence. These are categories, not calibrated probabilities.
 * HIGH: explicit label in the prompt. MEDIUM: lexical cue on an unlabelled sentence.
 * LOW: structural inference or a profile with a missing/ambiguous task.
 */
public enum Confidence {
    LOW, MEDIUM, HIGH
}
