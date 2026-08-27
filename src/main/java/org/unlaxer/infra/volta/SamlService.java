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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
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
            if (!isLocalDevBaseUrl(baseUrl)) {
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
            // 署名済みで、かつ claim の抽出元にしてよい要素。
            // skipSignature（開発用）のときは文書全体を対象にする。
            org.w3c.dom.Element signedScope = null;

            if (!skipSignature) {
                NodeList signatures = doc.getElementsByTagNameNS("*", "Signature");
                if (signatures == null || signatures.getLength() == 0) {
                    throw new ApiException(401, "SAML_SIGNATURE_REQUIRED", "SAML signature validation is required");
                }
                // #33 (XSW): 署名が複数あると「どれを検証したか」と「どこから claim を
                // 読むか」がずれる余地が生まれる。SAML Response に署名は1つで足りる
                // （Response か Assertion のいずれか）ので、複数は拒否する。
                if (signatures.getLength() > 1) {
                    throw new ApiException(401, "SAML_INVALID_SIGNATURE",
                            "multiple Signature elements are not allowed");
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

                // #33 (XSW): 署名が通っただけでは足りない。**何に署名されたのか**を
                // 確定し、claim はその要素の中からしか読まない。
                //
                // 攻撃の形: 正規の署名済み Assertion をそのまま残し、その外側に
                // 攻撃者が作った Assertion を差し込む（signature wrapping）。署名検証は
                // 正規部分に対して成功するので通り、後段が文書全体から
                // getElementsByTagNameNS("*", "NameID") 等で最初の一致を拾うと
                // **攻撃者側の値が使われる**。
                signedScope = resolveSignedScope(doc, signature);
            }

            org.w3c.dom.Node scope = signedScope != null ? signedScope : doc;

            String issuer = textOf(scope, "Issuer");
            if (idp.issuer() != null && !idp.issuer().isBlank() && !idp.issuer().equals(issuer)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "issuer mismatch");
            }
            String destination = attributeOf(scope, "Response", "Destination");
            if (destination != null && !destination.isBlank()
                    && expectedAcsUrl != null && !expectedAcsUrl.isBlank()
                    && !expectedAcsUrl.equals(destination)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "destination mismatch");
            }
            String recipient = attributeOf(scope, "SubjectConfirmationData", "Recipient");
            if (recipient != null && !recipient.isBlank()
                    && expectedAcsUrl != null && !expectedAcsUrl.isBlank()
                    && !expectedAcsUrl.equals(recipient)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "recipient mismatch");
            }
            String responseInResponseTo = attributeOf(scope, "Response", "InResponseTo");
            String subjectInResponseTo = attributeOf(scope, "SubjectConfirmationData", "InResponseTo");
            String inResponseTo = responseInResponseTo != null && !responseInResponseTo.isBlank() ? responseInResponseTo : subjectInResponseTo;
            if (expectedRequestId != null && !expectedRequestId.isBlank()) {
                if (inResponseTo == null || inResponseTo.isBlank() || !expectedRequestId.equals(inResponseTo)) {
                    throw new ApiException(401, "SAML_INVALID_RESPONSE", "in_response_to mismatch");
                }
            }
            String audience = textOf(scope, "Audience");
            if (idp.clientId() != null && !idp.clientId().isBlank() && !idp.clientId().equals(audience)) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "audience mismatch");
            }
            String notOnOrAfter = attributeOf(scope, "SubjectConfirmationData", "NotOnOrAfter");
            if (notOnOrAfter != null && !notOnOrAfter.isBlank()) {
                try {
                    Instant expiry = Instant.parse(notOnOrAfter);
                    if (expiry.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
                        throw new ApiException(401, "SAML_INVALID_RESPONSE", "assertion expired");
                    }
                } catch (DateTimeParseException e) {
                    throw new ApiException(400, "SAML_INVALID_RESPONSE", "invalid NotOnOrAfter");
                }
            }
            String email = textOf(scope, "NameID");
            if (email == null || email.isBlank() || !email.contains("@")) {
                email = findAttributeValue(scope, "email");
            }
            if (email == null || email.isBlank() || !email.contains("@")) {
                email = findAttributeValue(scope, "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");
            }
            if (email == null || email.isBlank() || !email.contains("@")) {
                throw new ApiException(401, "SAML_INVALID_RESPONSE", "email claim not found");
            }
            String displayName = findAttributeValue(scope, "displayName");
            if (displayName == null || displayName.isBlank()) {
                displayName = findAttributeValue(scope, "name");
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

    static boolean isLocalDevBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return true;
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
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

    /** #33: Document 全体ではなく「署名対象要素」を起点に探せるよう Node を取る。 */
    private static String textOf(org.w3c.dom.Node scope, String localName) {
        NodeList nodes = elementsIn(scope, localName);
        if (nodes == null || nodes.getLength() == 0 || nodes.item(0) == null) return null;
        String v = nodes.item(0).getTextContent();
        return v == null ? null : v.trim();
    }

    private static String attributeOf(org.w3c.dom.Node scope, String localName, String attributeName) {
        NodeList nodes = elementsIn(scope, localName);
        if (nodes == null || nodes.getLength() == 0 || nodes.item(0) == null) return null;
        var attr = nodes.item(0).getAttributes() == null ? null : nodes.item(0).getAttributes().getNamedItem(attributeName);
        return attr == null ? null : attr.getTextContent();
    }

    private static String findAttributeValue(org.w3c.dom.Node scope, String attributeName) {
        NodeList attributes = elementsIn(scope, "Attribute");
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

    /**
     * #33 (XSW): 検証済み署名が「何に」署名しているのかを確定する。
     *
     * SignedInfo の Reference URI（{@code #<ID>}）から対象要素を引き、それが
     * Response か Assertion であることを確認して返す。空 URI（文書全体への署名）は
     * ルート要素を対象とみなす。
     *
     * 判定を通らなければ 401。「署名は通ったが対象が特定できない」ものを
     * 受け入れると、ラップされた要素から claim を読む余地が残る。
     */
    private static org.w3c.dom.Element resolveSignedScope(Document doc, XMLSignature signature) {
        var references = signature.getSignedInfo().getReferences();
        if (references == null || references.size() != 1) {
            // SAML の Response/Assertion 署名は Reference 1本。複数あるものは
            // 「どこまでが署名対象か」が曖昧になるので受けない。
            throw new ApiException(401, "SAML_INVALID_SIGNATURE",
                    "exactly one Reference is required in SignedInfo");
        }
        Object ref = references.get(0);
        String uri = ((javax.xml.crypto.dsig.Reference) ref).getURI();

        org.w3c.dom.Element target;
        if (uri == null || uri.isEmpty()) {
            // 空 URI = 文書全体（enveloped signature）
            target = doc.getDocumentElement();
        } else if (uri.startsWith("#")) {
            String id = uri.substring(1);
            target = findElementById(doc, id);
            if (target == null) {
                throw new ApiException(401, "SAML_INVALID_SIGNATURE",
                        "Reference URI does not resolve to an element in this document");
            }
        } else {
            // 外部参照（http://... 等）は受けない
            throw new ApiException(401, "SAML_INVALID_SIGNATURE", "external Reference URI is not allowed");
        }

        String localName = target.getLocalName() != null ? target.getLocalName() : target.getNodeName();
        if (!"Response".equals(localName) && !"Assertion".equals(localName)) {
            throw new ApiException(401, "SAML_INVALID_SIGNATURE",
                    "signature must cover Response or Assertion, got: " + localName);
        }
        return target;
    }

    /**
     * ID 属性で要素を引く。
     *
     * {@code Document.getElementById} は DTD/スキーマで ID 型が宣言されていないと
     * 使えない（SAML は DTD を無効化しているので常に null）。SAML の慣習である
     * {@code ID} 属性を自前で走査する。**同じ ID が複数あれば拒否**する
     * （ID 重複は XSW の典型的な下準備）。
     */
    private static org.w3c.dom.Element findElementById(Document doc, String id) {
        NodeList all = doc.getElementsByTagName("*");
        org.w3c.dom.Element found = null;
        for (int i = 0; i < all.getLength(); i++) {
            org.w3c.dom.Node node = all.item(i);
            if (!(node instanceof org.w3c.dom.Element el)) continue;
            var attrs = el.getAttributes();
            if (attrs == null) continue;
            var idAttr = attrs.getNamedItem("ID");
            if (idAttr == null) idAttr = attrs.getNamedItem("Id");
            if (idAttr == null) continue;
            if (id.equals(idAttr.getTextContent())) {
                if (found != null) {
                    throw new ApiException(401, "SAML_INVALID_SIGNATURE",
                            "duplicate ID in document: " + id);
                }
                found = el;
            }
        }
        return found;
    }

    /**
     * scope 配下の要素を localName で引く。
     *
     * Document を渡せば従来どおり文書全体、Element を渡せばその子孫だけを見る。
     * #33 の要点は後者で、**署名対象の外側にある要素を claim として読まない**こと。
     */
    private static NodeList elementsIn(org.w3c.dom.Node scope, String localName) {
        if (scope instanceof Document d) {
            return d.getElementsByTagNameNS("*", localName);
        }
        if (scope instanceof org.w3c.dom.Element e) {
            // scope 自身が探している要素の場合もある（Assertion 署名で Assertion を探す等）
            String own = e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
            NodeList descendants = e.getElementsByTagNameNS("*", localName);
            if (localName.equals(own)) {
                return new SingleThenList(e, descendants);
            }
            return descendants;
        }
        return new SingleThenList(null, null);
    }

    /** scope 自身を先頭に、その子孫を続けて返す NodeList。 */
    private record SingleThenList(org.w3c.dom.Element head, NodeList rest) implements NodeList {
        @Override public org.w3c.dom.Node item(int index) {
            if (head == null) return rest == null ? null : rest.item(index);
            if (index == 0) return head;
            return rest == null ? null : rest.item(index - 1);
        }
        @Override public int getLength() {
            int restLen = rest == null ? 0 : rest.getLength();
            return (head == null ? 0 : 1) + restLen;
        }
    }

}
