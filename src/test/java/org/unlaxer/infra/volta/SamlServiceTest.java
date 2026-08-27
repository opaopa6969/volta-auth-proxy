package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class SamlServiceTest {
    private static final String RELAY_STATE_KEY = "test-relay-state-hmac-key";
    private final SamlService service = new SamlService(RELAY_STATE_KEY);
    private final SqlStore.IdpConfigRecord idp = new SqlStore.IdpConfigRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SAML",
            null,
            "https://idp.example.com/issuer",
            "volta-sp-audience",
            null,
            true,
            Instant.now()
    );

    @Test
    void parsesMockIdentityInDevMode() {
        String mock = "MOCK:alice@example.com";
        String saml = Base64.getEncoder().encodeToString(mock.getBytes(StandardCharsets.UTF_8));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, true, true, null, null);
        assertEquals("alice@example.com", identity.email());
    }

    @Test
    void allowsMockSamlOnlyForExactLocalHosts() {
        assertTrue(SamlService.isLocalDevBaseUrl(null));
        assertTrue(SamlService.isLocalDevBaseUrl("http://localhost:7070"));
        assertTrue(SamlService.isLocalDevBaseUrl("http://127.0.0.1:7070"));

        assertFalse(SamlService.isLocalDevBaseUrl("https://localhost.attacker.com"));
        assertFalse(SamlService.isLocalDevBaseUrl("https://evil-127.0.0.1.example.com"));
        assertFalse(SamlService.isLocalDevBaseUrl("https://example.com/path/localhost"));
        assertFalse(SamlService.isLocalDevBaseUrl("not a valid base url"));
    }

    @Test
    void parsesSamlXmlIdentity() {
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>https://idp.example.com/issuer</saml:Issuer>
                  <saml:Assertion>
                    <saml:Conditions>
                      <saml:AudienceRestriction>
                        <saml:Audience>volta-sp-audience</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:Subject>
                      <saml:NameID>bob@example.com</saml:NameID>
                      <saml:SubjectConfirmation>
                        <saml:SubjectConfirmationData NotOnOrAfter="2999-01-01T00:00:00Z"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                  </saml:Assertion>
                </samlp:Response>
                """;
        String saml = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null);
        assertEquals("bob@example.com", identity.email());
        assertEquals("bob", identity.displayName());
    }

    @Test
    void encodesAndDecodesRelayState() {
        String encoded = service.encodeRelayState(Map.of(
                "tenant_id", "11111111-1111-1111-1111-111111111111",
                "return_to", "https://app.example.com/path"
        ));
        SamlService.RelayState state = service.decodeRelayState(encoded);
        assertEquals("11111111-1111-1111-1111-111111111111", state.tenantId());
        assertEquals("https://app.example.com/path", state.returnTo());
        assertNull(state.requestId());
    }

    @Test
    void rejectsTamperedRelayState() {
        String encoded = service.encodeRelayState(Map.of("tenant_id", "original-tenant"));
        String tampered = (encoded.charAt(0) == 'A' ? "B" : "A") + encoded.substring(1);

        ApiException ex = assertThrows(ApiException.class, () -> service.decodeRelayState(tampered));
        assertEquals(400, ex.status());
        assertEquals("SAML_INVALID_RELAY_STATE", ex.code());
    }

    @Test
    void rejectsUnsignedRelayState() {
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"tenant_id\":\"attacker-controlled\"}".getBytes(StandardCharsets.UTF_8));

        ApiException ex = assertThrows(ApiException.class, () -> service.decodeRelayState(unsigned));
        assertEquals(400, ex.status());
        assertEquals("SAML_INVALID_RELAY_STATE", ex.code());
    }

    @Test
    void rejectsRelayStateSignedWithDifferentKey() {
        String encoded = service.encodeRelayState(Map.of("tenant_id", "original-tenant"));
        SamlService serviceWithDifferentKey = new SamlService("different-relay-state-hmac-key");

        ApiException ex = assertThrows(ApiException.class,
                () -> serviceWithDifferentKey.decodeRelayState(encoded));
        assertEquals("SAML_INVALID_RELAY_STATE", ex.code());
    }

    @Test
    void rejectsIssuerMismatch() {
        String xml = """
                <Response xmlns="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>https://wrong.example.com/issuer</saml:Issuer>
                  <saml:Assertion><saml:Subject><saml:NameID>a@example.com</saml:NameID></saml:Subject></saml:Assertion>
                </Response>
                """;
        String saml = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, true, null, null));
        assertEquals("SAML_INVALID_RESPONSE", ex.code());
    }

    @Test
    void requiresSignatureWhenSkipDisabled() {
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>https://idp.example.com/issuer</saml:Issuer>
                </samlp:Response>
                """;
        String saml = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, false, null, null));
        assertEquals("SAML_SIGNATURE_REQUIRED", ex.code());
    }

    @Test
    void rejectsNotOnOrAfterExpired() {
        String saml = samlResponseWithNotOnOrAfter(Instant.now().minus(10, ChronoUnit.MINUTES));
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null));
        assertEquals("SAML_INVALID_RESPONSE", ex.code());
    }

    @Test
    void allowsNotOnOrAfterJustInsideClockSkew() {
        String saml = samlResponseWithNotOnOrAfter(Instant.now().minus(4, ChronoUnit.MINUTES));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null);
        assertEquals("bob@example.com", identity.email());
    }

    @Test
    void allowsNotOnOrAfterAtSkewBoundary() {
        String saml = samlResponseWithNotOnOrAfter(Instant.now().minus(4, ChronoUnit.MINUTES).minus(55, ChronoUnit.SECONDS));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null);
        assertEquals("bob@example.com", identity.email());
    }

    @Test
    void rejectsNotOnOrAfterJustBeyondSkew() {
        String saml = samlResponseWithNotOnOrAfter(Instant.now().minus(5, ChronoUnit.MINUTES).minus(5, ChronoUnit.SECONDS));
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null));
        assertEquals("SAML_INVALID_RESPONSE", ex.code());
    }

    // --- #61: Conditions/@NotBefore 検証 ---

    @Test
    void rejectsNotBeforeFarInTheFuture() {
        String saml = samlResponseWithNotBefore(Instant.now().plus(10, ChronoUnit.MINUTES));
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null));
        assertEquals("SAML_INVALID_RESPONSE", ex.code());
    }

    @Test
    void allowsNotBeforeJustInsideClockSkew() {
        String saml = samlResponseWithNotBefore(Instant.now().plus(4, ChronoUnit.MINUTES));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null);
        assertEquals("bob@example.com", identity.email());
    }

    @Test
    void allowsNotBeforeAtSkewBoundary() {
        String saml = samlResponseWithNotBefore(Instant.now().plus(4, ChronoUnit.MINUTES).plus(55, ChronoUnit.SECONDS));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null);
        assertEquals("bob@example.com", identity.email());
    }

    @Test
    void rejectsNotBeforeJustBeyondSkew() {
        String saml = samlResponseWithNotBefore(Instant.now().plus(5, ChronoUnit.MINUTES).plus(5, ChronoUnit.SECONDS));
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null));
        assertEquals("SAML_INVALID_RESPONSE", ex.code());
    }

    @Test
    void allowsNotBeforeInThePast() {
        String saml = samlResponseWithNotBefore(Instant.now().minus(10, ChronoUnit.MINUTES));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null);
        assertEquals("bob@example.com", identity.email());
    }

    @Test
    void allowsMissingNotBefore() {
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>https://idp.example.com/issuer</saml:Issuer>
                  <saml:Assertion>
                    <saml:Conditions>
                      <saml:AudienceRestriction>
                        <saml:Audience>volta-sp-audience</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:Subject>
                      <saml:NameID>bob@example.com</saml:NameID>
                      <saml:SubjectConfirmation>
                        <saml:SubjectConfirmationData NotOnOrAfter="2999-01-01T00:00:00Z"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                  </saml:Assertion>
                </samlp:Response>
                """;
        String saml = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null);
        assertEquals("bob@example.com", identity.email());
    }

    @Test
    void rejectsInvalidNotBeforeFormat() {
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>https://idp.example.com/issuer</saml:Issuer>
                  <saml:Assertion>
                    <saml:Conditions NotBefore="not-a-date">
                      <saml:AudienceRestriction>
                        <saml:Audience>volta-sp-audience</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:Subject>
                      <saml:NameID>bob@example.com</saml:NameID>
                      <saml:SubjectConfirmation>
                        <saml:SubjectConfirmationData NotOnOrAfter="2999-01-01T00:00:00Z"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                  </saml:Assertion>
                </samlp:Response>
                """;
        String saml = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, true, "https://sp.example.com/callback", null));
        assertEquals("SAML_INVALID_RESPONSE", ex.code());
    }

    private static String samlResponseWithNotOnOrAfter(Instant notOnOrAfter) {
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>https://idp.example.com/issuer</saml:Issuer>
                  <saml:Assertion>
                    <saml:Conditions>
                      <saml:AudienceRestriction>
                        <saml:Audience>volta-sp-audience</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:Subject>
                      <saml:NameID>bob@example.com</saml:NameID>
                      <saml:SubjectConfirmation>
                        <saml:SubjectConfirmationData NotOnOrAfter="%s"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(notOnOrAfter.toString());
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String samlResponseWithNotBefore(Instant notBefore) {
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>https://idp.example.com/issuer</saml:Issuer>
                  <saml:Assertion>
                    <saml:Conditions NotBefore="%s">
                      <saml:AudienceRestriction>
                        <saml:Audience>volta-sp-audience</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:Subject>
                      <saml:NameID>bob@example.com</saml:NameID>
                      <saml:SubjectConfirmation>
                        <saml:SubjectConfirmationData NotOnOrAfter="2999-01-01T00:00:00Z"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(notBefore.toString());
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }
}
