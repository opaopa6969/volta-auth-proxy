package com.volta.authproxy.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlowContextTest {

    @FlowData("test.alpha")
    record Alpha(String value) {}

    @FlowData("test.beta")
    record Beta(int count) {}

    @Test
    void putAndGet() {
        var ctx = new FlowContext("flow-1");
        ctx.put(Alpha.class, new Alpha("hello"));

        assertEquals("hello", ctx.get(Alpha.class).value());
    }

    @Test
    void getMissingThrowsFlowException() {
        var ctx = new FlowContext("flow-1");
        var ex = assertThrows(FlowException.class, () -> ctx.get(Alpha.class));
        assertEquals("MISSING_CONTEXT", ex.code());
    }

    @Test
    void findReturnsOptional() {
        var ctx = new FlowContext("flow-1");
        assertTrue(ctx.find(Alpha.class).isEmpty());

        ctx.put(Alpha.class, new Alpha("x"));
        assertTrue(ctx.find(Alpha.class).isPresent());
    }

    @Test
    void hasCheck() {
        var ctx = new FlowContext("flow-1");
        assertFalse(ctx.has(Alpha.class));
        ctx.put(Alpha.class, new Alpha("x"));
        assertTrue(ctx.has(Alpha.class));
    }

    @Test
    void snapshotIsUnmodifiable() {
        var ctx = new FlowContext("flow-1");
        ctx.put(Alpha.class, new Alpha("x"));
        var snap = ctx.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snap.put(Beta.class, new Beta(1)));
    }
}
