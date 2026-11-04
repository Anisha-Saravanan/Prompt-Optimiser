package org.promptoptimizer.ingest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.model.CanonicalDocument;

class CanonicalizerTest {
    private final Canonicalizer canonicalizer = new Canonicalizer(1_000);

    @Test
    void normalizesLineEndingsAndRecordsIt() {
        CanonicalDocument d = canonicalizer.canonicalize("a.txt", "one\r\ntwo\rthree\n");
        assertEquals("one\ntwo\nthree\n", d.text());
        assertTrue(d.crlfNormalized());
    }

    @Test
    void leavesLfTextUntouched() {
        CanonicalDocument d = canonicalizer.canonicalize("a.txt", "one\ntwo\n");
        assertEquals("one\ntwo\n", d.text());
        assertFalse(d.crlfNormalized());
    }

    @Test
    void hashIsOfOriginalBytesNotNormalizedText() {
        CanonicalDocument crlf = canonicalizer.canonicalize("a", "x\r\ny");
        CanonicalDocument lf = canonicalizer.canonicalize("a", "x\ny");
        assertEquals(crlf.text(), lf.text());
        assertNotEquals(crlf.originalSha256(), lf.originalSha256());
        // known SHA-256 of "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                canonicalizer.canonicalize("h", "abc").originalSha256());
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] bad = {(byte) 0xC3, (byte) 0x28};
        assertThrows(InputRejectedException.class, () -> canonicalizer.canonicalize("bad", bad));
    }

    @Test
    void rejectsBinaryNulBytes() {
        byte[] nul = "ab\u0000cd".getBytes(StandardCharsets.UTF_8);
        assertThrows(InputRejectedException.class, () -> canonicalizer.canonicalize("bin", nul));
    }

    @Test
    void rejectsOversizeAndBlankIdAndNull() {
        assertThrows(InputRejectedException.class, () -> canonicalizer.canonicalize("big", new byte[1_001]));
        assertThrows(InputRejectedException.class, () -> canonicalizer.canonicalize(" ", "x"));
        assertThrows(InputRejectedException.class, () -> canonicalizer.canonicalize("n", (byte[]) null));
    }
}
