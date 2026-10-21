package org.promptoptimizer.lossless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes reversible whitespace edits for prose (blueprint S5): trailing spaces/tabs are removed
 * and runs of two or more blank lines collapse to one blank line.
 *
 * <p>An edit is skipped, never forced, when it overlaps an excluded range (fenced block or HARD
 * span), when a blank-line run sits next to indented text (possible indented code or a list
 * continuation), or, for Markdown, when it would remove a two-space hard line break. Leading
 * blank lines of a document are collapsed like any other run; a single blank line is never touched.
 */
public final class WhitespaceNormalizer {
    private static final Pattern BLANK_RUN = Pattern.compile("(?<=\\n)(?:[ \\t]*\\n){2,}");
    private static final Pattern TRAILING = Pattern.compile("[ \\t]+(?=\\n|\\z)");

    /** Edits for one unit plus the reasons for edits that were deliberately skipped. */
    public record Result(EditScript script, List<String> skipped) {}

    /**
     * Ranges of fenced code blocks (opening fence line through closing fence line, or to the end if
     * unterminated). Recognises ``` as the segmenter does, and also ~~~ with matching-marker closing;
     * the union of both readings is returned so nothing the segmenter treats as a fence is edited.
     */
    public static List<int[]> fencedRanges(String text) {
        List<int[]> ranges = new ArrayList<>(fenceScan(text, false));
        ranges.addAll(fenceScan(text, true));
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1]) {
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(new int[] {r[0], r[1]});
            }
        }
        return merged;
    }

    private static List<int[]> fenceScan(String text, boolean markerAware) {
        List<int[]> out = new ArrayList<>();
        int pos = 0;
        int fenceStart = -1;
        String openMarker = null;
        while (pos < text.length()) {
            int nl = text.indexOf('\n', pos);
            int lineEnd = nl < 0 ? text.length() : nl;
            int next = nl < 0 ? text.length() : nl + 1;
            String stripped = text.substring(pos, lineEnd).stripLeading();
            String marker = null;
            if (stripped.startsWith("```")) {
                marker = "```";
            } else if (markerAware && stripped.startsWith("~~~")) {
                marker = "~~~";
            }
            if (marker != null) {
                if (fenceStart < 0) {
                    fenceStart = pos;
                    openMarker = marker;
                } else if (!markerAware || marker.equals(openMarker)) {
                    out.add(new int[] {fenceStart, next});
                    fenceStart = -1;
                }
            }
            pos = next;
        }
        if (fenceStart >= 0) {
            out.add(new int[] {fenceStart, text.length()});
        }
        return out;
    }

    /**
     * @param docText  full canonical text of the document
     * @param format   must be a prose format
     * @param excluded ranges {@code [start,end)} in document coordinates that must not be edited
     * @return script whose offsets are relative to {@code unitStart}
     */
    public Result compute(String docText, ContentFormat format, int unitStart, int unitEnd, List<int[]> excluded) {
        if (!format.isProse()) {
            throw new IllegalArgumentException("whitespace normalization is not allowed for " + format);
        }
        List<String> skipped = new ArrayList<>();
        List<int[]> blankRuns = new ArrayList<>();

        Matcher mb = BLANK_RUN.matcher(docText).region(unitStart, unitEnd);
        mb.useTransparentBounds(true).useAnchoringBounds(false);
        while (mb.find()) {
            int s = mb.start();
            int e = mb.end();
            if (overlapsAny(excluded, s, e)) {
                skipped.add("BLANK_RUN_IN_PROTECTED_OR_FENCED@" + s);
            } else if (startsIndented(docText, lineStartBefore(docText, s - 1)) || startsIndented(docText, e)) {
                skipped.add("BLANK_RUN_NEXT_TO_INDENTED_TEXT@" + s);
            } else {
                blankRuns.add(new int[] {s, e});
            }
        }

        List<TextEdit> edits = new ArrayList<>();
        for (int[] r : blankRuns) {
            edits.add(new TextEdit(r[0] - unitStart, r[1] - unitStart, docText.substring(r[0], r[1]), "\n",
                    EditKind.BLANK_LINE_RUN));
        }

        Matcher mt = TRAILING.matcher(docText).region(unitStart, unitEnd);
        mt.useTransparentBounds(true).useAnchoringBounds(false);
        while (mt.find()) {
            int s = mt.start();
            int e = mt.end();
            String run = docText.substring(s, e);
            if (overlapsAny(blankRuns, s, e)) {
                continue; // already covered by the blank-run edit
            }
            if (overlapsAny(excluded, s, e)) {
                skipped.add("TRAILING_WS_IN_PROTECTED_OR_FENCED@" + s);
                continue;
            }
            if (format == ContentFormat.MARKDOWN && run.endsWith("  ")
                    && !docText.substring(lineStartBefore(docText, s), s).isBlank()) {
                skipped.add("MARKDOWN_HARD_LINE_BREAK@" + s);
                continue;
            }
            edits.add(new TextEdit(s - unitStart, e - unitStart, run, "", EditKind.TRAILING_WHITESPACE));
        }

        edits.sort(Comparator.comparingInt(TextEdit::start));
        return new Result(new EditScript(unitEnd - unitStart, edits), skipped);
    }

    private static boolean overlapsAny(List<int[]> ranges, int s, int e) {
        for (int[] r : ranges) {
            if (s < r[1] && r[0] < e) {
                return true;
            }
        }
        return false;
    }

    private static int lineStartBefore(String text, int pos) {
        return pos == 0 ? 0 : text.lastIndexOf('\n', pos - 1) + 1;
    }

    private static boolean startsIndented(String text, int pos) {
        return text.startsWith("    ", pos) || text.startsWith("\t", pos);
    }
}
