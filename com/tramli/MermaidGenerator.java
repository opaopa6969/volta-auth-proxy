package com.tramli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Generates Mermaid stateDiagram-v2 from FlowDefinition.
 */
public final class MermaidGenerator {

    private MermaidGenerator() {}

    public static <S extends Enum<S> & FlowState> String generate(FlowDefinition<S> definition) {
        var sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");

        S initial = definition.initialState();
        if (initial != null) sb.append("    [*] --> ").append(initial.name()).append('\n');

        Set<String> seen = new LinkedHashSet<>();
        for (Transition<S> t : definition.transitions()) {
            if (t.isSubFlow() && t.subFlowDefinition() != null) {
                // Render sub-flow as Mermaid subgraph
                var subDef = t.subFlowDefinition();
                sb.append("    state ").append(t.from().name()).append(" {\n");
                if (subDef.initialState() != null)
                    sb.append("        [*] --> ").append(subDef.initialState().name()).append('\n');
                for (var st : subDef.transitions()) {
                    String sKey = st.from().name() + "->" + st.to().name();
                    sb.append("        ").append(st.from().name()).append(" --> ").append(st.to().name());
                    String sLabel = transitionLabel(st);
                    if (!sLabel.isEmpty()) sb.append(" : ").append(sLabel);
                    sb.append('\n');
                }
                for (var term : subDef.terminalStates())
                    sb.append("        ").append(term.name()).append(" --> [*]\n");
                sb.append("    }\n");
                // Add exit transitions
                for (var exit : t.exitMappings().entrySet())
                    sb.append("    ").append(t.from().name()).append(" --> ").append(exit.getValue().name())
                      .append(" : ").append(exit.getKey()).append('\n');
                continue;
            }
            String key = t.from().name() + "->" + t.to().name();
            if (!seen.add(key)) continue;
            String label = transitionLabel(t);
            sb.append("    ").append(t.from().name()).append(" --> ").append(t.to().name());
            if (!label.isEmpty()) sb.append(" : ").append(label);
            sb.append('\n');
        }

        Set<String> errorSeen = new LinkedHashSet<>();
        for (var entry : definition.errorTransitions().entrySet()) {
            String key = entry.getKey().name() + "->" + entry.getValue().name();
            if (seen.contains(key)) continue;
            if (!errorSeen.add(key)) continue;
            sb.append("    ").append(entry.getKey().name())
              .append(" --> ").append(entry.getValue().name()).append(" : error\n");
        }

        for (S s : definition.terminalStates()) {
            sb.append("    ").append(s.name()).append(" --> [*]\n");
        }
        return sb.toString();
    }

    private static <S extends Enum<S> & FlowState> String transitionLabel(Transition<S> t) {
        return switch (t.type()) {
            case AUTO -> t.processor() != null ? t.processor().name() : "";
            case EXTERNAL -> t.guard() != null ? "[" + t.guard().name() + "]" : "";
            case BRANCH -> t.branch() != null ? t.branch().name() : "";
            case SUB_FLOW -> t.subFlowDefinition() != null ? "{" + t.subFlowDefinition().name() + "}" : "";
        };
    }

    /**
     * Generate Mermaid diagram highlighting external transitions and their data contracts.
     * Shows what data clients must send and what they receive.
     */
    public static <S extends Enum<S> & FlowState> String generateExternalContract(FlowDefinition<S> definition) {
        var sb = new StringBuilder();
        sb.append("flowchart LR\n");
        for (Transition<S> t : definition.transitions()) {
            if (!t.isExternal()) continue;
            sb.append("    subgraph ").append(t.from().name()).append("_to_").append(t.to().name()).append("\n");
            sb.append("        direction TB\n");
            if (t.guard() != null) {
                sb.append("        ").append(t.guard().name()).append("{\"[").append(t.guard().name()).append("]\"}\n");
                for (var req : t.guard().requires())
                    sb.append("        ").append(req.getSimpleName()).append(" -->|client sends| ").append(t.guard().name()).append("\n");
                for (var prod : t.guard().produces())
                    sb.append("        ").append(t.guard().name()).append(" -->|returns| ").append(prod.getSimpleName()).append("\n");
            }
            sb.append("    end\n");
        }
        return sb.toString();
    }

    /** Generate Mermaid data-flow diagram from requires/produces declarations. */
    public static <S extends Enum<S> & FlowState> String generateDataFlow(FlowDefinition<S> definition) {
        return definition.dataFlowGraph().toMermaid();
    }

    public static <S extends Enum<S> & FlowState> String writeToFile(
            FlowDefinition<S> definition, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String content = generate(definition);
        Files.writeString(outputDir.resolve("flow-" + definition.name() + ".mmd"), content);
        String dataFlow = generateDataFlow(definition);
        Files.writeString(outputDir.resolve("dataflow-" + definition.name() + ".mmd"), dataFlow);
        return content;
    }
}
