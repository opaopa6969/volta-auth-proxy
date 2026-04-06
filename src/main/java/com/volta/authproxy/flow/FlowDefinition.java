package com.volta.authproxy.flow;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable flow definition built via DSL. Validated at build() time with 8 checks.
 */
public final class FlowDefinition<S extends Enum<S> & FlowState> {
    private final String name;
    private final Class<S> stateClass;
    private final Duration ttl;
    private final int maxGuardRetries;
    private final List<Transition<S>> transitions;
    private final Map<S, S> errorTransitions;
    private final S initialState;
    private final Set<S> terminalStates;

    private FlowDefinition(String name, Class<S> stateClass, Duration ttl, int maxGuardRetries,
                           List<Transition<S>> transitions, Map<S, S> errorTransitions) {
        this.name = name;
        this.stateClass = stateClass;
        this.ttl = ttl;
        this.maxGuardRetries = maxGuardRetries;
        this.transitions = List.copyOf(transitions);
        this.errorTransitions = Map.copyOf(errorTransitions);

        S initial = null;
        Set<S> terminals = EnumSet.noneOf(stateClass);
        for (S s : stateClass.getEnumConstants()) {
            if (s.isInitial()) initial = s;
            if (s.isTerminal()) terminals.add(s);
        }
        this.initialState = initial;
        this.terminalStates = Collections.unmodifiableSet(terminals);
    }

    public String name() { return name; }
    public Class<S> stateClass() { return stateClass; }
    public Duration ttl() { return ttl; }
    public int maxGuardRetries() { return maxGuardRetries; }
    public List<Transition<S>> transitions() { return transitions; }
    public Map<S, S> errorTransitions() { return errorTransitions; }
    public S initialState() { return initialState; }
    public Set<S> terminalStates() { return terminalStates; }

    /** All transitions from a given state. */
    public List<Transition<S>> transitionsFrom(S state) {
        return transitions.stream().filter(t -> t.from() == state).toList();
    }

    /** Find the single external transition from a state (at most one per spec). */
    public Optional<Transition<S>> externalFrom(S state) {
        return transitions.stream()
                .filter(t -> t.from() == state && t.isExternal())
                .findFirst();
    }

    /** All states defined in the enum. */
    public Set<S> allStates() {
        return EnumSet.allOf(stateClass);
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

        public Builder<S> onAnyError(S errorState) {
            for (S s : stateClass.getEnumConstants()) {
                if (!s.isTerminal()) {
                    errorTransitions.put(s, errorState);
                }
            }
            return this;
        }

        public final class FromBuilder {
            private final S from;

            private FromBuilder(S from) {
                this.from = from;
            }

            public Builder<S> auto(S to, StateProcessor processor) {
                transitions.add(new Transition<>(from, to, TransitionType.AUTO, processor, null, null, Map.of()));
                return Builder.this;
            }

            public Builder<S> external(S to, TransitionGuard guard) {
                transitions.add(new Transition<>(from, to, TransitionType.EXTERNAL, null, guard, null, Map.of()));
                return Builder.this;
            }

            public Builder<S> external(S to, TransitionGuard guard, StateProcessor processor) {
                transitions.add(new Transition<>(from, to, TransitionType.EXTERNAL, processor, guard, null, Map.of()));
                return Builder.this;
            }

            public BranchBuilder branch(BranchProcessor branch) {
                return new BranchBuilder(from, branch);
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
                // Create one transition per branch target
                for (var entry : targets.entrySet()) {
                    StateProcessor proc = processors.get(entry.getKey());
                    transitions.add(new Transition<>(from, entry.getValue(), TransitionType.BRANCH,
                            proc, null, branch, Map.copyOf(targets)));
                }
                return Builder.this;
            }
        }

        public FlowDefinition<S> build() {
            var def = new FlowDefinition<>(name, stateClass, ttl, maxGuardRetries, transitions, errorTransitions);
            validate(def);
            return def;
        }

        private void validate(FlowDefinition<S> def) {
            List<String> errors = new ArrayList<>();

            // 1. All states reachable from initial
            checkReachability(def, errors);

            // 2. Path from initial to terminal exists
            checkPathToTerminal(def, errors);

            // 3. Auto/Branch transitions form a DAG (no cycles)
            checkDag(def, errors);

            // 4. At most one External per state
            checkExternalUniqueness(def, errors);

            // 5. Branch targets all defined
            checkBranchCompleteness(def, errors);

            // 6. requires/produces chain integrity
            checkRequiresProduces(def, errors);

            // 7. No transitions from terminal states
            checkTerminalNoOutgoing(def, errors);

            // 8. Initial state exists
            if (def.initialState == null) {
                errors.add("No initial state found (exactly one state must have isInitial()=true)");
            }

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
                    if (visited.add(t.to())) {
                        queue.add(t.to());
                    }
                }
                // Also follow error transitions
                S errTarget = def.errorTransitions.get(current);
                if (errTarget != null && visited.add(errTarget)) {
                    queue.add(errTarget);
                }
            }
            for (S s : stateClass.getEnumConstants()) {
                // Terminal states may be reached by engine-level mechanisms (TTL, guard maxout)
                // so only non-terminal unreachable states are errors
                if (!visited.contains(s) && !s.isTerminal()) {
                    errors.add("State " + s.name() + " is not reachable from " + def.initialState.name());
                }
            }
        }

        private void checkPathToTerminal(FlowDefinition<S> def, List<String> errors) {
            if (def.initialState == null) return;
            // DFS from initial, see if we can reach any terminal
            Set<S> visited = EnumSet.noneOf(stateClass);
            if (!canReachTerminal(def, def.initialState, visited)) {
                errors.add("No path from " + def.initialState.name() + " to any terminal state");
            }
        }

        private boolean canReachTerminal(FlowDefinition<S> def, S state, Set<S> visited) {
            if (state.isTerminal()) return true;
            if (!visited.add(state)) return false;
            for (Transition<S> t : def.transitionsFrom(state)) {
                if (canReachTerminal(def, t.to(), visited)) return true;
            }
            S errTarget = def.errorTransitions.get(state);
            if (errTarget != null && canReachTerminal(def, errTarget, visited)) return true;
            return false;
        }

        private void checkDag(FlowDefinition<S> def, List<String> errors) {
            // Only auto and branch transitions must be DAG
            Map<S, Set<S>> autoGraph = new EnumMap<>(stateClass);
            for (Transition<S> t : def.transitions) {
                if (t.isAuto() || t.isBranch()) {
                    autoGraph.computeIfAbsent(t.from(), k -> EnumSet.noneOf(stateClass)).add(t.to());
                }
            }
            Set<S> visited = EnumSet.noneOf(stateClass);
            Set<S> inStack = EnumSet.noneOf(stateClass);
            for (S s : stateClass.getEnumConstants()) {
                if (!visited.contains(s)) {
                    if (hasCycle(autoGraph, s, visited, inStack)) {
                        errors.add("Auto/Branch transitions contain a cycle involving " + s.name());
                        break;
                    }
                }
            }
        }

        private boolean hasCycle(Map<S, Set<S>> graph, S node, Set<S> visited, Set<S> inStack) {
            visited.add(node);
            inStack.add(node);
            Set<S> neighbors = graph.getOrDefault(node, Set.of());
            for (S neighbor : neighbors) {
                if (inStack.contains(neighbor)) return true;
                if (!visited.contains(neighbor) && hasCycle(graph, neighbor, visited, inStack)) return true;
            }
            inStack.remove(node);
            return false;
        }

        private void checkExternalUniqueness(FlowDefinition<S> def, List<String> errors) {
            Map<S, Integer> externalCount = new EnumMap<>(stateClass);
            for (Transition<S> t : def.transitions) {
                if (t.isExternal()) {
                    externalCount.merge(t.from(), 1, Integer::sum);
                }
            }
            for (var entry : externalCount.entrySet()) {
                if (entry.getValue() > 1) {
                    errors.add("State " + entry.getKey().name() + " has " + entry.getValue() + " external transitions (max 1)");
                }
            }
        }

        private void checkBranchCompleteness(FlowDefinition<S> def, List<String> errors) {
            for (Transition<S> t : def.transitions) {
                if (t.isBranch() && t.branchTargets() != null) {
                    for (var entry : t.branchTargets().entrySet()) {
                        S target = entry.getValue();
                        boolean exists = false;
                        for (S s : stateClass.getEnumConstants()) {
                            if (s == target) { exists = true; break; }
                        }
                        if (!exists) {
                            errors.add("Branch target '" + entry.getKey() + "' -> " + target.name() + " is not a valid state");
                        }
                    }
                }
            }
        }

        private void checkRequiresProduces(FlowDefinition<S> def, List<String> errors) {
            if (def.initialState == null) return;
            // Walk all paths from initial, track what's produced
            Set<S> visited = EnumSet.noneOf(stateClass);
            checkRequiresProducesFrom(def, def.initialState, new HashSet<>(initiallyAvailable), visited, errors);
        }

        private void checkRequiresProducesFrom(FlowDefinition<S> def, S state,
                                                Set<Class<?>> available, Set<S> visited,
                                                List<String> errors) {
            if (!visited.add(state)) return;
            for (Transition<S> t : def.transitionsFrom(state)) {
                Set<Class<?>> newAvailable = new HashSet<>(available);

                // Check guard requires
                if (t.guard() != null) {
                    for (Class<?> req : t.guard().requires()) {
                        if (!newAvailable.contains(req)) {
                            errors.add("Guard '" + t.guard().name() + "' at " + t.from().name() +
                                    " requires " + req.getSimpleName() + " but it may not be available");
                        }
                    }
                    newAvailable.addAll(t.guard().produces());
                }

                // Check branch requires
                if (t.branch() != null) {
                    for (Class<?> req : t.branch().requires()) {
                        if (!newAvailable.contains(req)) {
                            errors.add("Branch '" + t.branch().name() + "' at " + t.from().name() +
                                    " requires " + req.getSimpleName() + " but it may not be available");
                        }
                    }
                }

                // Check processor requires
                if (t.processor() != null) {
                    for (Class<?> req : t.processor().requires()) {
                        if (!newAvailable.contains(req)) {
                            errors.add("Processor '" + t.processor().name() + "' at " + t.from().name() +
                                    " → " + t.to().name() + " requires " + req.getSimpleName() +
                                    " but it may not be available");
                        }
                    }
                    newAvailable.addAll(t.processor().produces());
                }

                checkRequiresProducesFrom(def, t.to(), newAvailable, visited, errors);
            }
        }

        private void checkTerminalNoOutgoing(FlowDefinition<S> def, List<String> errors) {
            for (Transition<S> t : def.transitions) {
                if (t.from().isTerminal()) {
                    errors.add("Terminal state " + t.from().name() + " has an outgoing transition to " + t.to().name());
                }
            }
        }
    }
}
