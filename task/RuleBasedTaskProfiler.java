package org.promptoptimizer.task;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.task.ProfileWarning.Code;
import org.promptoptimizer.task.ProfileWarning.Severity;
import org.promptoptimizer.task.Provenance.Basis;

/**
 * Deterministic, rule-based {@link TaskProfiler} (blueprint S2, rules-only MVP).
 *
 * <p>Method: (1) fenced code and block quotes are set aside; text inside inline quotes/backticks is
 * masked so cue words in it are not read as instructions; (2) labelled sections (TASK:, REQUIREMENTS:,
 * CONSTRAINTS:, OUTPUT FORMAT:, plus CONTEXT-like sections that are skipped) are extracted as
 * EXPLICIT_LABEL items; (3) remaining prose and unlabelled list items are classified by lexical cues;
 * (4) if a labelled section of a kind exists, unlabelled cue sentences of that kind are kept as
 * {@code candidates} with a warning instead of being promoted; (5) exact repeats (after
 * normalization) are merged and recorded.
 *
 * <p>Limitations: English only; lexical cues, no parsing; the task statement is the labelled TASK
 * section or else the first imperative-looking prose sentence; indented (non-fenced) code is not
 * recognised; conflicting instructions are not detected; a sentence may legitimately be both the
 * task and a constraint/output item.
 */
public final class RuleBasedTaskProfiler implements TaskProfiler {

    public static final String VERSION = "rules-0.1";

    private enum SectionKind { TASK, REQUIREMENT, CONSTRAINT, OUTPUT, CONTEXT }

    private static final Map<String, SectionKind> LABELS = Map.ofEntries(
            Map.entry("task", SectionKind.TASK), Map.entry("your task", SectionKind.TASK),
            Map.entry("the task", SectionKind.TASK), Map.entry("goal", SectionKind.TASK),
            Map.entry("objective", SectionKind.TASK), Map.entry("request", SectionKind.TASK),
            Map.entry("requirements", SectionKind.REQUIREMENT), Map.entry("requirement", SectionKind.REQUIREMENT),
            Map.entry("deliverables", SectionKind.REQUIREMENT), Map.entry("deliverable", SectionKind.REQUIREMENT),
            Map.entry("acceptance criteria", SectionKind.REQUIREMENT),
            Map.entry("constraints", SectionKind.CONSTRAINT), Map.entry("constraint", SectionKind.CONSTRAINT),
            Map.entry("rules", SectionKind.CONSTRAINT), Map.entry("restrictions", SectionKind.CONSTRAINT),
            Map.entry("restriction", SectionKind.CONSTRAINT), Map.entry("limitations", SectionKind.CONSTRAINT),
            Map.entry("output", SectionKind.OUTPUT), Map.entry("output format", SectionKind.OUTPUT),
            Map.entry("format", SectionKind.OUTPUT), Map.entry("response format", SectionKind.OUTPUT),
            Map.entry("answer format", SectionKind.OUTPUT), Map.entry("expected output", SectionKind.OUTPUT),
            Map.entry("context", SectionKind.CONTEXT), Map.entry("background", SectionKind.CONTEXT),
            Map.entry("input", SectionKind.CONTEXT), Map.entry("data", SectionKind.CONTEXT),
            Map.entry("example", SectionKind.CONTEXT), Map.entry("examples", SectionKind.CONTEXT),
            Map.entry("code", SectionKind.CONTEXT), Map.entry("log", SectionKind.CONTEXT),
            Map.entry("logs", SectionKind.CONTEXT));

    private static final Pattern HEADER_COLON = Pattern.compile(
            "^[ \\t]{0,3}(?:#{1,6}[ \\t]+)?(?:\\*\\*|__)?([A-Za-z][A-Za-z ]{1,24}?)[ \\t]*(?:\\*\\*|__)?[ \\t]*:[ \\t]*(?:\\*\\*|__)?[ \\t]*(.*)$");
    private static final Pattern HEADER_HEADING = Pattern.compile(
            "^[ \\t]{0,3}#{1,6}[ \\t]+(?:\\*\\*|__)?([A-Za-z][A-Za-z ]{1,24}?)[ \\t]*(?:\\*\\*|__)?[ \\t]*:?[ \\t]*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^([ \\t]*)(?:[-*+\\u2022]|\\d{1,3}[.)])[ \\t]+(\\S.*)$");

    private static final Pattern NEG = Pattern.compile(
            "\\b(?:do not|don't|does not|doesn't|must not|mustn't|should not|shouldn't|shall not|never|cannot|can't|can not"
                    + "|avoid|refrain from|not allowed|forbidden|prohibited|without|no (?:external|additional|extra|new|other))\\b");
    private static final Pattern LIMIT = Pattern.compile(
            "\\b(?:at most|at least|no more than|no less than|no fewer than|exactly|up to \\d+|under \\d+|fewer than|less than"
                    + "|more than|within \\d+|maximum|minimum|limited to|only)\\b");
    private static final Pattern PRESERVE = Pattern.compile(
            "\\b(?:preserve|retain|maintain|backward[- ]compatible|compatible with|compatibility|unchanged|intact|as[- ]is)\\b"
                    + "|\\bkeep\\b[^.!?]{0,40}\\b(?:compatible|unchanged|intact|same|existing)\\b");
    private static final Pattern OBLIGATION = Pattern.compile(
            "\\b(?:must|should|shall|need to|needs to|have to|has to|required to|is required|are required|make sure|ensure|be sure to)\\b");
    private static final String FORMAT_WORDS =
            "(?:json|yaml|xml|csv|markdown|html|plain text|bullet(?:ed)? (?:points?|lists?)|bullets|numbered list|table|code block|headings?|step[- ]by[- ]step)";
    private static final Pattern OUTPUT_VERB_FORMAT = Pattern.compile(
            "\\b(?:respond|reply|answer|output|return|provide|present|produce|give|display|format(?:ted)?|structure)\\b[^.!?]{0,60}?\\b(?:in|as|using|into|with)\\b[^.!?]{0,20}?\\b"
                    + FORMAT_WORDS + "\\b");
    private static final Pattern OUTPUT_VERB_DIRECT = Pattern.compile(
            "\\b(?:respond|reply|answer|output|return)\\b[^.!?]{0,30}?\\b" + FORMAT_WORDS + "\\b");
    private static final Pattern OUTPUT_PHRASE = Pattern.compile(
            "\\b(?:output format|response format|answer format|format (?:your|the) (?:answer|response|output)|in the form of)\\b");
    private static final Pattern OUTPUT_LENGTH = Pattern.compile(
            "\\b(?:in|within|under|at most|no more than|up to|exactly)\\s+(?:about |around )?\\d+\\s+(?:words|sentences|paragraphs|bullets|lines|characters)\\b");

    private static final Pattern LEAD_FILLER = Pattern.compile(
            "^(?:(?:also|then|next|finally|first|and|so|now|ok|okay|hi|hello|hey)\\b[,!.]?\\s+)+");
    private static final Pattern LEAD_POLITE = Pattern.compile(
            "^(?:(?:please|kindly)\\b,?\\s+|(?:can|could|would|will) you\\s+(?:please\\s+|kindly\\s+)?"
                    + "|i (?:need|want|would like|'d like) you to\\s+|your task is to\\s+)+");
    private static final Set<String> VERBS = Set.of(
            "write", "create", "build", "implement", "generate", "debug", "fix", "find", "identify", "explain",
            "summarize", "summarise", "translate", "convert", "refactor", "review", "analyze", "analyse", "list",
            "describe", "compare", "draft", "edit", "rewrite", "optimize", "optimise", "classify", "extract",
            "calculate", "compute", "produce", "design", "suggest", "recommend", "answer", "check", "test",
            "document", "parse", "provide", "give", "make", "develop", "add", "remove", "update", "improve",
            "evaluate", "outline", "plan", "show", "tell", "help", "handle", "include");
    private static final Set<String> VAGUE_VERBS = Set.of("help", "improve", "handle");
    private static final Set<String> ABBREVIATIONS = Set.of("e.g", "i.e", "vs", "mr", "mrs", "ms", "dr", "fig", "approx");

    /** True if {@code label} (case-insensitive, trimmed) is a section label this profiler recognises. */
    public static boolean isSectionLabel(String label) {
        return label != null && LABELS.containsKey(label.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public TaskProfile profile(CanonicalDocument doc) {
        return new Scan(doc).run();
    }

    // ------------------------------------------------------------------ classification

    private record Cls(boolean constraint, String constraintLabel, boolean obligation, boolean output, boolean imperative) {
        boolean any() {
            return constraint || obligation || output;
        }
    }

    private static Cls classify(String eff) {
        String low = eff.toLowerCase(Locale.ROOT).trim();
        boolean question = low.endsWith("?");
        boolean neg = NEG.matcher(low).find();
        boolean limit = LIMIT.matcher(low).find();
        boolean pres = PRESERVE.matcher(low).find();
        boolean constraint = neg || limit || pres;
        String label = neg ? "prohibition" : limit ? "limit" : pres ? "preservation" : "restriction";
        boolean obligation = !constraint && !question && OBLIGATION.matcher(low).find();
        boolean output = OUTPUT_VERB_FORMAT.matcher(low).find() || OUTPUT_VERB_DIRECT.matcher(low).find()
                || OUTPUT_PHRASE.matcher(low).find() || OUTPUT_LENGTH.matcher(low).find();
        return new Cls(constraint, label, obligation, output, imperativeStart(low));
    }

    private static boolean imperativeStart(String low) {
        String s = LEAD_FILLER.matcher(low).replaceFirst("");
        s = LEAD_POLITE.matcher(s).replaceFirst("");
        int end = 0;
        while (end < s.length() && Character.isLetter(s.charAt(end))) {
            end++;
        }
        return end > 0 && VERBS.contains(s.substring(0, end));
    }

    private static String normalize(String text) {
        String s = text.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        s = s.replaceFirst("^\\s*(?:[-*+\\u2022]|\\d{1,3}[.)])\\s+", "");
        s = s.replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        return s.replaceFirst("^(?:please|also) ", "");
    }

    private static boolean anyCue(String text) {
        return classify(text).any();
    }

    // ------------------------------------------------------------------ scan

    private static final class Draft {
        final ItemKind kind;
        final String rule;
        int start;
        int end;
        Draft(ItemKind kind, String rule, int start, int end) {
            this.kind = kind;
            this.rule = rule;
            this.start = start;
            this.end = end;
        }
    }

    /** Unlabelled sentence or list item awaiting resolution. */
    private record Candidate(int start, int end, Cls cls, boolean listItem) { }

    private static final class Section {
        final SectionKind kind;
        int count;
        int blanks;
        Section(SectionKind kind) {
            this.kind = kind;
        }
    }

    private final class Scan {
        final CanonicalDocument doc;
        final String text;
        final String eff;
        final List<int[]> lines = new ArrayList<>();
        final List<Draft> labelled = new ArrayList<>();
        final List<Draft> extraTasks = new ArrayList<>();
        final List<Candidate> free = new ArrayList<>();
        final List<ProfileWarning> warnings = new ArrayList<>();

        Section section;
        Draft taskDraft;
        Draft lastDraft;          // labelled list item open for continuation lines
        int[] pendingFree;        // unlabelled list item {start, end} open for continuation
        int paraStart = -1;
        int paraEnd = -1;
        int regionStart = -1;
        int regionEnd = -1;
        boolean prevWasListLine;

        Scan(CanonicalDocument doc) {
            this.doc = doc;
            this.text = doc.text();
            int pos = 0;
            StringBuilder e = new StringBuilder(text.length());
            while (pos <= text.length()) {
                int nl = text.indexOf('\n', pos);
                int end = nl < 0 ? text.length() : nl;
                lines.add(new int[] {pos, end});
                e.append(mask(text.substring(pos, end)));
                if (nl < 0) {
                    break;
                }
                e.append('\n');
                pos = nl + 1;
            }
            this.eff = e.toString();
        }

        /** Masks quoted/backticked content on one line, preserving length (offsets stay aligned). */
        private String mask(String line) {
            char[] c = line.toCharArray();
            for (int i = 0; i < c.length; i++) {
                char ch = c[i];
                if (ch == '\u2019') {
                    c[i] = '\'';
                } else if (ch == '"' || ch == '\u201C' || ch == '`') {
                    char close = ch == '\u201C' ? '\u201D' : ch;
                    int j = i + 1;
                    while (j < c.length && c[j] != close) {
                        j++;
                    }
                    if (j < c.length) {
                        char before = c[j - 1];
                        for (int k = i; k <= j; k++) {
                            c[k] = ' ';
                        }
                        if (j - 1 > i && (before == '.' || before == '!' || before == '?') && startsNewSentence(c, j + 1)) {
                            c[j] = before; // keep sentence end visible after a quoted ending
                        }
                        i = j;
                    }
                }
            }
            return new String(c);
        }

        /** True if, after position {@code from}, the line ends or the next word starts uppercase/digit. */
        private boolean startsNewSentence(char[] c, int from) {
            int k = from;
            while (k < c.length && (c[k] == ' ' || c[k] == '\t')) {
                k++;
            }
            return k >= c.length || Character.isUpperCase(c[k]) || Character.isDigit(c[k]);
        }

        TaskProfile run() {
            if (text.isBlank()) {
                warnings.add(new ProfileWarning(Code.EMPTY_INPUT, Severity.WARN, "prompt is empty or blank", null));
                return new TaskProfile(doc, null, List.of(), List.of(), List.of(), List.of(), warnings,
                        Confidence.LOW, VERSION);
            }
            scanLines();
            return resolve();
        }

        // -------------------------------------------------------------- line scan

        void scanLines() {
            boolean inFence = false;
            String fenceMarker = null;
            boolean inQuote = false;
            for (int[] ln : lines) {
                int ls = ln[0];
                int le = ln[1];
                String raw = text.substring(ls, le);
                String trimmed = raw.strip();

                if (inFence) {
                    extendRegion(ls, le);
                    if (trimmed.startsWith(fenceMarker)) {
                        inFence = false;
                        closeRegion();
                    }
                    continue;
                }
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    flushParagraph();
                    flushPendingFree();
                    closeRegion();
                    lastDraft = null;
                    inFence = true;
                    fenceMarker = trimmed.substring(0, 3);
                    extendRegion(ls, le);
                    continue;
                }
                if (trimmed.startsWith(">")) {
                    flushParagraph();
                    flushPendingFree();
                    lastDraft = null;
                    inQuote = true;
                    extendRegion(ls, le);
                    continue;
                }
                if (inQuote) {
                    inQuote = false;
                    closeRegion();
                }

                if (trimmed.isEmpty()) {
                    flushParagraph();
                    flushPendingFree();
                    lastDraft = null;
                    prevWasListLine = false;
                    if (section != null) {
                        section.blanks++;
                        if (section.count > 0 || section.blanks >= 2) {
                            closeSection();
                        }
                    }
                    continue;
                }

                // header?
                int[] rest = new int[2];
                SectionKind hk = matchHeader(raw, ls, rest);
                if (hk != null) {
                    flushParagraph();
                    flushPendingFree();
                    lastDraft = null;
                    closeSection();
                    section = new Section(hk);
                    taskDraft = null;
                    prevWasListLine = false;
                    if (rest[0] >= 0) {
                        contentProse(hk, rest[0], rest[1]);
                    }
                    continue;
                }

                // list item?
                Matcher lm = LIST_ITEM.matcher(raw);
                if (lm.matches()) {
                    int cs = ls + lm.start(2);
                    int ce = ls + raw.stripTrailing().length();
                    flushParagraph();
                    flushPendingFree();
                    if (section != null && section.kind != SectionKind.CONTEXT) {
                        contentListItem(cs, ce);
                    } else if (section == null) {
                        pendingFree = new int[] {cs, ce};
                    }
                    prevWasListLine = true;
                    continue;
                }

                boolean indented = raw.charAt(0) == ' ' || raw.charAt(0) == '\t';
                int ce = ls + raw.stripTrailing().length();
                int cs = ls + (raw.length() - raw.stripLeading().length());
                // continuation of an open list item
                if (indented && lastDraft != null) {
                    lastDraft.end = ce;
                    continue;
                }
                if (indented && pendingFree != null) {
                    pendingFree[1] = ce;
                    continue;
                }
                if (section != null && section.count > 0 && prevWasListLine && !indented
                        && section.kind != SectionKind.TASK) {
                    closeSection(); // unindented prose right after a list ends the section
                }
                prevWasListLine = false;
                flushPendingFree();
                lastDraft = null;
                if (section != null) {
                    if (section.kind != SectionKind.CONTEXT) {
                        contentProse(section.kind, cs, ce);
                    }
                    continue;
                }
                if (paraStart < 0) {
                    paraStart = cs;
                }
                paraEnd = ce;
            }
            flushParagraph();
            flushPendingFree();
            closeRegion();
            closeSection();
        }

        SectionKind matchHeader(String raw, int ls, int[] rest) {
            rest[0] = -1;
            Matcher m = HEADER_COLON.matcher(raw);
            if (m.matches()) {
                SectionKind k = LABELS.get(m.group(1).trim().toLowerCase(Locale.ROOT));
                if (k != null) {
                    String g = m.group(2);
                    if (!g.isBlank()) {
                        rest[0] = ls + m.start(2);
                        rest[1] = ls + m.start(2) + g.stripTrailing().length();
                    }
                    return k;
                }
            }
            m = HEADER_HEADING.matcher(raw);
            if (m.matches()) {
                return LABELS.get(m.group(1).trim().toLowerCase(Locale.ROOT));
            }
            return null;
        }

        void contentListItem(int cs, int ce) {
            switch (section.kind) {
                case TASK -> extendTask(cs, ce);
                case REQUIREMENT -> lastDraft = addLabelled(ItemKind.REQUIREMENT, "label:requirements#list-item", cs, ce);
                case CONSTRAINT -> lastDraft = addLabelled(ItemKind.CONSTRAINT, "label:constraints#list-item", cs, ce);
                case OUTPUT -> lastDraft = addLabelled(ItemKind.OUTPUT_EXPECTATION, "label:output#list-item", cs, ce);
                default -> { }
            }
        }

        void contentProse(SectionKind kind, int cs, int ce) {
            switch (kind) {
                case TASK -> extendTask(cs, ce);
                case REQUIREMENT -> splitSentences(cs, ce, ItemKind.REQUIREMENT, "label:requirements#sentence");
                case CONSTRAINT -> splitSentences(cs, ce, ItemKind.CONSTRAINT, "label:constraints#sentence");
                case OUTPUT -> splitSentences(cs, ce, ItemKind.OUTPUT_EXPECTATION, "label:output#sentence");
                default -> { }
            }
        }

        void extendTask(int cs, int ce) {
            if (taskDraft == null) {
                taskDraft = new Draft(ItemKind.TASK, "label:task", cs, ce);
                if (labelled.stream().anyMatch(d -> d.kind == ItemKind.TASK)) {
                    extraTasks.add(taskDraft);
                } else {
                    labelled.add(taskDraft);
                }
                section.count++;
            } else {
                taskDraft.end = ce;
            }
        }

        Draft addLabelled(ItemKind kind, String rule, int cs, int ce) {
            Draft d = new Draft(kind, rule, cs, ce);
            labelled.add(d);
            section.count++;
            return d;
        }

        void splitSentences(int a, int b, ItemKind kind, String rule) {
            for (int[] s : sentenceBounds(a, b)) {
                if (eff.substring(s[0], s[1]).isBlank()) {
                    noteQuotedCue(s[0], s[1]);
                    continue;
                }
                addLabelled(kind, rule, s[0], s[1]);
            }
        }

        void closeSection() {
            if (section != null && section.count == 0 && section.kind != SectionKind.CONTEXT) {
                warnings.add(new ProfileWarning(Code.EMPTY_SECTION, Severity.WARN,
                        section.kind + " section has a label but no content", null));
            }
            section = null;
            taskDraft = null;
            lastDraft = null;
            prevWasListLine = false;
        }

        // -------------------------------------------------------------- unlabelled content

        void flushPendingFree() {
            if (pendingFree != null) {
                int s = pendingFree[0];
                int e = pendingFree[1];
                pendingFree = null;
                addFreeCandidate(s, e, true);
            }
        }

        void flushParagraph() {
            if (paraStart >= 0) {
                int a = paraStart;
                int b = paraEnd;
                paraStart = -1;
                paraEnd = -1;
                for (int[] s : sentenceBounds(a, b)) {
                    addFreeCandidate(s[0], s[1], false);
                }
            }
        }

        void addFreeCandidate(int s, int e, boolean listItem) {
            String masked = eff.substring(s, e);
            if (masked.isBlank()) {
                noteQuotedCue(s, e);
                return;
            }
            noteQuotedCue(s, e);
            Cls cls = classify(masked);
            if (!cls.any() && !cls.imperative()) {
                return;
            }
            free.add(new Candidate(s, e, cls, listItem));
        }

        void noteQuotedCue(int s, int e) {
            if (anyCue(text.substring(s, e)) && !anyCue(eff.substring(s, e))) {
                warnings.add(new ProfileWarning(Code.CUE_IN_CODE_OR_QUOTE, Severity.INFO,
                        "instruction-like words appear only inside quoted text; not treated as an instruction",
                        new SourceRef(doc.id(), s, e)));
            }
        }

        void extendRegion(int s, int e) {
            if (regionStart < 0) {
                regionStart = s;
            }
            regionEnd = e;
        }

        void closeRegion() {
            if (regionStart >= 0 && regionEnd > regionStart) {
                if (anyCue(text.substring(regionStart, regionEnd))) {
                    warnings.add(new ProfileWarning(Code.CUE_IN_CODE_OR_QUOTE, Severity.INFO,
                            "instruction-like words appear in a code block or block quote; not treated as instructions",
                            new SourceRef(doc.id(), regionStart, regionEnd)));
                }
            }
            regionStart = -1;
            regionEnd = -1;
        }

        /** Sentence ranges within [a,b), trimmed, boundaries decided on masked text. */
        List<int[]> sentenceBounds(int a, int b) {
            List<int[]> out = new ArrayList<>();
            int s = a;
            int i = a;
            while (i < b) {
                char ch = eff.charAt(i);
                if (ch == '.' || ch == '!' || ch == '?') {
                    int j = i;
                    while (j + 1 < b && ".!?".indexOf(eff.charAt(j + 1)) >= 0) {
                        j++;
                    }
                    int k = j + 1;
                    while (k < b && ")]\"\u201D'*_`".indexOf(eff.charAt(k)) >= 0) {
                        k++;
                    }
                    boolean boundary = k >= b || Character.isWhitespace(eff.charAt(k));
                    if (boundary && ch == '.' && j == i && isAbbreviation(i, a)) {
                        boundary = false;
                    }
                    if (boundary) {
                        addTrimmed(out, s, k);
                        s = k;
                    }
                    i = k;
                } else {
                    i++;
                }
            }
            addTrimmed(out, s, b);
            return out;
        }

        boolean isAbbreviation(int dot, int floor) {
            int w = dot;
            while (w > floor && !Character.isWhitespace(eff.charAt(w - 1))) {
                w--;
            }
            String word = eff.substring(w, dot).toLowerCase(Locale.ROOT).replaceAll("^[(\\[\"']+", "");
            return ABBREVIATIONS.contains(word);
        }

        void addTrimmed(List<int[]> out, int s, int e) {
            while (s < e && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
                e--;
            }
            if (e > s) {
                out.add(new int[] {s, e});
            }
        }

        // -------------------------------------------------------------- resolution

        TaskProfile resolve() {
            List<ItemBuild> tasks = new ArrayList<>();
            List<ItemBuild> reqs = new ArrayList<>();
            List<ItemBuild> cons = new ArrayList<>();
            List<ItemBuild> outs = new ArrayList<>();
            List<ItemBuild> cands = new ArrayList<>();

            for (Draft d : labelled) {
                switch (d.kind) {
                    case TASK -> tasks.add(build(ItemKind.TASK, "task", d.start, d.end, d.rule, Basis.EXPLICIT_LABEL, Confidence.HIGH));
                    case REQUIREMENT -> reqs.add(build(ItemKind.REQUIREMENT, "requirement", d.start, d.end, d.rule, Basis.EXPLICIT_LABEL, Confidence.HIGH));
                    case CONSTRAINT -> cons.add(build(ItemKind.CONSTRAINT, classify(eff.substring(d.start, d.end)).constraintLabel(),
                            d.start, d.end, d.rule, Basis.EXPLICIT_LABEL, Confidence.HIGH));
                    case OUTPUT_EXPECTATION -> outs.add(build(ItemKind.OUTPUT_EXPECTATION, "format", d.start, d.end, d.rule, Basis.EXPLICIT_LABEL, Confidence.HIGH));
                }
            }
            for (Draft extra : extraTasks) {
                warnings.add(new ProfileWarning(Code.MULTIPLE_TASK_CANDIDATES, Severity.WARN,
                        "more than one labelled TASK section; the first is used", new SourceRef(doc.id(), extra.start, extra.end)));
            }
            boolean hasLabelledTask = !tasks.isEmpty();
            boolean hasLabelledReq = !reqs.isEmpty();
            boolean hasLabelledCons = !cons.isEmpty();
            boolean hasLabelledOut = !outs.isEmpty();

            Candidate chosenTask = null;
            if (!hasLabelledTask) {
                for (Candidate c : free) {
                    if (!c.listItem() && c.cls().imperative()) {
                        chosenTask = c;
                        break;
                    }
                }
                if (chosenTask != null) {
                    tasks.add(build(ItemKind.TASK, "task", chosenTask.start(), chosenTask.end(),
                            "free:imperative-start", Basis.CUE_RULE, Confidence.MEDIUM));
                }
            }

            for (Candidate c : free) {
                Cls cls = c.cls();
                boolean isTask = c == chosenTask;
                if (cls.constraint()) {
                    route(cons, cands, hasLabelledCons, ItemKind.CONSTRAINT, cls.constraintLabel(), c, "free:constraint-cue", Basis.CUE_RULE, Confidence.MEDIUM);
                }
                if (cls.obligation()) {
                    route(reqs, cands, hasLabelledReq, ItemKind.REQUIREMENT, "requirement", c, "free:obligation-cue", Basis.CUE_RULE, Confidence.MEDIUM);
                }
                if (cls.output()) {
                    route(outs, cands, hasLabelledOut, ItemKind.OUTPUT_EXPECTATION, "format", c, "free:output-cue", Basis.CUE_RULE, Confidence.MEDIUM);
                }
                if (!isTask && cls.imperative() && !cls.constraint() && !cls.obligation() && !cls.output()) {
                    route(reqs, cands, hasLabelledReq, ItemKind.REQUIREMENT, "requirement", c,
                            c.listItem() ? "list:imperative-inferred" : "free:imperative-inferred",
                            Basis.STRUCTURAL_INFERENCE, Confidence.LOW);
                }
            }

            ExtractedItem task = null;
            if (!tasks.isEmpty()) {
                ItemBuild tb = tasks.get(0);
                task = tb.toItem("T1", List.of());
                int words = eff.substring(tb.start, tb.end).trim().split("\\s+").length;
                String firstWord = eff.substring(tb.start, tb.end).trim().toLowerCase(Locale.ROOT).split("[^a-z']+")[0];
                String stripped = LEAD_POLITE.matcher(LEAD_FILLER.matcher(eff.substring(tb.start, tb.end).trim().toLowerCase(Locale.ROOT)).replaceFirst("")).replaceFirst("");
                String verb = stripped.split("[^a-z]+")[0];
                String lastWord = eff.substring(tb.start, tb.end).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").trim();
                lastWord = lastWord.substring(lastWord.lastIndexOf(' ') + 1);
                boolean pronounObject = words <= 4 && Set.of("this", "it", "that", "these", "those", "them", "here").contains(lastWord);
                if (words <= 2 || pronounObject || VAGUE_VERBS.contains(verb)) {
                    warnings.add(new ProfileWarning(Code.AMBIGUOUS_TASK, Severity.WARN,
                            "task statement is very short or vague (\"" + firstWord + "...\"); required details may be missing",
                            task.source()));
                }
            } else {
                warnings.add(new ProfileWarning(Code.MISSING_TASK, Severity.WARN,
                        "no labelled TASK section and no imperative sentence found", null));
            }

            List<ExtractedItem> reqItems = dedupe(reqs, "R", null);
            List<ExtractedItem> conItems = dedupe(cons, "C", null);
            List<ExtractedItem> outItems = dedupe(outs, "O", null);
            // candidates: merge into an existing item when they repeat it exactly, else keep as candidate
            List<ExtractedItem> candItems = new ArrayList<>();
            List<ExtractedItem> reqFinal = new ArrayList<>(reqItems);
            List<ExtractedItem> conFinal = new ArrayList<>(conItems);
            List<ExtractedItem> outFinal = new ArrayList<>(outItems);
            int x = 0;
            for (ItemBuild cb : cands) {
                List<ExtractedItem> target = switch (cb.kind) {
                    case REQUIREMENT -> reqFinal;
                    case CONSTRAINT -> conFinal;
                    default -> outFinal;
                };
                int idx = indexOfNormalized(target, cb);
                if (idx >= 0) {
                    ExtractedItem old = target.get(idx);
                    List<SourceRef> dups = new ArrayList<>(old.duplicateSources());
                    dups.add(cb.ref());
                    target.set(idx, new ExtractedItem(old.id(), old.kind(), old.label(), old.text(),
                            old.provenance(), old.confidence(), dups));
                    warnings.add(new ProfileWarning(Code.DUPLICATE_ITEM, Severity.INFO,
                            "repeats " + old.id() + " verbatim (after normalization)", cb.ref()));
                } else {
                    x++;
                    candItems.add(cb.toItem("X" + x, List.of()));
                    warnings.add(new ProfileWarning(Code.UNPROMOTED_CANDIDATE, Severity.INFO,
                            "unlabelled " + cb.kind.name().toLowerCase(Locale.ROOT)
                                    + " cue outside the labelled section; kept as candidate X" + x
                                    + " (possible repeat or addition)", cb.ref()));
                }
            }

            Confidence overall;
            boolean warn = warnings.stream().anyMatch(w -> w.severity() == Severity.WARN);
            if (task == null) {
                overall = Confidence.LOW;
            } else if (hasLabelledTask && !warn) {
                overall = Confidence.HIGH;
            } else {
                overall = Confidence.MEDIUM;
            }
            return new TaskProfile(doc, task, reqFinal, conFinal, outFinal, candItems, warnings, overall, VERSION);
        }

        void route(List<ItemBuild> target, List<ItemBuild> cands, boolean hasLabelled, ItemKind kind, String label,
                   Candidate c, String rule, Basis basis, Confidence conf) {
            ItemBuild b = build(kind, label, c.start(), c.end(), rule, basis, conf);
            (hasLabelled ? cands : target).add(b);
        }

        ItemBuild build(ItemKind kind, String label, int s, int e, String rule, Basis basis, Confidence conf) {
            return new ItemBuild(kind, label, s, e, rule, basis, conf);
        }

        List<ExtractedItem> dedupe(List<ItemBuild> builds, String prefix, Object unused) {
            List<ItemBuild> sorted = new ArrayList<>(builds);
            sorted.sort((p, q) -> Integer.compare(p.start, q.start));
            List<ExtractedItem> out = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            List<List<SourceRef>> dups = new ArrayList<>();
            List<ItemBuild> kept = new ArrayList<>();
            for (ItemBuild b : sorted) {
                String key = normalize(text.substring(b.start, b.end));
                int idx = keys.indexOf(key);
                if (idx >= 0) {
                    dups.get(idx).add(b.ref());
                    warnings.add(new ProfileWarning(Code.DUPLICATE_ITEM, Severity.INFO,
                            "repeats " + prefix + (idx + 1) + " verbatim (after normalization)", b.ref()));
                } else {
                    keys.add(key);
                    dups.add(new ArrayList<>());
                    kept.add(b);
                }
            }
            for (int i = 0; i < kept.size(); i++) {
                out.add(kept.get(i).toItem(prefix + (i + 1), dups.get(i)));
            }
            return out;
        }

        int indexOfNormalized(List<ExtractedItem> items, ItemBuild cb) {
            String key = normalize(text.substring(cb.start, cb.end));
            for (int i = 0; i < items.size(); i++) {
                if (normalize(items.get(i).text()).equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        private final class ItemBuild {
            final ItemKind kind;
            final String label;
            final int start;
            final int end;
            final String rule;
            final Basis basis;
            final Confidence conf;

            ItemBuild(ItemKind kind, String label, int start, int end, String rule, Basis basis, Confidence conf) {
                this.kind = kind;
                this.label = label;
                this.start = start;
                this.end = end;
                this.rule = rule;
                this.basis = basis;
                this.conf = conf;
            }

            SourceRef ref() {
                return new SourceRef(doc.id(), start, end);
            }

            ExtractedItem toItem(String id, List<SourceRef> dups) {
                return new ExtractedItem(id, kind, label, text.substring(start, end),
                        new Provenance(ref(), rule, basis), conf, dups);
            }
        }
    }
}
