package com.volta.authproxy.flow;

import java.util.Set;

/**
 * Processes a state transition. 1 transition = 1 processor (principle).
 * requires() declares needed FlowContext attributes; produces() declares what it adds.
 */
public interface StateProcessor {
    String name();
    Set<Class<?>> requires();
    Set<Class<?>> produces();
    void process(FlowContext ctx) throws FlowException;
}
