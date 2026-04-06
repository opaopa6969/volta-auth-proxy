package com.volta.authproxy.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlowDataRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @FlowData("reg.alpha")
    record Alpha(String name) {}

    @FlowData("reg.beta")
    record Beta(int count) {}

    @FlowData("reg.alpha") // duplicate alias
    record AlphaDuplicate(String other) {}

    @Test
    void registerAndLookup() {
        var registry = new FlowDataRegistry(mapper);
        registry.register(Alpha.class);
        registry.register(Beta.class);

        assertEquals("reg.alpha", registry.aliasFor(Alpha.class));
        assertEquals(Alpha.class, registry.classFor("reg.alpha"));
        assertEquals("reg.beta", registry.aliasFor(Beta.class));
    }

    @Test
    void duplicateAliasThrows() {
        var registry = new FlowDataRegistry(mapper);
        registry.register(Alpha.class);
        assertThrows(FlowException.class, () -> registry.register(AlphaDuplicate.class));
    }

    @Test
    void unregisteredClassThrows() {
        var registry = new FlowDataRegistry(mapper);
        assertThrows(IllegalArgumentException.class, () -> registry.aliasFor(Alpha.class));
    }

    @Test
    void unknownAliasThrows() {
        var registry = new FlowDataRegistry(mapper);
        assertThrows(IllegalArgumentException.class, () -> registry.classFor("nonexistent"));
    }

    @Test
    void serializeAndDeserialize() {
        var registry = new FlowDataRegistry(mapper);
        registry.register(Alpha.class);
        registry.register(Beta.class);

        var ctx = new FlowContext("flow-1");
        ctx.put(Alpha.class, new Alpha("hello"));
        ctx.put(Beta.class, new Beta(42));

        var serialized = registry.serialize(ctx);
        assertEquals(2, serialized.size());
        assertTrue(serialized.containsKey("reg.alpha"));
        assertTrue(serialized.containsKey("reg.beta"));

        // Deserialize into new context
        var ctx2 = new FlowContext("flow-2");
        registry.deserializeInto(ctx2, serialized);
        assertEquals("hello", ctx2.get(Alpha.class).name());
        assertEquals(42, ctx2.get(Beta.class).count());
    }
}
