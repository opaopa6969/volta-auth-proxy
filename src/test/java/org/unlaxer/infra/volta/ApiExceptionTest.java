package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionTest {

    @Test
    void statusCodeIsPreserved() {
        var ex = new ApiException(403, "ROLE_INSUFFICIENT", "Access denied");
        assertEquals(403, ex.status());
    }

    @Test
    void errorCodeIsPreserved() {
        var ex = new ApiException(404, "NOT_FOUND", "Resource missing");
        assertEquals("NOT_FOUND", ex.code());
    }

    @Test
    void messageIsPreserved() {
        var ex = new ApiException(400, "BAD_REQUEST", "Invalid input");
        assertEquals("Invalid input", ex.getMessage());
    }

    @Test
    void isARuntimeException() {
        var ex = new ApiException(500, "INTERNAL", "Server error");
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void unauthorizedVariant() {
        var ex = new ApiException(401, "UNAUTHENTICATED", "Login required");
        assertEquals(401, ex.status());
        assertEquals("UNAUTHENTICATED", ex.code());
    }
}
