package org.promptoptimizer.validate;

/** Failure classes of the preservation validator (blueprint section 7.1). Not all can arise yet; see {@link CheckId}. */
public enum FailureClass {
    OVERBUDGET, SPAN_LOST, VALUE_LOST, REQ_MISSING, FACT_LOST, STRUCT_INVALID, DANGLING_REF,
    SEMANTIC_DRIFT, TASK_RISK, PROVENANCE, PROBE_FLAG
}
