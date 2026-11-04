package org.promptoptimizer.validate;

import static org.promptoptimizer.lossless.LosslessTestSupport.COUNTER;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.agents.ClassificationAgent;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.assemble.ProvenanceSegment;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.lossless.ContentFormat;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.segment.ParagraphSegmenter;
import org.promptoptimizer.segment.ProfileSegmenter;
import org.promptoptimizer.task.LlmTaskProfiler;
import org.promptoptimizer.task.TaskProfile;

/** Helpers for validator/recovery tests (not itself a test class). */
public final class ValidationTestSupport {
    private static final Canonicalizer CANON = new Canonicalizer(1 << 20);
    private static final Pattern LABELED_SECTION =
            Pattern.compile("(?im)^[ \\t]*(TASK|REQUIREMENTS?|CONSTRAINTS?|OUTPUT(?: FORMAT)?):[ \\t]*");
    private static final Pattern LIST_ITEM =
            Pattern.compile("(?m)^[ \\t]*(?:[-*+\\u2022]|\\d{1,3}[.)])[ \\t]+");
    public static final PreservationValidator VALIDATOR = PreservationValidator.standard();

    /** Blueprint section 10.1 example (an invented example, not evidence), with noise and a repeated paragraph. */
    public static final String BLUEPRINT_PROMPT = """
            Hi! Thanks so much in advance. I'm a second-year student working on a grade management
            project for my Data Structures course.   \s


            TASK: Please debug the attached project. Find all compilation errors and logical errors,
            explain the root cause of each, and suggest minimal fixes.

            REQUIREMENTS:
            1. Identify compilation errors.
            2. Identify logical errors.
            3. Explain the root cause of each problem.
            4. Suggest minimal fixes.

            CONSTRAINTS:
            - Do not change the public method signatures of StudentService.
            - Do not rewrite modules that already work.
            - Keep the code compatible with Java 17.



            The report printer prints a header row followed by one line per student and then a footer that summarises everything.

            The report printer prints a header row followed by one line per student and then a footer that summarises everything.
            """;

    private ValidationTestSupport() {}

    /** A prompt document with its profile-typed units and detected spans. */
    public record Fixture(List<PreparedDocument> docs, TaskProfile profile) {
        public PreparedDocument prompt() {
            return docs.stream().filter(d -> d.role() == DocRole.PROMPT).findFirst().orElseThrow();
        }
    }

    public static Fixture fixture(String promptText, PreparedDocument... files) {
        CanonicalDocument doc = CANON.canonicalize("prompt", promptText);
        ClassificationAgent fixtureAgent = (text, instructions) -> fixtureResponse(text);
        TaskProfile profile = new LlmTaskProfiler(fixtureAgent).profile(doc);
        List<InformationUnit> units = new ProfileSegmenter(profile).segment(doc, COUNTER);
        PreparedDocument prompt = new PreparedDocument(doc, DocRole.PROMPT, ContentFormat.PLAIN_TEXT, units,
                new RuleBasedProtectedSpanDetector().detect(doc));
        List<PreparedDocument> docs = new ArrayList<>(List.of(prompt));
        docs.addAll(List.of(files));
        return new Fixture(docs, profile);
    }

    public static PreparedDocument file(String id, String text, ContentFormat format) {
        CanonicalDocument doc = CANON.canonicalize(id, text);
        List<InformationUnit> units = new ParagraphSegmenter().segment(doc, COUNTER);
        return new PreparedDocument(doc, DocRole.FILE, format, units, new RuleBasedProtectedSpanDetector().detect(doc));
    }

    private static String fixtureResponse(String prompt) {
        Matcher matcher = LABELED_SECTION.matcher(prompt);
        List<Integer> sectionStarts = new ArrayList<>();
        List<Integer> contentStarts = new ArrayList<>();
        List<String> sectionKinds = new ArrayList<>();
        while (matcher.find()) {
            sectionStarts.add(matcher.start());
            contentStarts.add(matcher.end());
            sectionKinds.add(matcher.group(1));
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < sectionStarts.size(); i++) {
            int start = contentStarts.get(i);
            int end = i + 1 < sectionStarts.size() ? sectionStarts.get(i + 1) : prompt.length();
            int paragraphEnd = prompt.indexOf("\n\n", start);
            if (paragraphEnd >= 0 && paragraphEnd < end) {
                end = paragraphEnd;
            }
            while (start < end && Character.isWhitespace(prompt.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(prompt.charAt(end - 1))) {
                end--;
            }
            if (start < end) {
                String kind = switch (sectionKinds.get(i).toUpperCase(java.util.Locale.ROOT)) {
                    case "TASK" -> "TASK";
                    case "CONSTRAINT", "CONSTRAINTS" -> "CONSTRAINT";
                    case "OUTPUT", "OUTPUT FORMAT" -> "OUTPUT_EXPECTATION";
                    default -> "REQUIREMENT";
                };
                String section = prompt.substring(start, end);
                if (kind.equals("REQUIREMENT") || kind.equals("CONSTRAINT")) {
                    addListItems(items, kind, section);
                } else {
                    addItem(items, kind, section);
                }
            }
        }
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static void addListItems(List<String> items, String kind, String section) {
        Matcher listItemMatcher = LIST_ITEM.matcher(section);
        List<Integer> itemStarts = new ArrayList<>();
        List<Integer> contentStarts = new ArrayList<>();
        while (listItemMatcher.find()) {
            itemStarts.add(listItemMatcher.start());
            contentStarts.add(listItemMatcher.end());
        }
        if (itemStarts.isEmpty()) {
            addItem(items, kind, section);
            return;
        }
        for (int i = 0; i < itemStarts.size(); i++) {
            int start = contentStarts.get(i);
            int end = i + 1 < itemStarts.size() ? itemStarts.get(i + 1) : section.length();
            while (end > start && Character.isWhitespace(section.charAt(end - 1))) {
                end--;
            }
            if (start < end) {
                addItem(items, kind, section.substring(start, end));
            }
        }
    }

    private static void addItem(List<String> items, String kind, String text) {
        items.add("{\"kind\":\"" + kind + "\",\"text\":\"" + escapeJson(text) + "\"}");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public static LosslessResult lossless(List<PreparedDocument> docs) {
        return new LosslessPass(COUNTER, LosslessPass.Config.defaults()).run(docs);
    }

    public static AssembledInput assemble(List<PreparedDocument> docs) {
        return new Assembler(COUNTER).assemble(lossless(docs));
    }

    public static ValidationContext ctx(Fixture f, Integer budget, AssembledInput candidate) {
        return new ValidationContext(f.docs(), f.profile(), budget, COUNTER, new RuleBasedProtectedSpanDetector(), candidate);
    }

    public static ValidationResult validate(Fixture f, Integer budget, AssembledInput candidate) {
        return VALIDATOR.validate(ctx(f, budget, candidate));
    }

    public static List<Finding> errorsOf(ValidationResult r, FailureClass cls) {
        return r.errors().stream().filter(x -> x.failureClass() == cls).toList();
    }

    /** Replaces the single occurrence of {@code target} in the output and keeps segments tiling and the token count honest. */
    public static AssembledInput replaceInOutput(AssembledInput in, String target, String replacement) {
        int idx = in.text().indexOf(target);
        if (idx < 0 || in.text().indexOf(target, idx + 1) >= 0) {
            throw new IllegalArgumentException("target must occur exactly once: " + target);
        }
        String text = in.text().substring(0, idx) + replacement + in.text().substring(idx + target.length());
        int delta = replacement.length() - target.length();
        List<ProvenanceSegment> segs = new ArrayList<>();
        for (ProvenanceSegment s : in.segments()) {
            int start = s.outStart();
            int end = s.outEnd();
            if (start >= idx + target.length()) {
                start += delta;
                end += delta;
            } else if (end > idx) {
                end += delta;
            }
            if (end > start) {
                segs.add(new ProvenanceSegment(start, end, s.kind(), s.docId(), s.origStart(), s.origEnd(), s.script()));
            }
        }
        return new AssembledInput(text, COUNTER.count(text).tokens(), in.scaffoldTokens(), in.tokensBefore(),
                in.tokenizerId(), segs, in.records());
    }

    public static AssembledInput withSegments(AssembledInput in, List<ProvenanceSegment> segs) {
        return new AssembledInput(in.text(), in.tokens(), in.scaffoldTokens(), in.tokensBefore(), in.tokenizerId(), segs,
                in.records());
    }

    /** Swaps the output text and positions of two adjacent segments (segment i and i+1). */
    public static AssembledInput swapAdjacent(AssembledInput in, int i) {
        ProvenanceSegment a = in.segments().get(i);
        ProvenanceSegment b = in.segments().get(i + 1);
        String ta = in.text().substring(a.outStart(), a.outEnd());
        String tb = in.text().substring(b.outStart(), b.outEnd());
        String text = in.text().substring(0, a.outStart()) + tb + ta + in.text().substring(b.outEnd());
        List<ProvenanceSegment> segs = new ArrayList<>(in.segments());
        segs.set(i, new ProvenanceSegment(a.outStart(), a.outStart() + tb.length(), b.kind(), b.docId(), b.origStart(),
                b.origEnd(), b.script()));
        segs.set(i + 1, new ProvenanceSegment(a.outStart() + tb.length(), b.outEnd(), a.kind(), a.docId(), a.origStart(),
                a.origEnd(), a.script()));
        return new AssembledInput(text, COUNTER.count(text).tokens(), in.scaffoldTokens(), in.tokensBefore(),
                in.tokenizerId(), segs, in.records());
    }

    /** Emits segment i twice in a row (text and segment). */
    public static AssembledInput duplicateSegment(AssembledInput in, int i) {
        ProvenanceSegment s = in.segments().get(i);
        String piece = in.text().substring(s.outStart(), s.outEnd());
        String text = in.text().substring(0, s.outEnd()) + piece + in.text().substring(s.outEnd());
        List<ProvenanceSegment> segs = new ArrayList<>();
        for (int k = 0; k < in.segments().size(); k++) {
            ProvenanceSegment t = in.segments().get(k);
            if (k <= i) {
                segs.add(t);
                if (k == i) {
                    segs.add(new ProvenanceSegment(s.outEnd(), s.outEnd() + piece.length(), s.kind(), s.docId(),
                            s.origStart(), s.origEnd(), s.script()));
                }
            } else {
                segs.add(new ProvenanceSegment(t.outStart() + piece.length(), t.outEnd() + piece.length(), t.kind(),
                        t.docId(), t.origStart(), t.origEnd(), t.script()));
            }
        }
        return new AssembledInput(text, COUNTER.count(text).tokens(), in.scaffoldTokens(), in.tokensBefore(),
                in.tokenizerId(), segs, in.records());
    }
}
