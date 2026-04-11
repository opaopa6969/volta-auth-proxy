package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;

final class SamlService {
    private final ObjectMapper objectMapper = new ObjectMapper();

    SamlIdentity parseIdentity(
            String samlResponseB64,
            SqlStore.IdpConfigRecord idp,
            boolean devMode,
            boolean skipSignature,
            String expectedAcsUrl,
            String expectedRequestId
    ) {
        if (samlResponseB64 == null || samlResponseB64.isBlank()) {
            throw new ApiException(400, "SAML_INVALID_RESPONSE", "SAMLResponse is required");
        }
        String xml = new String(Base64.getDecoder().decode(samlResponseB64), StandardCharsets.UTF_8);
        if (devMode && xml.startsWith("MOCK:")) {
            // Only allow MOCK SAML in development with explicit DEV_MODE=true AND non-production base URL
            String baseUrl = System.getenv("BASE_URL");
            boolean isLocalDev = baseUrl == null || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");
            if (!isLocalDev) {
                throw new ApiException(400, "SAML_INVALID_RESPONSE", "MOCK SAML not allowed in production");
            }
            String email = xml.substring("MOCK:".length()).trim();
            if (email.isBlank() || !email.contains("@")) {
                throw new ApiException(400, "SAML_INVALID_RESPONSE", "mock email is invalid");
            }
            return new SamlIdentity(email, email.split("@")[0], idp.issuer() == null ? "mock-idp" : idp.issuer());
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            if (!skipSignature) {
                NodeList signatures = doc.getElementsByTagNameNS("*", "Signature");
                if (signatures == null || signatures.getLength() == 0) {
                    throw new ApiException(401, "SAML_SIGNATURE_REQUIRED", "SAML signature validation is required");
                }
                if (idp.x509Cert() == null || idp.x509Cert().isBlank()) {
                    throw new ApiException(401, "SAML_SIGNATURE_REQUIRED", "IdP certificate is required");
                }
                X509Certificate cert = parseCertificate(idp.x509Cert());
                DOMValidateContext validateContext = new DOMValidateContext(cert.getPublicKey(), signatures.item(0));
                validateContext.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
                XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
                XMLSignature signature = factory.unmarshalXMLSignature(validateContext);
                boolean valid = signature.validate(validateContext);
                if (!valid) {
                    throw new ApiException(401, "SAML_INVALID_SIGNATURE", "SAML signature validation failed");
                }
            }

            String issuer = textOf(doc, "Issuer");
            if (idp.issuer() != null && !idp.issuer().isBlank() && !idp.issuer().equals(issuer)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "issuer mismatch");
            }
            String destination = attributeOf(doc, "Response", "Destination");
            if (destination != null && !destination.isBlank()
                    && expectedAcsUrl != null && !expectedAcsUrl.isBlank()
                    && !expectedAcsUrl.equals(destination)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "destination mismatch");
            }
            String recipient = attributeOf(doc, "SubjectConfirmationData", "Recipient");
            if (recipient != null && !recipient.isBlank()
                    && expectedAcsUrl != null && !expectedAcsUrl.isBlank()
                    && !expectedAcsUrl.equals(recipient)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "recipient mismatch");
            }
            String responseInResponseTo = attributeOf(doc, "Response", "InResponseTo");
            String subjectInResponseTo = attributeOf(doc, "SubjectConfirmationData", "InResponseTo");
            String inResponseTo = responseInResponseTo != null && !responseInResponseTo.isBlank() ? responseInResponseTo : subjectInResponseTo;
            if (expectedRequestId != null && !expectedRequestId.isBlank()) {
                if (inResponseTo == null || inResponseTo.isBlank() || !expectedRequestId.equals(inResponseTo)) {
                    throw new ApiException(401, "SAML_INVALID_RESPONSE", "in_response_to mismatch");
                }
            }
            String audience = textOf(doc, "Audience");
            if (idp.clientId() != null && !idp.clientId().isBlank() && !idp.clientId().equals(audience)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "audience mismatch");
            }
            String notOnOrAfter = attributeOf(doc, "SubjectConfirmationData", "NotOnOrAfter");
            if (notOnOrAfter != null && !notOnOrAfter.isBlank()) {
                try {
                    Instant expiry = Instant.parse(notOnOrAfter);
                    if (expiry.isBefore(Instant.now())) {
                        throw new ApiException(401, "SAML_INVALID_RESPONSE", "assertion expired");
                    }
                } catch (DateTimeParseException e) {
                    throw new ApiException(400, "SAML_INVALID_RESPONSE", "invalid NotOnOrAfter");
                }
            }
            String email = textOf(doc, "NameID");
            if (email == null || email.isBlank() || !email.contains("@")) {
                email = findAttributeValue(doc, "email");
            }
            if (email == null || email.isBlank() || !email.contains("@")) {
                email = findAttributeValue(doc, "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");
            }
            if (email == null || email.isBlank() || !email.contains("@")) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "email claim not found");
            }
            String displayName = findAttributeValue(doc, "displayName");
            if (displayName == null || displayName.isBlank()) {
                displayName = findAttributeValue(doc, "name");
            }
            if (displayName == null || displayName.isBlank()) {
                displayName = email.split("@")[0];
            }
            return new SamlIdentity(email, displayName, issuer);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "SAML_INVALID_RESPONSE", "invalid saml response");
        }
    }

    String encodeRelayState(Map<String, Object> relay) {
        try {
            String json = objectMapper.writeValueAsString(relay);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    RelayState decodeRelayState(String relayStateRaw) {
        if (relayStateRaw == null || relayStateRaw.isBlank()) {
            return new RelayState(null, null, null);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(relayStateRaw);
            JsonNode node = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            String tenantId = node.path("tenant_id").isMissingNode() ? null : node.path("tenant_id").asText(null);
            String returnTo = node.path("return_to").isMissingNode() ? null : node.path("return_to").asText(null);
            String requestId = node.path("request_id").isMissingNode() ? null : node.path("request_id").asText(null);
            return new RelayState(tenantId, returnTo, requestId);
        } catch (Exception e) {
            return new RelayState(null, relayStateRaw, null);
        }
    }

    private static String textOf(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes == null || nodes.getLength() == 0 || nodes.item(0) == null) return null;
        String v = nodes.item(0).getTextContent();
        return v == null ? null : v.trim();
    }

    private static String attributeOf(Document doc, String localName, String attributeName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes == null || nodes.getLength() == 0 || nodes.item(0) == null) return null;
        var attr = nodes.item(0).getAttributes() == null ? null : nodes.item(0).getAttributes().getNamedItem(attributeName);
        return attr == null ? null : attr.getTextContent();
    }

    private static String findAttributeValue(Document doc, String attributeName) {
        NodeList attributes = doc.getElementsByTagNameNS("*", "Attribute");
        for (int i = 0; i < attributes.getLength(); i++) {
            var node = attributes.item(i);
            var nameNode = node.getAttributes() == null ? null : node.getAttributes().getNamedItem("Name");
            if (nameNode == null) continue;
            if (!attributeName.equals(nameNode.getTextContent())) continue;
            var values = ((org.w3c.dom.Element) node).getElementsByTagNameNS("*", "AttributeValue");
            if (values.getLength() > 0 && values.item(0) != null) {
                String v = values.item(0).getTextContent();
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return null;
    }

    private static X509Certificate parseCertificate(String pem) {
        try {
            String normalized = pem
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new ApiException(400, "SAML_CERT_INVALID", "Invalid SAML x509 certificate");
        }
    }

    record SamlIdentity(String email, String displayName, String issuer) {
    }

    record RelayState(String tenantId, String returnTo, String requestId) {
    }
}
