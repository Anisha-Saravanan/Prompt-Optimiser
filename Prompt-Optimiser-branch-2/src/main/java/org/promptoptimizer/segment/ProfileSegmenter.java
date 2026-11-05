package org.promptoptimizer.segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.model.Model.CanonicalDocument;
import org.promptoptimizer.model.Model.InformationUnit;
import org.promptoptimizer.model.Model.UnitType;
import org.promptoptimizer.task.TaskProfile.ExtractedItem;
import org.promptoptimizer.task.TaskProfile.ItemKind;
import org.promptoptimizer.task.TaskProfile.SourceRef;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.TokenCounter;

/**
 * Splits paragraph units at profiled component and sentence boundaries. Unclassified text remains
 * conservatively typed unless a sentence contains a clear requirement, constraint, or output cue.
 */
public final class ProfileSegmenter implements Segmenter {
    private static final Pattern LIST_MARKER = Pattern.compile("(?:[-*+\\u2022]|\\d{1,3}[.)])");
    private static final Pattern SECTION_LABEL = Pattern.compile(
            "(?:#{1,6}\\s*)?(?:\\*\\*|__)?[A-Za-z][A-Za-z ]{1,24}(?:\\*\\*|__)?\\s*:?");
    private static final Pattern LANG_TAG = Pattern.compile("^[A-Za-z0-9_+#.\\-]+$");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s|$)");
    private static final Pattern CONSTRAINT_CUE = Pattern.compile(
            "(?i)\\b(?:must not|do not|don't|never|avoid|without external (?:libraries|tools|dependencies)|"
                    + "without third.party (?:libraries|frameworks)|use built.in|using built.in|"
                    + "keep (?:the |existing )?(?:implementation|api|signatures)|preserve|maintain|"
                    + "persistently|persistent(?:ly)?|remain available after restarting)\\b");
    private static final Pattern OUTPUT_CUE = Pattern.compile(
            "(?i)\\b(?:return|output|provide)\\b.*\\b(?:code|source|format|report|json|markdown)\\b");
    private static final Pattern REQUIREMENT_CUE = Pattern.compile(
            "(?i)\\b(?:should|must|allow|support|record|store|search|borrow|update|identify|notify|"
                    + "provide|validate|ensure|include|display|show)\\b");

    private final TaskProfile profile;

    public ProfileSegmenter(TaskProfile profile) {
        this.profile = Objects.requireNonNull(profile);
    }

    @Override
    public List<InformationUnit> segment(CanonicalDocument doc, TokenCounter counter) {
        Objects.requireNonNull(doc);
        Objects.requireNonNull(counter);
        if (!profile.prompt().id().equals(doc.id()) || !profile.prompt().text().equals(doc.text())) {
            throw new IllegalArgumentException("task profile was built from a different document than '" + doc.id() + "'");
        }
        if (doc.text().isEmpty()) {
            return List.of();
        }

        List<ExtractedItem> items = allItems();
        List<InformationUnit> paragraphs = new ParagraphSegmenter().segment(doc, counter);
        List<InformationUnit> units = new ArrayList<>();
        for (InformationUnit paragraph : paragraphs) {
            segmentParagraph(doc, paragraph, items, counter, units);
        }
        Segmenter.verifyPartition(doc, units);
        return List.copyOf(units);
    }

    private void segmentParagraph(CanonicalDocument doc, InformationUnit paragraph, List<ExtractedItem> items,
                                 TokenCounter counter, List<InformationUnit> result) {
        List<ExtractedItem> overlapping = items.stream()
                .filter(item -> overlaps(item.source(), paragraph))
                .sorted(Comparator.comparingInt(item -> item.source().start()))
                .toList();
        if (overlapping.isEmpty()) {
            UnitType type = isTaggedFence(paragraph.slice(doc)) ? UnitType.CODE : UnitType.UNKNOWN;
            addUnit(result, doc, paragraph.start(), paragraph.end(), type, counter);
            return;
        }

        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(paragraph.start());
        boundaries.add(paragraph.end());
        for (ExtractedItem item : overlapping) {
            boundaries.add(Math.max(paragraph.start(), item.source().start()));
            boundaries.add(Math.min(paragraph.end(), item.source().end()));
        }

        List<Integer> points = new ArrayList<>(boundaries);
        List<RangeType> ranges = new ArrayList<>();
        UnitType onlyKind = onlyMappedKind(overlapping);
        for (int i = 0; i + 1 < points.size(); i++) {
            int start = points.get(i);
            int end = points.get(i + 1);
            UnitType type = typeForRange(start, end, overlapping);
            if (type == UnitType.UNKNOWN && onlyKind != UnitType.UNKNOWN
                    && isScaffolding(doc.text().substring(start, end))) {
                type = onlyKind;
            }
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1).type() == type
                    && ranges.get(ranges.size() - 1).end() == start) {
                RangeType previous = ranges.remove(ranges.size() - 1);
                ranges.add(new RangeType(previous.start(), end, type));
            } else {
                ranges.add(new RangeType(start, end, type));
            }
        }
        for (RangeType range : ranges) {
            addSentenceUnits(result, doc, range.start(), range.end(), range.type(), counter);
        }
    }

    private static void addSentenceUnits(List<InformationUnit> result, CanonicalDocument doc, int start, int end,
                                        UnitType type, TokenCounter counter) {
        String text = doc.text();
        int partStart = start;
        Matcher matcher = SENTENCE_END.matcher(text);
        matcher.region(start, end);
        while (matcher.find()) {
            int sentenceEnd = matcher.end();
            int nextStart = sentenceEnd;
            while (nextStart < end && Character.isWhitespace(text.charAt(nextStart))) {
                nextStart++;
            }
            if (nextStart < end && (Character.isUpperCase(text.charAt(nextStart))
                    || text.charAt(nextStart) == '"' || text.charAt(nextStart) == '\'')) {
                addSentenceUnit(result, doc, partStart, nextStart, type, counter);
                partStart = nextStart;
            }
        }
        addSentenceUnit(result, doc, partStart, end, type, counter);
    }

    private static void addSentenceUnit(List<InformationUnit> result, CanonicalDocument doc, int start, int end,
                                        UnitType type, TokenCounter counter) {
        UnitType classifiedType = type == UnitType.UNKNOWN
                ? inferType(doc.text().substring(start, end)) : type;
        addUnit(result, doc, start, end, classifiedType, counter);
    }

    private static UnitType inferType(String text) {
        String sentence = text.strip();
        if (CONSTRAINT_CUE.matcher(sentence).find()) {
            return UnitType.CONSTRAINT;
        }
        if (OUTPUT_CUE.matcher(sentence).find()) {
            return UnitType.REQUIREMENT;
        }
        if (REQUIREMENT_CUE.matcher(sentence).find()) {
            return UnitType.REQUIREMENT;
        }
        return UnitType.UNKNOWN;
    }

    private List<ExtractedItem> allItems() {
        List<ExtractedItem> items = new ArrayList<>();
        profile.taskStatement().ifPresent(items::add);
        items.addAll(profile.requirements());
        items.addAll(profile.constraints());
        items.addAll(profile.outputs());
        items.addAll(profile.candidates());
        items.sort(Comparator.comparingInt(item -> item.source().start()));
        return items;
    }

    private static UnitType typeForRange(int start, int end, List<ExtractedItem> items) {
        UnitType type = null;
        for (ExtractedItem item : items) {
            SourceRef source = item.source();
            if (source.start() <= start && source.end() >= end) {
                UnitType itemType = item.id().startsWith("X") ? UnitType.UNKNOWN : map(item.kind());
                if (type == null) {
                    type = itemType;
                } else if (type != itemType) {
                    return UnitType.UNKNOWN;
                }
            }
        }
        return type == null ? UnitType.UNKNOWN : type;
    }

    private static UnitType onlyMappedKind(List<ExtractedItem> items) {
        UnitType result = null;
        for (ExtractedItem item : items) {
            UnitType type = item.id().startsWith("X") ? UnitType.UNKNOWN : map(item.kind());
            if (type == UnitType.UNKNOWN) {
                return UnitType.UNKNOWN;
            }
            if (result == null) {
                result = type;
            } else if (result != type) {
                return UnitType.UNKNOWN;
            }
        }
        return result == null ? UnitType.UNKNOWN : result;
    }

    private static UnitType map(ItemKind kind) {
        return switch (kind) {
            case TASK -> UnitType.TASK;
            case REQUIREMENT, OUTPUT_EXPECTATION -> UnitType.REQUIREMENT;
            case CONSTRAINT -> UnitType.CONSTRAINT;
        };
    }

    private static boolean overlaps(SourceRef source, InformationUnit unit) {
        return source.start() < unit.end() && unit.start() < source.end();
    }

 private static boolean isScaffolding(String text) {
    for (String line : text.split("\n", -1)) {
        String value = line.replaceAll("\\s+", " ").strip();
        if (value.isEmpty() || LIST_MARKER.matcher(value).matches()) {
            continue;
        }
        if (!SECTION_LABEL.matcher(value).matches()) {
            return false;
        }
    }
    return true;
}
    private static boolean isTaggedFence(String unitText) {
        String[] lines = unitText.split("\n", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isBlank()) {
            last--;
        }
        if (first >= last) {
            return false;
        }
        String open = lines[first].strip();
        String marker = open.startsWith("```") ? "```" : open.startsWith("~~~") ? "~~~" : null;
        if (marker == null || !LANG_TAG.matcher(open.substring(marker.length()).strip()).matches()) {
            return false;
        }
        for (int i = first + 1; i <= last; i++) {
            if (lines[i].strip().startsWith(marker)) {
                return i == last && lines[i].strip().equals(marker);
            }
        }
        return false;
    }

    private static void addUnit(List<InformationUnit> units, CanonicalDocument doc, int start, int end,
                                UnitType type, TokenCounter counter) {
        if (start < end) {
            int tokens = counter.count(doc.text().substring(start, end)).tokens();
            units.add(new InformationUnit(doc.id(), units.size(), start, end, type, tokens));
        }
    }

    private record RangeType(int start, int end, UnitType type) {}
}
