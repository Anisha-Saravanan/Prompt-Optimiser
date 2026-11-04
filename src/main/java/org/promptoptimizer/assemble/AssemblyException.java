package org.promptoptimizer.assemble;

/** The inputs cannot be assembled unambiguously (for example, text collides with the delimiters). */
public class AssemblyException extends RuntimeException {
    public AssemblyException(String message) {
        super(message);
    }
}
