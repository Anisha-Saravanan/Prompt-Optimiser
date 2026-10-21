package org.promptoptimizer.model;

import java.util.Objects;

/**
 * Canonical text of one input (prompt or file): UTF-8 text with LF line endings.
 * The original byte hash is retained so the source stays addressable.
 *
 * @param id             stable id (file name or "prompt")
 * @param text           canonical text
 * @param originalSha256 SHA-256 of the original bytes before normalization
 * @param crlfNormalized true if CRLF/CR line endings were converted to LF (recorded normalization)
 */
public record CanonicalDocument(String id, String text, String originalSha256, boolean crlfNormalized) {
    public CanonicalDocument {
        Objects.requireNonNull(id);
        Objects.requireNonNull(text);
        Objects.requireNonNull(originalSha256);
    }
}
