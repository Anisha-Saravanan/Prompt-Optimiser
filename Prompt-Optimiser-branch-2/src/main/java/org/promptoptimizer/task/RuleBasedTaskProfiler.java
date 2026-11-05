package org.promptoptimizer.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.model.Model.CanonicalDocument;
import org.promptoptimizer.task.TaskProfile.Confidence;
import org.promptoptimizer.task.TaskProfile.ExtractedItem;
import org.promptoptimizer.task.TaskProfile.ItemKind;
import org.promptoptimizer.task.TaskProfile.ProfileWarning;
import org.promptoptimizer.task.TaskProfile.ProfileWarning.Code;
import org.promptoptimizer.task.TaskProfile.ProfileWarning.Severity;
import org.promptoptimizer.task.TaskProfile.Provenance;
import org.promptoptimizer.task.TaskProfile.Provenance.Basis;
import org.promptoptimizer.task.TaskProfile.SourceRef;
import org.promptoptimizer.task.TaskProfile.TaskProfiler;

/** Offline, source-grounded task profiling. It never sends prompt text to an external service. */
public final class RuleBasedTaskProfiler implements TaskProfiler {
    public static final String VERSION = "rules-1";

    private static final Pattern SECTION = Pattern.compile(
            "^(?:#{1,6}\\s*)?(?:\\*\\*)?(TASK|YOUR TASK|REQUEST|GOAL|REQUIREMENTS?|ACCEPTANCE CRITERIA|"
                    + "CONSTRAINTS?|RESTRICTIONS?|OUTPUT FORMAT|RESPONSE FORMAT|OUTPUT|DELIVERABLES?)"
                    + "(?:\\*\\*)?\\s*:?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPERATIVE = Pattern.compile(
            "^(?:please\\s+)?(?:write|create|build|debug|fix|explain|summari[sz]e|translate|generate|"
                    + "analy[sz]e|implement|compare|review|optimi[sz]e|convert|answer|solve|refactor|"
                    + "design|find|identify|update|make|develop)\\b.*",
            Pattern.CASE_INSENSITIVE);

    @Override
    public TaskProfile profile(CanonicalDocument prompt) {
        Objects.requireNonNull(prompt);
        if (prompt.text().isEmpty()) {
            return new TaskProfile(prompt, null, List.of(), List.of(), List.of(), List.of(),
                    List.of(new ProfileWarning(Code.EMPTY_INPUT, Severity.INFO, "Prompt is empty.", null)),
                    Confidence.LOW, VERSION);
        }

        List<Section> sections = findSections(prompt.text());
        List<ExtractedItem> requirements = new ArrayList<>();
        List<ExtractedItem> constraints = new ArrayList<>();
        List<ExtractedItem> outputs = new ArrayList<>();
        List<ProfileWarning> warnings = new ArrayList<>();
        ExtractedItem task = null;
        int requirementId = 0;
        int constraintId = 0;
        int outputId = 0;

        for (Section section : sections) {
            int[] range = trim(prompt.text(), section.contentStart(), section.contentEnd());
            if (range[0] == range[1]) {
                warnings.add(new ProfileWarning(Code.EMPTY_SECTION, Severity.WARN,
                        "The " + section.kind().name().toLowerCase(Locale.ROOT) + " section is empty.",
                        new SourceRef(prompt.id(), section.labelStart(), section.labelEnd())));
                continue;
            }
            String text = prompt.text().substring(range[0], range[1]);
            ItemKind kind = section.kind();
            String id;
            String label;
            if (kind == ItemKind.TASK) {
                if (task != null) {
                    warnings.add(new ProfileWarning(Code.MULTIPLE_TASK_CANDIDATES, Severity.WARN,
                            "More than one labelled task section was found; only the first is used for ranking.",
                            new SourceRef(prompt.id(), range[0], range[1])));
                    continue;
                }
                id = "T1";
                label = "task";
            } else if (kind == ItemKind.REQUIREMENT) {
                id = "R" + ++requirementId;
                label = "requirement";
            } else if (kind == ItemKind.CONSTRAINT) {
                id = "C" + ++constraintId;
                label = "restriction";
            } else {
                id = "O" + ++outputId;
                label = "format";
            }

            ExtractedItem item = new ExtractedItem(id, kind, label, text,
                    new Provenance(new SourceRef(prompt.id(), range[0], range[1]),
                            "labelled-section", Basis.EXPLICIT_LABEL),
                    Confidence.HIGH, List.of());
            switch (kind) {
                case TASK -> task = item;
                case REQUIREMENT -> requirements.add(item);
                case CONSTRAINT -> constraints.add(item);
                case OUTPUT_EXPECTATION -> outputs.add(item);
            }
        }

        if (task == null) {
            task = inferTask(prompt);
            if (task != null) {
                warnings.add(new ProfileWarning(Code.AMBIGUOUS_TASK, Severity.INFO,
                        "Task inferred from an imperative sentence; review it before relying on relevance ranking.",
                        task.source()));
            } else {
                warnings.add(new ProfileWarning(Code.MISSING_TASK, Severity.WARN,
                        "No labelled or confidently imperative task statement was found; selection may be disabled.",
                        null));
            }
        }

        Confidence confidence = task == null ? Confidence.LOW
                : task.provenance().basis() == Basis.EXPLICIT_LABEL ? Confidence.HIGH : Confidence.MEDIUM;
        return new TaskProfile(prompt, task, requirements, constraints, outputs, List.of(), warnings,
                confidence, VERSION);
    }

    private static List<Section> findSections(String text) {
        List<Section> sections = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            Matcher matcher = SECTION.matcher(text.substring(lineStart, lineEnd).stripTrailing());
            if (matcher.matches()) {
                ItemKind kind = kind(matcher.group(1));
                int contentStart = lineStart + matcher.start(2);
                while (contentStart < lineEnd && Character.isWhitespace(text.charAt(contentStart))) {
                    contentStart++;
                }
                if (contentStart == lineEnd) {
                    contentStart = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
                }
                sections.add(new Section(kind, lineStart, lineEnd, contentStart, text.length()));
            }
            lineStart = lineEnd + 1;
        }

        List<Section> bounded = new ArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            int end = i + 1 < sections.size() ? sections.get(i + 1).labelStart() : text.length();
            bounded.add(new Section(section.kind(), section.labelStart(), section.labelEnd(),
                    section.contentStart(), end));
        }
        return List.copyOf(bounded);
    }

    private static ItemKind kind(String label) {
        String normalized = label.toUpperCase(Locale.ROOT);
        if (normalized.equals("TASK") || normalized.equals("YOUR TASK")
                || normalized.equals("REQUEST") || normalized.equals("GOAL")) {
            return ItemKind.TASK;
        }
        if (normalized.startsWith("REQUIREMENT") || normalized.equals("ACCEPTANCE CRITERIA")
                || normalized.startsWith("DELIVERABLE")) {
            return ItemKind.REQUIREMENT;
        }
        if (normalized.startsWith("CONSTRAINT") || normalized.startsWith("RESTRICTION")) {
            return ItemKind.CONSTRAINT;
        }
        return ItemKind.OUTPUT_EXPECTATION;
    }

    private static ExtractedItem inferTask(CanonicalDocument prompt) {
        String text = prompt.text();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            int[] range = trim(text, lineStart, lineEnd);
            if (range[0] < range[1]) {
                String line = text.substring(range[0], range[1])
                        .replaceFirst("^(?:[-*+]\\s+|\\d+[.)]\\s+)", "");
                Matcher matcher = IMPERATIVE.matcher(line);
                if (matcher.matches()) {
                    int start = range[0] + text.substring(range[0], range[1]).indexOf(line);
                    int taskEnd = sentenceEnd(line, 0);
                    String taskText = line.substring(0, taskEnd).stripTrailing();
                    return new ExtractedItem("T1", ItemKind.TASK, "task", taskText,
                            new Provenance(new SourceRef(prompt.id(), start, start + taskText.length()),
                                    "imperative-cue", Basis.CUE_RULE),
                            Confidence.MEDIUM, List.of());
                }
            }
            lineStart = lineEnd + 1;
        }
        return null;
    }

    private static int sentenceEnd(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current != '.' && current != '!' && current != '?') {
                continue;
            }
            if (current == '.' && i > 0 && i + 1 < text.length()
                    && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1))) {
                continue;
            }
            int next = i + 1;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (next == text.length() || Character.isUpperCase(text.charAt(next))) {
                return i + 1;
            }
        }
        return text.length();
    }

    private static int[] trim(String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new int[] {start, end};
    }

    private record Section(ItemKind kind, int labelStart, int labelEnd, int contentStart, int contentEnd) {}
}
