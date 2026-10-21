package org.promptoptimizer.ingest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.promptoptimizer.model.CanonicalDocument;

/**
 * Converts raw bytes into a {@link CanonicalDocument}. The only normalizations applied are
 * strict UTF-8 decoding and CRLF/CR to LF; both are recorded. Malformed input is rejected
 * explicitly rather than silently repaired.
 */
public final class Canonicalizer {
    private final long maxBytes;

    public Canonicalizer(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public CanonicalDocument canonicalize(String id, byte[] bytes) {
        if (id == null || id.isBlank()) {
            throw new InputRejectedException("document id must not be blank");
        }
        if (bytes == null) {
            throw new InputRejectedException("document '" + id + "' has no content");
        }
        if (bytes.length > maxBytes) {
            throw new InputRejectedException(
                    "document '" + id + "' is " + bytes.length + " bytes, limit is " + maxBytes);
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InputRejectedException("document '" + id + "' is not valid UTF-8", e);
        }
        if (decoded.indexOf('\u0000') >= 0) {
            throw new InputRejectedException("document '" + id + "' contains NUL bytes (binary?)");
        }
        boolean hadCr = decoded.indexOf('\r') >= 0;
        String text = hadCr ? decoded.replace("\r\n", "\n").replace('\r', '\n') : decoded;
        return new CanonicalDocument(id, text, sha256(bytes), hadCr);
    }

    public CanonicalDocument canonicalize(String id, String text) {
        return canonicalize(id, text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
