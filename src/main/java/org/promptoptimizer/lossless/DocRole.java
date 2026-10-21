package org.promptoptimizer.lossless;

/** Whether a document is the user's prompt or an attached file. Prompt units win duplicate ties. */
public enum DocRole {
    PROMPT, FILE
}
