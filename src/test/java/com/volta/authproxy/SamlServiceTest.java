package com.volta.authproxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class SamlServiceTest {
    private final SamlService service = new SamlService();
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
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, true, true);
        assertEquals("alice@example.com", identity.email());
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
        SamlService.SamlIdentity identity = service.parseIdentity(saml, idp, false, true);
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
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, true));
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
        ApiException ex = assertThrows(ApiException.class, () -> service.parseIdentity(saml, idp, false, false));
        assertEquals("SAML_SIGNATURE_REQUIRED", ex.code());
    }
}
