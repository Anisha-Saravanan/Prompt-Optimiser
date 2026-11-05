package org.promptoptimizer.optimize;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.lossless.LosslessResult.PreparedDocument;
import org.promptoptimizer.model.Model.InformationUnit;

/**
 * Required-unit dependencies. Edges point from a unit to the unit that must also be kept.
 * The built-in inference is deliberately lexical and conservative, not a Java parser.
 */
public final class DependencyGraph {
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern TYPE_REFERENCE = Pattern.compile(
            "\\b(?:new|extends|implements|instanceof|import)\\s+(?:[\\w$]+\\.)*([A-Za-z_$][\\w$]*)\\b"
                    + "|\\b([A-Z][A-Za-z0-9_$]*)\\s*\\.\\s*[A-Za-z_$][\\w$]*"
                    + "|\\b([A-Z][A-Za-z0-9_$]*)\\s+[a-z_$][\\w$]*\\b");

    private final List<Edge> edges;
    private final Map<String, List<Edge>> outgoing;

    public DependencyGraph(Collection<Edge> edges) {
        Objects.requireNonNull(edges);
        Set<Edge> unique = new LinkedHashSet<>(edges);
        this.edges = List.copyOf(unique);
        Map<String, List<Edge>> index = new HashMap<>();
        for (Edge edge : this.edges) {
            index.computeIfAbsent(edge.unitId(), ignored -> new ArrayList<>()).add(edge);
        }
        Map<String, List<Edge>> immutable = new HashMap<>();
        index.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        this.outgoing = Map.copyOf(immutable);
    }

    public List<Edge> edges() {
        return edges;
    }

    /** Returns the transitive required closure, including all supplied unit ids. */
    public Set<String> closure(Collection<String> unitIds) {
        Objects.requireNonNull(unitIds);
        Set<String> result = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (String id : unitIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("unit ids must not be blank");
            }
            if (result.add(id)) {
                pending.addLast(id);
            }
        }
        while (!pending.isEmpty()) {
            for (Edge edge : outgoing.getOrDefault(pending.removeFirst(), List.of())) {
                if (result.add(edge.requiredUnitId())) {
                    pending.addLast(edge.requiredUnitId());
                }
            }
        }
        return Set.copyOf(result);
    }

    /** Adds dependencies visible from Java declarations/references and simple lead-in/list structure. */
    public static DependencyGraph infer(List<PreparedDocument> documents) {
        Objects.requireNonNull(documents);
        Map<String, Set<String>> typeOwners = new HashMap<>();
        List<UnitText> units = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (PreparedDocument document : documents) {
            String text = document.doc().text();
            for (InformationUnit unit : document.units()) {
                String unitText = text.substring(unit.start(), unit.end());
                units.add(new UnitText(unit.id(), unitText));
                Matcher declarations = TYPE_DECLARATION.matcher(unitText);
                while (declarations.find()) {
                    typeOwners.computeIfAbsent(declarations.group(1), ignored -> new LinkedHashSet<>()).add(unit.id());
                }
            }
        }

        for (UnitText unit : units) {
            Matcher refs = TYPE_REFERENCE.matcher(unit.text());
            while (refs.find()) {
                String symbol = refs.group(1) != null ? refs.group(1)
                        : refs.group(2) != null ? refs.group(2) : refs.group(3);
                Set<String> owners = typeOwners.getOrDefault(symbol, Set.of());
                if (owners.size() == 1) {
                    String owner = owners.iterator().next();
                    if (!owner.equals(unit.id())) {
                        edges.add(new Edge(unit.id(), owner, EdgeType.REFERS_TO));
                    }
                }
            }
        }

        for (PreparedDocument document : documents) {
            List<InformationUnit> documentUnits = document.units();
            String text = document.doc().text();
            for (int i = 0; i + 1 < documentUnits.size(); i++) {
                InformationUnit leadIn = documentUnits.get(i);
                InformationUnit following = documentUnits.get(i + 1);
                String leadText = text.substring(leadIn.start(), leadIn.end()).stripTrailing();
                if (endsWithListLeadIn(leadText)) {
                    edges.add(new Edge(following.id(), leadIn.id(), EdgeType.ELABORATES));
                }
            }
        }
        return new DependencyGraph(edges);
    }

    private static boolean endsWithListLeadIn(String text) {
        if (text.isEmpty() || !text.endsWith(":")) {
            return false;
        }
        String lastLine = text.substring(text.lastIndexOf('\n') + 1).strip();
        return !lastLine.isEmpty() && lastLine.endsWith(":");
    }

    private record UnitText(String id, String text) {}

    /** Required edge from {@code unitId} to {@code requiredUnitId}. */
    public record Edge(String unitId, String requiredUnitId, EdgeType type) {
        public Edge {
            if (unitId == null || unitId.isBlank() || requiredUnitId == null || requiredUnitId.isBlank()) {
                throw new IllegalArgumentException("dependency unit ids are required");
            }
            Objects.requireNonNull(type);
            if (unitId.equals(requiredUnitId)) {
                throw new IllegalArgumentException("a unit cannot depend on itself");
            }
        }
    }

    public enum EdgeType {
        REFERS_TO,
        SCOPES,
        IMPORTS,
        CALLS,
        EXTENDS,
        SCHEMA_OF,
        ELABORATES,
        SAME_ERROR,
        FILE_CONTEXT
    }
}
