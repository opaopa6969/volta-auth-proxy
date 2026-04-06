package com.volta.authproxy.flow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates Mermaid stateDiagram-v2 from FlowDefinition.
 */
public final class MermaidGenerator {

    private MermaidGenerator() {}

    public static <S extends Enum<S> & FlowState> String generate(FlowDefinition<S> definition) {
        var sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");

        S initial = definition.initialState();
        if (initial != null) {
            sb.append("    [*] --> ").append(initial.name()).append('\n');
        }

        // Deduplicate branch transitions (same from, same branch)
        Set<String> seen = new LinkedHashSet<>();
        for (Transition<S> t : definition.transitions()) {
            String key = t.from().name() + "->" + t.to().name();
            if (!seen.add(key)) continue;

            String label = transitionLabel(t);
            sb.append("    ").append(t.from().name())
              .append(" --> ").append(t.to().name());
            if (!label.isEmpty()) {
                sb.append(" : ").append(label);
            }
            sb.append('\n');
        }

        // Error transitions
        Set<String> errorSeen = new LinkedHashSet<>();
        for (var entry : definition.errorTransitions().entrySet()) {
            String key = entry.getKey().name() + "->" + entry.getValue().name();
            // Skip if already covered by normal transitions
            if (seen.contains(key)) continue;
            if (!errorSeen.add(key)) continue;
            sb.append("    ").append(entry.getKey().name())
              .append(" --> ").append(entry.getValue().name())
              .append(" : error\n");
        }

        // Terminal states to [*]
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
        };
    }

    /** Generate and write .mmd file. Returns the generated content. */
    public static <S extends Enum<S> & FlowState> String writeToFile(
            FlowDefinition<S> definition, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String content = generate(definition);
        String filename = "flow-" + definition.name() + ".mmd";
        Files.writeString(outputDir.resolve(filename), content);
        return content;
    }
}
