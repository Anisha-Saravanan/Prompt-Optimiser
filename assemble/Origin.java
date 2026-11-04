package org.promptoptimizer.assemble;

/**
 * Where an output character came from: a range of the original canonical text of {@code docId}.
 * For unchanged characters the range is one character; for characters produced by an edit it is the
 * edited range; for an omission marker it is the whole removed unit.
 */
public record Origin(String docId, int start, int end, SegmentKind kind) {}
