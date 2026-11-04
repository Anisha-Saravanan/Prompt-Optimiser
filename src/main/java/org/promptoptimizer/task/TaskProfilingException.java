package org.promptoptimizer.task;

public final class TaskProfilingException extends RuntimeException {
    public TaskProfilingException(String message) {
        super(message);
    }

    public TaskProfilingException(String message, Throwable cause) {
        super(message, cause);
    }
}
