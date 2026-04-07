package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppRegistryTest {
    @Test
    void resolveByIdAndHost() {
        AppRegistry registry = new AppRegistry(List.of(
                new AppRegistry.AppPolicy("app-wiki", "https://wiki.example.com", List.of("MEMBER", "ADMIN")),
                new AppRegistry.AppPolicy("app-admin", "https://admin.example.com", List.of("ADMIN"))
        ));

        assertEquals("app-wiki", registry.resolve("app-wiki", null).orElseThrow().id());
        assertEquals("app-admin", registry.resolve(null, "admin.example.com").orElseThrow().id());
        assertTrue(registry.resolve("missing", null).isEmpty());
    }
}
