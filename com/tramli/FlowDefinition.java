package com.tramli;

import java.time.Duration;
import java.util.*;

/**
 * Immutable flow definition built via DSL. Validated at build() time with 8 checks.
 *
 * <pre>
 * Tramli.define("order", OrderState.class)
 *     .ttl(Duration.ofMinutes(5))
 *     .from(CREATED).auto(PAYMENT_PENDING, paymentInit)
 *     .from(PAYMENT_PENDING).external(CONFIRMED, paymentGuard)
 *     .from(CONFIRMED).branch(stockCheck)
 *         .to(SHIPPED, "in_stock", shipProcessor)
 *         .to(CANCELLED, "out_of_stock", cancelProcessor)
 *         .endBranch()
 *     .onAnyError(CANCELLED)
 *     .build();
 * </pre>
 */
public final class FlowDefinition<S extends Enum<S> & FlowState> {
    private final String name;
    private final Class<S> stateClass;
    private final Duration ttl;
    private final int maxGuardRetries;
    private final List<Transition<S>> transitions;
    private final Map<S, S> errorTransitions;
    private final Map<S, List<ExceptionRoute<S>>> exceptionRoutes;
    private final S initialState;

    /** Route to a specific state based on exception type. */
    public record ExceptionRoute<S>(Class<? extends Exception> exceptionType, S target) {}
    private final Set<S> terminalStates;
    private final DataFlowGraph<S> dataFlowGraph;
    private final List<String> warnings;

    private FlowDefinition(String name, Class<S> stateClass, Duration ttl, int maxGuardRetries,
                           List<Transition<S>> transitions, Map<S, S> errorTransitions,
                           Map<S, List<ExceptionRoute<S>>> exceptionRoutes,
                           DataFlowGraph<S> dataFlowGraph, List<String> warnings) {
        this.name = name;
        this.stateClass = stateClass;
        this.ttl = ttl;
        this.maxGuardRetries = maxGuardRetries;
        this.transitions = List.copyOf(transitions);
        this.errorTransitions = Map.copyOf(errorTransitions);
        this.exceptionRoutes = exceptionRoutes != null ? Map.copyOf(exceptionRoutes) : Map.of();

        S initial = null;
        Set<S> terminals = EnumSet.noneOf(stateClass);
        for (S s : stateClass.getEnumConstants()) {
            if (s.isInitial()) initial = s;
            if (s.isTerminal()) terminals.add(s);
        }
        this.initialState = initial;
        this.terminalStates = Collections.unmodifiableSet(terminals);
        this.dataFlowGraph = dataFlowGraph;
        this.warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public String name() { return name; }
    public Class<S> stateClass() { return stateClass; }
    public Duration ttl() { return ttl; }
    public int maxGuardRetries() { return maxGuardRetries; }
    public List<Transition<S>> transitions() { return transitions; }
    public Map<S, S> errorTransitions() { return errorTransitions; }
    public Map<S, List<ExceptionRoute<S>>> exceptionRoutes() { return exceptionRoutes; }
    public S initialState() { return initialState; }
    public Set<S> terminalStates() { return terminalStates; }

    public List<Transition<S>> transitionsFrom(S state) {
        return transitions.stream().filter(t -> t.from() == state).toList();
    }

    public Optional<Transition<S>> externalFrom(S state) {
        return transitions.stream()
                .filter(t -> t.from() == state && t.isExternal())
                .findFirst();
    }

    public Set<S> allStates() {
        return EnumSet.allOf(stateClass);
    }

    /** Data-flow graph derived from requires/produces declarations. */
    public DataFlowGraph<S> dataFlowGraph() { return dataFlowGraph; }

    /** Convert to a renderable state diagram view. */
    public RenderableGraph.StateDiagram toRenderableStateDiagram() {
        var edges = new java.util.ArrayList<RenderableGraph.StateEdge>();
        var subFlows = new java.util.ArrayList<RenderableGraph.SubFlowBlock>();

        for (var t : transitions) {
            if (t.isSubFlow() && t.subFlowDefinition() != null) {
                // Sub-flow transitions become SubFlowBlocks
                var subDef = t.subFlowDefinition();
                var innerEdges = new java.util.ArrayList<RenderableGraph.StateEdge>();
                for (var st : subDef.transitions()) {
                    String label = st.processor() != null ? st.processor().name() :
                            st.guard() != null ? "[" + st.guard().name() + "]" :
                            st.branch() != null ? st.branch().name() : "";
                    innerEdges.add(new RenderableGraph.StateEdge(st.from().name(), st.to().name(), label));
                }
                var innerTerminals = new java.util.LinkedHashSet<String>();
                for (var term : subDef.terminalStates()) innerTerminals.add(term.name());
                var inner = new RenderableGraph.StateDiagram(subDef.name(),
                        subDef.initialState() != null ? subDef.initialState().name() : null,
                        innerEdges, innerTerminals, java.util.List.of());
                subFlows.add(new RenderableGraph.SubFlowBlock(t.from().name(), inner));
                continue;
            }
            String label = t.processor() != null ? t.processor().name() :
                    t.guard() != null ? "[" + t.guard().name() + "]" :
                    t.branch() != null ? t.branch().name() : "";
            edges.add(new RenderableGraph.StateEdge(t.from().name(), t.to().name(), label));
        }
        for (var e : errorTransitions.entrySet()) {
            edges.add(new RenderableGraph.StateEdge(e.getKey().name(), e.getValue().name(), "error"));
        }
        var terminals = new java.util.LinkedHashSet<String>();
        for (var t : terminalStates) terminals.add(t.name());
        return new RenderableGraph.StateDiagram(name,
                initialState != null ? initialState.name() : null,
                edges, terminals, subFlows);
    }

    /** Render the state diagram using a custom renderer. */
    public String renderStateDiagram(java.util.function.Function<RenderableGraph.StateDiagram, String> renderer) {
        return renderer.apply(toRenderableStateDiagram());
    }

    /** Structural warnings detected at build() time (e.g. liveness risks). */
    public List<String> warnings() { return warnings; }

    /**
     * Create a new FlowDefinition with a sub-flow inserted before a specific transition.
     * The original transition A→B becomes: A→subFlow→(onExit "DONE")→B.
     * Original FlowDefinition is not modified.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public FlowDefinition<S> withPlugin(S from, S to, FlowDefinition<?> pluginFlow) {
        var newTransitions = new ArrayList<Transition<S>>();
        boolean replaced = false;
        for (Transition<S> t : transitions) {
            if (t.from() == from && t.to() == to && !replaced) {
                // Replace with sub-flow transition
                var exitMap = new java.util.LinkedHashMap<String, S>();
                for (var terminal : pluginFlow.terminalStates()) {
                    exitMap.put(terminal.name(), to);
                }
                newTransitions.add(new Transition<>(from, from, TransitionType.SUB_FLOW,
                        t.processor(), null, null, Map.of(), pluginFlow, Map.copyOf(exitMap)));
                // Keep original transition as auto from the exit target (if not already present)
                if (t.processor() != null) {
                    // Original processor runs after sub-flow completes
                }
                replaced = true;
            } else {
                newTransitions.add(t);
            }
        }
        // Reuse parent's data-flow graph (plugin insertion preserves data contracts)
        return new FlowDefinition<>(name + "+plugin:" + pluginFlow.name(), stateClass, ttl,
                maxGuardRetries, newTransitions, new LinkedHashMap<>(errorTransitions), this.exceptionRoutes,
                this.dataFlowGraph, this.warnings);
    }

    // ─── Builder ─────────────────────────────────────────────

    public static <S extends Enum<S> & FlowState> Builder<S> builder(String name, Class<S> stateClass) {
        return new Builder<>(name, stateClass);
    }

    public static final class Builder<S extends Enum<S> & FlowState> {
        private final String name;
        private final Class<S> stateClass;
        private Duration ttl = Duration.ofMinutes(5);
        private int maxGuardRetries = 3;
        private final List<Transition<S>> transitions = new ArrayList<>();
        private final Map<S, S> errorTransitions = new LinkedHashMap<>();
        private final Map<S, List<ExceptionRoute<S>>> exceptionRoutes = new LinkedHashMap<>();
        private final Set<Class<?>> initiallyAvailable = new HashSet<>();

        private Builder(String name, Class<S> stateClass) {
            this.name = name;
            this.stateClass = stateClass;
        }

        /** Declare data types that will be provided via startFlow(initialData). */
        public Builder<S> initiallyAvailable(Class<?>... types) {
            Collections.addAll(initiallyAvailable, types);
            return this;
        }

        public Builder<S> ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public Builder<S> maxGuardRetries(int max) {
            this.maxGuardRetries = max;
            return this;
        }

        public FromBuilder from(S state) {
            return new FromBuilder(state);
        }

        public Builder<S> onError(S from, S to) {
            errorTransitions.put(from, to);
            return this;
        }

        /** Route specific exception types to specific states. Checked before onError. */
        public Builder<S> onStepError(S from, Class<? extends Exception> exceptionType, S to) {
            exceptionRoutes.computeIfAbsent(from, k -> new ArrayList<>())
                    .add(new ExceptionRoute<>(exceptionType, to));
            return this;
        }

        public Builder<S> onAnyError(S errorState) {
            for (S s : stateClass.getEnumConstants()) {
                if (!s.isTerminal()) errorTransitions.put(s, errorState);
            }
            return this;
        }

        public final class FromBuilder {
            private final S from;
            private FromBuilder(S from) { this.from = from; }

            public Builder<S> auto(S to, StateProcessor processor) {
                transitions.add(new Transition<>(from, to, TransitionType.AUTO, processor, null, null, Map.of()));
                return Builder.this;
            }

            public Builder<S> external(S to, TransitionGuard guard) {
                transitions.add(new Transition<>(from, to, TransitionType.EXTERNAL, null, guard, null, Map.of()));
                return Builder.this;
            }

            public Builder<S> external(S to, TransitionGuard guard, Duration timeout) {
                transitions.add(new Transition<>(from, to, TransitionType.EXTERNAL, null, guard, null, Map.of(), null, Map.of(), timeout));
                return Builder.this;
            }

            public Builder<S> external(S to, TransitionGuard guard, StateProcessor processor) {
                transitions.add(new Transition<>(from, to, TransitionType.EXTERNAL, processor, guard, null, Map.of()));
                return Builder.this;
            }

            public Builder<S> external(S to, TransitionGuard guard, StateProcessor processor, Duration timeout) {
                transitions.add(new Transition<>(from, to, TransitionType.EXTERNAL, processor, guard, null, Map.of(), null, Map.of(), timeout));
                return Builder.this;
            }

            public BranchBuilder branch(BranchProcessor branch) {
                return new BranchBuilder(from, branch);
            }

            public SubFlowBuilder subFlow(FlowDefinition<?> subFlowDef) {
                return new SubFlowBuilder(from, subFlowDef);
            }
        }

        public final class SubFlowBuilder {
            private final S from;
            private final FlowDefinition<?> subFlowDef;
            private final java.util.LinkedHashMap<String, S> exitMap = new java.util.LinkedHashMap<>();

            private SubFlowBuilder(S from, FlowDefinition<?> subFlowDef) {
                this.from = from;
                this.subFlowDef = subFlowDef;
            }

            public SubFlowBuilder onExit(String terminalName, S parentState) {
                exitMap.put(terminalName, parentState);
                return this;
            }

            public Builder<S> endSubFlow() {
                transitions.add(new Transition<>(from, from, TransitionType.SUB_FLOW,
                        null, null, null, Map.of(), subFlowDef, Map.copyOf(exitMap)));
                return Builder.this;
            }
        }

        public final class BranchBuilder {
            private final S from;
            private final BranchProcessor branch;
            private final Map<String, S> targets = new LinkedHashMap<>();
            private final Map<String, StateProcessor> processors = new LinkedHashMap<>();

            private BranchBuilder(S from, BranchProcessor branch) {
                this.from = from;
                this.branch = branch;
            }

            public BranchBuilder to(S state, String label) {
                targets.put(label, state);
                return this;
            }

            public BranchBuilder to(S state, String label, StateProcessor processor) {
                targets.put(label, state);
                processors.put(label, processor);
                return this;
            }

            public Builder<S> endBranch() {
                for (var entry : targets.entrySet()) {
                    StateProcessor proc = processors.get(entry.getKey());
                    transitions.add(new Transition<>(from, entry.getValue(), TransitionType.BRANCH,
                            proc, null, branch, Map.copyOf(targets)));
                }
                return Builder.this;
            }
        }

        public FlowDefinition<S> build() {
            var def = new FlowDefinition<>(name, stateClass, ttl, maxGuardRetries, transitions, errorTransitions, exceptionRoutes, null, null);
            validate(def);
            var graph = DataFlowGraph.build(def, initiallyAvailable);
            var warnings = buildWarnings(def);
            return new FlowDefinition<>(name, stateClass, ttl, maxGuardRetries, transitions, errorTransitions, exceptionRoutes, graph, warnings);
        }

        private List<String> buildWarnings(FlowDefinition<S> def) {
            var warnings = new ArrayList<String>();
            boolean perpetual = def.terminalStates.isEmpty();
            boolean hasExternal = def.transitions.stream().anyMatch(Transition::isExternal);
            if (perpetual && hasExternal) {
                warnings.add("Perpetual flow '" + name + "' has External transitions — " +
                        "ensure events are always delivered to avoid deadlock (liveness risk)");
            }
            // Dead data warning
            if (def.dataFlowGraph != null) {
                var dead = def.dataFlowGraph.deadData();
                if (!dead.isEmpty()) {
                    warnings.add("Dead data detected: " + dead.stream()
                            .map(Class::getSimpleName).sorted().toList() +
                            " — produced but never required by any downstream processor");
                }
            }
            // ExceptionRoute subclass ordering warning
            for (var entry : exceptionRoutes.entrySet()) {
                var routes = entry.getValue();
                for (int i = 0; i < routes.size(); i++) {
                    for (int j = i + 1; j < routes.size(); j++) {
                        if (routes.get(i).exceptionType().isAssignableFrom(routes.get(j).exceptionType())) {
                            warnings.add("onStepError at " + entry.getKey().name() + ": " +
                                    routes.get(i).exceptionType().getSimpleName() + " (index " + i + ") " +
                                    "is a superclass of " + routes.get(j).exceptionType().getSimpleName() +
                                    " (index " + j + ") — the subclass route will never match. Reorder to put specific exceptions first.");
                        }
                    }
                }
            }
            return warnings;
        }

        private void validate(FlowDefinition<S> def) {
            List<String> errors = new ArrayList<>();
            if (def.initialState == null) {
                errors.add("No initial state found (exactly one state must have isInitial()=true)");
            }
            checkReachability(def, errors);
            checkPathToTerminal(def, errors);
            checkDag(def, errors);
            checkExternalUniqueness(def, errors);
            checkBranchCompleteness(def, errors);
            checkRequiresProduces(def, errors);
            checkAutoExternalConflict(def, errors);
            checkSubFlowExitCompleteness(def, errors);
            checkSubFlowNestingDepth(def, errors, 0);
            checkSubFlowCircularRef(def, errors, new java.util.LinkedHashSet<>());
            checkTerminalNoOutgoing(def, errors);

            if (!errors.isEmpty()) {
                throw new FlowException("INVALID_FLOW_DEFINITION",
                        "Flow '" + name + "' has " + errors.size() + " validation error(s):\n  - " +
                                String.join("\n  - ", errors));
            }
        }

        private void checkReachability(FlowDefinition<S> def, List<String> errors) {
            if (def.initialState == null) return;
            Set<S> visited = EnumSet.noneOf(stateClass);
            Deque<S> queue = new ArrayDeque<>();
            queue.add(def.initialState);
            visited.add(def.initialState);
            while (!queue.isEmpty()) {
                S current = queue.poll();
                for (Transition<S> t : def.transitionsFrom(current)) {
                    if (t.isSubFlow()) {
                        for (S target : t.exitMappings().values()) {
                            if (visited.add(target)) queue.add(target);
                        }
                        continue;
                    }
                    if (visited.add(t.to())) queue.add(t.to());
                }
                S errTarget = def.errorTransitions.get(current);
                if (errTarget != null && visited.add(errTarget)) queue.add(errTarget);
            }
            for (S s : stateClass.getEnumConstants()) {
                if (!visited.contains(s) && !s.isTerminal()) {
                    errors.add("State " + s.name() + " is not reachable from " + def.initialState.name());
                }
            }
        }

        private void checkPathToTerminal(FlowDefinition<S> def, List<String> errors) {
            if (def.initialState == null) return;
            Set<S> visited = EnumSet.noneOf(stateClass);
            if (!canReachTerminal(def, def.initialState, visited)) {
                errors.add("No path from " + def.initialState.name() + " to any terminal state");
            }
        }

        private boolean canReachTerminal(FlowDefinition<S> def, S state, Set<S> visited) {
            if (state.isTerminal()) return true;
            if (!visited.add(state)) return false;
            for (Transition<S> t : def.transitionsFrom(state)) {
                // For sub-flow transitions, check exit mappings as reachable targets
                if (t.isSubFlow()) {
                    for (S target : t.exitMappings().values()) {
                        if (canReachTerminal(def, target, visited)) return true;
                    }
                    continue;
                }
                if (canReachTerminal(def, t.to(), visited)) return true;
            }
            S errTarget = def.errorTransitions.get(state);
            return errTarget != null && canReachTerminal(def, errTarget, visited);
        }

        private void checkDag(FlowDefinition<S> def, List<String> errors) {
            Map<S, Set<S>> autoGraph = new EnumMap<>(stateClass);
            for (Transition<S> t : def.transitions) {
                if (t.isAuto() || t.isBranch()) {
                    autoGraph.computeIfAbsent(t.from(), k -> EnumSet.noneOf(stateClass)).add(t.to());
                }
            }
            Set<S> visited = EnumSet.noneOf(stateClass);
            Set<S> inStack = EnumSet.noneOf(stateClass);
            for (S s : stateClass.getEnumConstants()) {
                if (!visited.contains(s) && hasCycle(autoGraph, s, visited, inStack)) {
                    errors.add("Auto/Branch transitions contain a cycle involving " + s.name());
                    break;
                }
            }
        }

        private boolean hasCycle(Map<S, Set<S>> graph, S node, Set<S> visited, Set<S> inStack) {
            visited.add(node);
            inStack.add(node);
            for (S neighbor : graph.getOrDefault(node, Set.of())) {
                if (inStack.contains(neighbor)) return true;
                if (!visited.contains(neighbor) && hasCycle(graph, neighbor, visited, inStack)) return true;
            }
            inStack.remove(node);
            return false;
        }

        private void checkExternalUniqueness(FlowDefinition<S> def, List<String> errors) {
            Map<S, Integer> externalCount = new EnumMap<>(stateClass);
            for (Transition<S> t : def.transitions) {
                if (t.isExternal()) externalCount.merge(t.from(), 1, Integer::sum);
            }
            for (var entry : externalCount.entrySet()) {
                if (entry.getValue() > 1)
                    errors.add("State " + entry.getKey().name() + " has " + entry.getValue() + " external transitions (max 1)");
            }
        }

        private void checkBranchCompleteness(FlowDefinition<S> def, List<String> errors) {
            for (Transition<S> t : def.transitions) {
                if (t.isBranch() && t.branchTargets() != null) {
                    for (var entry : t.branchTargets().entrySet()) {
                        boolean exists = false;
                        for (S s : stateClass.getEnumConstants()) {
                            if (s == entry.getValue()) { exists = true; break; }
                        }
                        if (!exists)
                            errors.add("Branch target '" + entry.getKey() + "' -> " + entry.getValue().name() + " is not a valid state");
                    }
                }
            }
        }

        private void checkRequiresProduces(FlowDefinition<S> def, List<String> errors) {
            if (def.initialState == null) return;
            Map<S, Set<Class<?>>> stateAvailable = new EnumMap<>(stateClass);
            checkRequiresProducesFrom(def, def.initialState, new HashSet<>(initiallyAvailable), stateAvailable, errors);
        }

        private void checkRequiresProducesFrom(FlowDefinition<S> def, S state,
                                                Set<Class<?>> available,
                                                Map<S, Set<Class<?>>> stateAvailable,
                                                List<String> errors) {
            if (stateAvailable.containsKey(state)) {
                Set<Class<?>> existing = stateAvailable.get(state);
                if (existing.containsAll(available)) return;
                existing.retainAll(available);
            } else {
                stateAvailable.put(state, new HashSet<>(available));
            }

            for (Transition<S> t : def.transitionsFrom(state)) {
                Set<Class<?>> newAvailable = new HashSet<>(stateAvailable.get(state));
                if (t.guard() != null) {
                    for (Class<?> req : t.guard().requires()) {
                        if (!newAvailable.contains(req))
                            errors.add("Guard '" + t.guard().name() + "' at " + t.from().name() +
                                    " requires " + req.getSimpleName() + " but it may not be available");
                    }
                    newAvailable.addAll(t.guard().produces());
                }
                if (t.branch() != null) {
                    for (Class<?> req : t.branch().requires()) {
                        if (!newAvailable.contains(req))
                            errors.add("Branch '" + t.branch().name() + "' at " + t.from().name() +
                                    " requires " + req.getSimpleName() + " but it may not be available");
                    }
                }
                if (t.processor() != null) {
                    for (Class<?> req : t.processor().requires()) {
                        if (!newAvailable.contains(req))
                            errors.add("Processor '" + t.processor().name() + "' at " + t.from().name() +
                                    " -> " + t.to().name() + " requires " + req.getSimpleName() +
                                    " but it may not be available");
                    }
                    newAvailable.addAll(t.processor().produces());
                }
                checkRequiresProducesFrom(def, t.to(), newAvailable, stateAvailable, errors);

                // Error path analysis: if processor fails, available set does NOT include
                // the processor's produces. Check error transition target's requirements.
                if (t.processor() != null) {
                    S errorTarget = def.errorTransitions.get(t.from());
                    if (errorTarget != null) {
                        Set<Class<?>> errorAvailable = new HashSet<>(stateAvailable.get(state));
                        // guard produces are available (guard passed before processor ran)
                        if (t.guard() != null) errorAvailable.addAll(t.guard().produces());
                        // but processor produces are NOT available (processor failed)
                        checkRequiresProducesFrom(def, errorTarget, errorAvailable, stateAvailable, errors);
                    }
                }
            }
        }

        private void checkAutoExternalConflict(FlowDefinition<S> def, List<String> errors) {
            for (S state : def.allStates()) {
                List<Transition<S>> trans = def.transitionsFrom(state);
                boolean hasAuto = trans.stream().anyMatch(t -> t.isAuto() || t.isBranch());
                boolean hasExternal = trans.stream().anyMatch(Transition::isExternal);
                if (hasAuto && hasExternal)
                    errors.add("State " + state.name() +
                            " has both auto/branch and external transitions — auto takes priority, making external unreachable");
            }
        }

        private void checkTerminalNoOutgoing(FlowDefinition<S> def, List<String> errors) {
            for (Transition<S> t : def.transitions) {
                if (t.from().isTerminal() && !t.isSubFlow())
                    errors.add("Terminal state " + t.from().name() + " has an outgoing transition to " + t.to().name());
            }
        }

        private void checkSubFlowExitCompleteness(FlowDefinition<S> def, List<String> errors) {
            for (Transition<S> t : def.transitions) {
                if (!t.isSubFlow()) continue;
                var subDef = t.subFlowDefinition();
                if (subDef == null) continue;
                for (var terminal : subDef.terminalStates()) {
                    if (!t.exitMappings().containsKey(terminal.name())) {
                        errors.add("SubFlow '" + subDef.name() + "' at " + t.from().name() +
                                " has terminal state " + terminal.name() +
                                " with no onExit mapping");
                    }
                }
            }
        }

        private void checkSubFlowNestingDepth(FlowDefinition<?> def, List<String> errors, int currentDepth) {
            if (currentDepth > 3) {
                errors.add("SubFlow nesting depth exceeds maximum of 3 (flow: " + def.name() + ")");
                return;
            }
            for (var t : def.transitions()) {
                if (t.isSubFlow() && t.subFlowDefinition() != null) {
                    checkSubFlowNestingDepth(t.subFlowDefinition(), errors, currentDepth + 1);
                }
            }
        }

        private void checkSubFlowCircularRef(FlowDefinition<?> def, List<String> errors, java.util.LinkedHashSet<String> visited) {
            if (!visited.add(def.name())) {
                errors.add("Circular sub-flow reference detected: " + String.join(" -> ", visited) + " -> " + def.name());
                return;
            }
            for (var t : def.transitions()) {
                if (t.isSubFlow() && t.subFlowDefinition() != null) {
                    checkSubFlowCircularRef(t.subFlowDefinition(), errors, new java.util.LinkedHashSet<>(visited));
                }
            }
        }
    }
}
