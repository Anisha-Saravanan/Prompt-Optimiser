package org.promptoptimizer.optimize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic lexical relevance and overlap features; these are candidate signals, not calibrated importance. */
public final class Bm25JaccardScorer {
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}_]+");

    private final Config config;

    public Bm25JaccardScorer() {
        this(Config.defaults());
    }

    public Bm25JaccardScorer(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    /** Scores units against a task query. Results are ordered by combined score, then unit id. */
    public List<UnitScore> score(List<UnitText> units, String query) {
        if (units == null || query == null) {
            throw new IllegalArgumentException("units and query are required");
        }
        Set<String> ids = new HashSet<>();
        for (UnitText unit : units) {
            if (unit == null) {
                throw new IllegalArgumentException("units must not contain null");
            }
            if (!ids.add(unit.id())) {
                throw new IllegalArgumentException("duplicate unit id: " + unit.id());
            }
        }
        if (units.isEmpty()) {
            return List.of();
        }

        List<Map<String, Integer>> frequencies = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double averageLength = 0;
        for (UnitText unit : units) {
            Map<String, Integer> terms = frequencies(tokenize(unit.text()));
            frequencies.add(terms);
            averageLength += terms.values().stream().mapToInt(Integer::intValue).sum();
            for (String term : terms.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        averageLength /= units.size();

        Map<String, Integer> queryTerms = frequencies(tokenize(query));
        Set<String> querySet = queryTerms.keySet();
        List<Double> bm25 = new ArrayList<>();
        List<Double> jaccard = new ArrayList<>();
        double maxBm25 = 0;
        for (int i = 0; i < units.size(); i++) {
            Map<String, Integer> terms = frequencies.get(i);
            int length = terms.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (Map.Entry<String, Integer> q : queryTerms.entrySet()) {
                Integer tf = terms.get(q.getKey());
                if (tf == null) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(q.getKey(), 0);
                double idf = Math.log(1 + (units.size() - df + 0.5) / (df + 0.5));
                double denominator = tf + config.k1() * (1 - config.b()
                        + config.b() * length / Math.max(averageLength, 1));
                score += q.getValue() * idf * tf * (config.k1() + 1) / denominator;
            }
            bm25.add(score);
            maxBm25 = Math.max(maxBm25, score);
            Set<String> unitTerms = terms.keySet();
            jaccard.add(jaccard(querySet, unitTerms));
        }

        double weightTotal = config.bm25Weight() + config.jaccardWeight();
        List<UnitScore> result = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            double normalizedBm25 = maxBm25 == 0 ? 0 : bm25.get(i) / maxBm25;
            double combined = (config.bm25Weight() * normalizedBm25 + config.jaccardWeight() * jaccard.get(i))
                    / weightTotal;
            result.add(new UnitScore(units.get(i).id(), bm25.get(i), jaccard.get(i), normalizedBm25, combined));
        }
        result.sort((left, right) -> {
            int byScore = Double.compare(right.combined(), left.combined());
            return byScore != 0 ? byScore : left.unitId().compareTo(right.unitId());
        });
        return List.copyOf(result);
    }

    /** Pairwise overlap features for review or candidate generation; this method never removes a unit. */
    public PairScore compare(UnitText left, UnitText right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("both units are required");
        }
        Set<String> leftTerms = new HashSet<>(tokenize(left.text()));
        Set<String> rightTerms = new HashSet<>(tokenize(right.text()));
        return new PairScore(left.id(), right.id(), jaccard(leftTerms, rightTerms),
                coverage(leftTerms, rightTerms), coverage(rightTerms, leftTerms));
    }

    /** Returns lexical near-duplicate candidates only; the threshold must be calibrated before use for merging. */
    public List<PairScore> nearDuplicateCandidates(List<UnitText> units, double minimumJaccard) {
        if (units == null || !Double.isFinite(minimumJaccard) || minimumJaccard < 0 || minimumJaccard > 1) {
            throw new IllegalArgumentException("units and a Jaccard threshold in [0, 1] are required");
        }
        Set<String> ids = new HashSet<>();
        for (UnitText unit : units) {
            if (unit == null || !ids.add(unit.id())) {
                throw new IllegalArgumentException("units must be non-null and have unique ids");
            }
        }
        List<PairScore> candidates = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            for (int j = i + 1; j < units.size(); j++) {
                PairScore pair = compare(units.get(i), units.get(j));
                if (pair.jaccard() >= minimumJaccard) {
                    candidates.add(pair);
                }
            }
        }
        candidates.sort((left, right) -> {
            int byOverlap = Double.compare(right.jaccard(), left.jaccard());
            if (byOverlap != 0) {
                return byOverlap;
            }
            int byLeftId = left.leftUnitId().compareTo(right.leftUnitId());
            return byLeftId != 0 ? byLeftId : left.rightUnitId().compareTo(right.rightUnitId());
        });
        return List.copyOf(candidates);
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TERM.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static Map<String, Integer> frequencies(List<String> terms) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String term : terms) {
            result.merge(term, 1, Integer::sum);
        }
        return result;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static double coverage(Set<String> source, Set<String> target) {
        if (source.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(source);
        intersection.retainAll(target);
        return (double) intersection.size() / source.size();
    }

    public record UnitText(String id, String text) {
        public UnitText {
            if (id == null || id.isBlank() || text == null) {
                throw new IllegalArgumentException("unit id and text are required");
            }
        }
    }

    public record UnitScore(String unitId, double bm25, double jaccard, double normalizedBm25, double combined) {}

    /** Directional lexical coverage is not proof of semantic equivalence. */
    public record PairScore(String leftUnitId, String rightUnitId, double jaccard,
                            double leftCoveredByRight, double rightCoveredByLeft) {}

    /** BM25 parameters and feature weights are transparent defaults, not fitted values. */
    public record Config(double k1, double b, double bm25Weight, double jaccardWeight) {
        public Config {
            if (!Double.isFinite(k1) || k1 <= 0 || !Double.isFinite(b) || b < 0 || b > 1
                    || !Double.isFinite(bm25Weight) || bm25Weight < 0
                    || !Double.isFinite(jaccardWeight) || jaccardWeight < 0
                    || bm25Weight + jaccardWeight == 0) {
                throw new IllegalArgumentException("invalid BM25/Jaccard configuration");
            }
        }

        public static Config defaults() {
            return new Config(1.2, 0.75, 0.5, 0.5);
        }
    }
}
