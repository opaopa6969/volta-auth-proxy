package org.unlaxer.infra.volta.flow;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OidcStateCodecTest {
    private final OidcStateCodec codec = new OidcStateCodec("test-hmac-key-32chars-minimum!!");

    @Test
    void encodeAndDecode_roundTrips() {
        String flowId = "550e8400-e29b-41d4-a716-446655440000";
        String nonce = "abc123";

        String state = codec.encode(flowId, nonce);
        assertNotNull(state);
        assertFalse(state.isBlank());

        Optional<String> decoded = codec.decode(state);
        assertTrue(decoded.isPresent());
        assertEquals(flowId, decoded.get());
    }

    @Test
    void decode_tamperedState_returnsEmpty() {
        String state = codec.encode("flow-123", "nonce-456");
        // Tamper with the state
        String tampered = state.substring(0, state.length() - 2) + "XX";

        assertTrue(codec.decode(tampered).isEmpty());
    }

    @Test
    void decode_garbageInput_returnsEmpty() {
        assertTrue(codec.decode("not-base64!@#$").isEmpty());
        assertTrue(codec.decode("").isEmpty());
        assertTrue(codec.decode("aGVsbG8=").isEmpty()); // valid base64 but wrong format
    }

    @Test
    void differentKeys_cannotDecodeEachOther() {
        var codec1 = new OidcStateCodec("key-one-aaaaaaaaaaaaaaaa");
        var codec2 = new OidcStateCodec("key-two-bbbbbbbbbbbbbbbb");

        String state = codec1.encode("flow-1", "nonce-1");
        assertTrue(codec2.decode(state).isEmpty());
    }

    @Test
    void sameFlowId_differentNonces_produceDifferentStates() {
        String s1 = codec.encode("flow-1", "nonce-a");
        String s2 = codec.encode("flow-1", "nonce-b");
        assertNotEquals(s1, s2);
    }
}
