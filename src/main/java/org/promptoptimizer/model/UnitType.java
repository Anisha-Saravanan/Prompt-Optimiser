package org.promptoptimizer.model;

/** Types of information units (blueprint section 5.1). */
public enum UnitType {
    TASK, REQUIREMENT, CONSTRAINT, INPUT_FACT, ERROR_TEXT, CODE, SCHEMA, DOC,
    EXAMPLE, CONTEXT, REPEAT, NOISE, UNKNOWN
}
