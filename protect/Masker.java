package org.promptoptimizer.protect;

import java.util.ArrayList;
import java.util.List;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;

/**
 * Replaces every HARD span with an opaque placeholder so that any later operator can only touch
 * text between placeholders. Unmasking restores the exact original bytes and rejects any text in
 * which a placeholder is missing, duplicated, reordered or altered.
 */
public final class Masker {
    static final String PREFIX = "⟦P:";
    static final String SUFFIX = "⟧";

    /** Masked text plus the ordered originals needed to restore it. */
    public record Masked(String text, List<String> originals) {}

    public static final class PlaceholderIntegrityException extends RuntimeException {
        public PlaceholderIntegrityException(String message) {
            super(message);
        }
    }

    /**
     * @param spans spans for this document, sorted and non-overlapping (as returned by the detector)
     * @throws IllegalArgumentException if spans overlap, are out of range, or the text already
     *                                  contains the placeholder marker (masking would be ambiguous)
     */
    public Masked mask(CanonicalDocument doc, List<ProtectedSpan> spans) {
        String text = doc.text();
        if (text.contains(PREFIX)) {
            throw new IllegalArgumentException("document '" + doc.id() + "' already contains the placeholder marker");
        }
        StringBuilder out = new StringBuilder(text.length());
        List<String> originals = new ArrayList<>();
        int cursor = 0;
        for (ProtectedSpan s : spans) {
            if (s.level() != Level.HARD) {
                continue;
            }
            if (!s.docId().equals(doc.id())) {
                throw new IllegalArgumentException("span belongs to another document");
            }
            if (s.start() < cursor || s.end() > text.length()) {
                throw new IllegalArgumentException("spans overlap or exceed document bounds at " + s.start());
            }
            out.append(text, cursor, s.start());
            out.append(PREFIX).append(originals.size()).append(SUFFIX);
            originals.add(text.substring(s.start(), s.end()));
            cursor = s.end();
        }
        out.append(text, cursor, text.length());
        return new Masked(out.toString(), List.copyOf(originals));
    }

    /**
     * Restores originals into {@code maskedText}.
     *
     * @throws PlaceholderIntegrityException if placeholders are not exactly 0..n-1, once each, in order
     */
    public String unmask(String maskedText, List<String> originals) {
        StringBuilder out = new StringBuilder(maskedText.length());
        int expected = 0;
        int pos = 0;
        while (true) {
            int p = maskedText.indexOf(PREFIX, pos);
            if (p < 0) {
                break;
            }
            int q = maskedText.indexOf(SUFFIX, p);
            if (q < 0) {
                throw new PlaceholderIntegrityException("unterminated placeholder at " + p);
            }
            String num = maskedText.substring(p + PREFIX.length(), q);
            int idx;
            try {
                idx = Integer.parseInt(num);
            } catch (NumberFormatException e) {
                throw new PlaceholderIntegrityException("malformed placeholder '" + num + "' at " + p);
            }
            if (idx != expected) {
                throw new PlaceholderIntegrityException(
                        "placeholder " + idx + " found where " + expected + " was expected (missing, duplicated or reordered)");
            }
            out.append(maskedText, pos, p).append(originals.get(idx));
            expected++;
            pos = q + SUFFIX.length();
        }
        if (expected != originals.size()) {
            throw new PlaceholderIntegrityException(
                    "found " + expected + " placeholders, expected " + originals.size());
        }
        out.append(maskedText, pos, maskedText.length());
        return out.toString();
    }
}
