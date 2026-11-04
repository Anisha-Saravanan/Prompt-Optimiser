
package org.promptoptimizer;

import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;

public final class CanonicalizerDemo {

    public static void main(String[] args) {
        Canonicalizer canonicalizer = new Canonicalizer(1000);

        String input = "Hello\r\nworld\r";

        CanonicalDocument result =
                canonicalizer.canonicalize("sample.txt", input);

        System.out.println("ID: " + result.id());
        System.out.println("Normalized text:");
        System.out.println(result.text().replace("\n", "\\n\n"));
        System.out.println("Original SHA-256: " + result.originalSha256());
        System.out.println("Line endings normalized: " + result.crlfNormalized());
    }
}
