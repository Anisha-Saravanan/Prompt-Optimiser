package org.promptoptimizer.protect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.model.SpanKind;

/**
 * Deterministic regex/lexicon detector (blueprint section 5.4). It deliberately favours recall:
 * over-protection costs tokens, under-protection can change the answer. Known limits: it cannot
 * recognise constraints phrased without a cue word, and it does not parse code (AST-based
 * identifier detection is a later milestone).
 */
public final class RuleBasedProtectedSpanDetector implements ProtectedSpanDetector {

    public static final String VERSION = "rules-v1";

    private static final Pattern CONSTRAINT_CUE = Pattern.compile(
            "\\b(?:must|shall|do\\s+not|don't|dont|never|only|except|unless|at\\s+least|at\\s+most|exactly"
                    + "|no\\s+more\\s+than|without|confidential|secret|must\\s+not|should\\s+not|cannot|can't)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A sentence-like segment: text up to terminal punctuation or end of line. */
    private static final Pattern SEGMENT = Pattern.compile("[^.!?\\n]+(?:[.!?](?=\\s|$))?");

    private static final Pattern NEGATION_MODAL = Pattern.compile(
            "\\b(?:not|no|never|none|neither|nor|only|except|unless|must|shall|should|at\\s+least|at\\s+most"
                    + "|exactly|more\\s+than|less\\s+than)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.])\\d+(?:[.,]\\d+)*(?:\\s?(?:%|ms|ns|us|KB|MB|GB|TB|kg|km|cm|mm|USD|EUR|px))?(?![\\w])");

    private static final Pattern CAMEL = Pattern.compile("\\b[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+\\b");
    private static final Pattern PASCAL = Pattern.compile("\\b(?:[A-Z][a-z0-9]+){2,}\\b");
    private static final Pattern SNAKE = Pattern.compile("\\b[a-z0-9]+(?:_[a-z0-9]+)+\\b");
    private static final Pattern UPPER_SNAKE = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");
    private static final Pattern QUALIFIED = Pattern.compile("\\b[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*){2,}\\b");

    private static final Pattern ERROR_LINE = Pattern.compile(
            "(?m)^.*(?:\\b[A-Z][A-Za-z0-9_]*(?:Exception|Error)\\b|^\\s*at\\s+[\\w.$<>]+\\(.*\\)\\s*$"
                    + "|\\[ERROR\\]|\\bFAILED\\b|cannot find symbol|\\bexpected:<).*$");

    private static final Pattern URL = Pattern.compile("https?://[^\\s)>\\]\"']+");
    private static final Pattern PATH = Pattern.compile("(?<![\\w/.-])(?:[\\w.-]+/)+[\\w.-]+\\.[A-Za-z0-9]{1,5}\\b");

    private static final Pattern QUOTED = Pattern.compile("\"[^\"\\n]{1,200}\"|`[^`\\n]+`");

    @Override
    public List<ProtectedSpan> detect(CanonicalDocument doc) {
        String text = doc.text();
        List<ProtectedSpan> raw = new ArrayList<>();

        // Constraint sentences, and negation/modal words inside them.
        Matcher seg = SEGMENT.matcher(text);
        while (seg.find()) {
            int s = seg.start();
            int e = seg.end();
            while (s < e && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
                e--;
            }
            if (e <= s) {
                continue;
            }
            String sentence = text.substring(s, e);
            if (CONSTRAINT_CUE.matcher(sentence).find()) {
                add(raw, doc, s, e, SpanKind.CONSTRAINT_SENTENCE, "constraint-cue");
                Matcher nm = NEGATION_MODAL.matcher(sentence);
                while (nm.find()) {
                    add(raw, doc, s + nm.start(), s + nm.end(), SpanKind.NEGATION_OR_MODAL, "negation-or-modal");
                }
            }
        }

        scan(raw, doc, NUMBER, SpanKind.NUMBER_WITH_UNIT, "number");
        scan(raw, doc, CAMEL, SpanKind.IDENTIFIER, "camelCase");
        scan(raw, doc, PASCAL, SpanKind.IDENTIFIER, "PascalCase");
        scan(raw, doc, SNAKE, SpanKind.IDENTIFIER, "snake_case");
        scan(raw, doc, UPPER_SNAKE, SpanKind.IDENTIFIER, "UPPER_SNAKE");
        scan(raw, doc, QUALIFIED, SpanKind.IDENTIFIER, "qualified-name");
        scan(raw, doc, ERROR_LINE, SpanKind.ERROR_TEXT, "error-line");
        scan(raw, doc, URL, SpanKind.PATH_OR_URL, "url");
        scan(raw, doc, PATH, SpanKind.PATH_OR_URL, "file-path");
        scan(raw, doc, QUOTED, SpanKind.QUOTED_STRING, "quoted");

        return merge(raw);
    }

    private static void scan(List<ProtectedSpan> out, CanonicalDocument doc, Pattern p, SpanKind kind, String reason) {
        Matcher m = p.matcher(doc.text());
        while (m.find()) {
            if (m.end() > m.start()) {
                add(out, doc, m.start(), m.end(), kind, reason);
            }
        }
    }

    private static void add(List<ProtectedSpan> out, CanonicalDocument doc, int s, int e, SpanKind kind, String reason) {
        out.add(new ProtectedSpan(doc.id(), s, e, kind, Level.HARD, reason));
    }

    /** Merges overlapping spans into their union. HARD wins over SOFT; the longest span names the kind. */
    static List<ProtectedSpan> merge(List<ProtectedSpan> spans) {
        List<ProtectedSpan> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(ProtectedSpan::start).thenComparingInt(ProtectedSpan::end));
        List<ProtectedSpan> merged = new ArrayList<>();
        ProtectedSpan cur = null;
        for (ProtectedSpan s : sorted) {
            if (cur == null) {
                cur = s;
            } else if (s.start() < cur.end()) {
                boolean curLonger = (cur.end() - cur.start()) >= (s.end() - s.start());
                ProtectedSpan dominant = curLonger ? cur : s;
                Level level = (cur.level() == Level.HARD || s.level() == Level.HARD) ? Level.HARD : Level.SOFT;
                cur = new ProtectedSpan(cur.docId(), cur.start(), Math.max(cur.end(), s.end()),
                        dominant.kind(), level, dominant.reason());
            } else {
                merged.add(cur);
                cur = s;
            }
        }
        if (cur != null) {
            merged.add(cur);
        }
        return merged;
    }
}
