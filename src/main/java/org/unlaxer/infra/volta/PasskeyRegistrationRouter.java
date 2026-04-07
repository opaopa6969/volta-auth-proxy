package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PasskeyRegistrationRouter {
    private final AppConfig config;
    private final SqlStore store;
    private final AuthService authService;
    private final SessionStore sessionStore;
    private final AuditService auditService;
    private final PolicyEngine policy;
    private final KeyCipher secretCipher;
    private final ObjectMapper objectMapper;

    public PasskeyRegistrationRouter(AppConfig config, SqlStore store, AuthService authService,
                                     SessionStore sessionStore, AuditService auditService,
                                     PolicyEngine policy, KeyCipher secretCipher,
                                     ObjectMapper objectMapper) {
        this.config = config;
        this.store = store;
        this.authService = authService;
        this.sessionStore = sessionStore;
        this.auditService = auditService;
        this.policy = policy;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
    }

    public void register(Javalin app) {
        app.post("/api/v1/users/{userId}/passkeys/register/start", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self registration is allowed");
            }
            byte[] challenge = new byte[32];
            new java.security.SecureRandom().nextBytes(challenge);
            // Store challenge in session-scoped attribute via a simple in-memory map (TTL managed by caller)
            String challengeB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
            sessionStore.setPasskeyChallenge(ctx.cookie(AuthService.SESSION_COOKIE), challengeB64);

            UserRecord user = store.findUserById(userId).orElseThrow();
            List<SqlStore.PasskeyRecord> existing = store.listPasskeys(userId);

            // Build excludeCredentials from existing passkeys
            var excludeList = existing.stream().map(pk ->
                Map.of("type", "public-key",
                       "id", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(pk.credentialId()))
            ).toList();

            ctx.json(Map.of(
                "challenge", challengeB64,
                "rp", Map.of("id", config.webauthnRpId(), "name", config.webauthnRpName()),
                "user", Map.of(
                    "id", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                            userId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "name", user.email(),
                    "displayName", user.displayName() != null ? user.displayName() : user.email()
                ),
                "pubKeyCredParams", List.of(
                    Map.of("type", "public-key", "alg", -7),   // ES256
                    Map.of("type", "public-key", "alg", -257)  // RS256
                ),
                "timeout", 300000,
                "attestation", "none",
                "authenticatorSelection", Map.of(
                    "residentKey", "preferred",
                    "userVerification", "preferred"
                ),
                "excludeCredentials", excludeList
            ));
        });

        app.post("/api/v1/users/{userId}/passkeys/register/finish", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self registration is allowed");
            }
            String storedChallenge = sessionStore.getPasskeyChallenge(ctx.cookie(AuthService.SESSION_COOKIE));
            if (storedChallenge == null) {
                throw new ApiException(400, "CHALLENGE_EXPIRED", "Registration challenge expired or not found");
            }
            sessionStore.clearPasskeyChallenge(ctx.cookie(AuthService.SESSION_COOKIE));

            com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(ctx.body());
            String credentialIdB64 = body.path("id").asText();
            com.fasterxml.jackson.databind.JsonNode response = body.path("response");
            String clientDataJsonB64 = response.path("clientDataJSON").asText();
            String attestationObjectB64 = response.path("attestationObject").asText();

            byte[] credentialId = java.util.Base64.getUrlDecoder().decode(credentialIdB64);
            byte[] clientDataJson = java.util.Base64.getUrlDecoder().decode(clientDataJsonB64);
            byte[] attestationObject = java.util.Base64.getUrlDecoder().decode(attestationObjectB64);

            // Parse and validate using webauthn4j
            com.webauthn4j.data.RegistrationRequest registrationRequest = new com.webauthn4j.data.RegistrationRequest(
                    attestationObject, clientDataJson);
            com.webauthn4j.data.RegistrationParameters registrationParameters = new com.webauthn4j.data.RegistrationParameters(
                    new com.webauthn4j.server.ServerProperty(
                            new com.webauthn4j.data.client.Origin(config.webauthnRpOrigin()),
                            config.webauthnRpId(),
                            new com.webauthn4j.data.client.challenge.DefaultChallenge(java.util.Base64.getUrlDecoder().decode(storedChallenge)),
                            null),
                    null, false, true);

            com.webauthn4j.WebAuthnManager webAuthnManager = com.webauthn4j.WebAuthnManager.createNonStrictWebAuthnManager();
            com.webauthn4j.data.RegistrationData registrationData = webAuthnManager.validate(registrationRequest, registrationParameters);

            com.webauthn4j.data.attestation.authenticator.AttestedCredentialData attestedCred =
                    registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
            byte[] publicKeyBytes = new com.webauthn4j.converter.util.ObjectConverter()
                    .getCborConverter().writeValueAsBytes(attestedCred.getCOSEKey());
            long signCount = registrationData.getAttestationObject().getAuthenticatorData().getSignCount();
            UUID aaguid = attestedCred.getAaguid() != null ?
                    java.util.UUID.nameUUIDFromBytes(attestedCred.getAaguid().getBytes()) : null;

            // BE/BS flags
            boolean backupEligible = registrationData.getAttestationObject().getAuthenticatorData().isFlagBE();
            boolean backupState = registrationData.getAttestationObject().getAuthenticatorData().isFlagBS();

            // Transports
            String transports = body.path("response").path("transports") != null ?
                    body.path("response").path("transports").toString() : null;

            String passkeyName = body.path("name").asText("Passkey");

            UUID passkeyId = store.createPasskey(userId, credentialId, publicKeyBytes, signCount,
                    transports, passkeyName, aaguid, backupEligible, backupState);

            auditService.log(ctx, "PASSKEY_REGISTERED", p, "PASSKEY", passkeyId.toString(), Map.of());
            ctx.status(201).json(Map.of("ok", true, "id", passkeyId.toString()));
        });

        app.get("/api/v1/users/{userId}/passkeys", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            if (!p.userId().equals(userId)) {
                policy.enforceMinRole(p, "ADMIN");
            }
            var passkeys = store.listPasskeys(userId).stream().map(pk -> {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", pk.id().toString());
                m.put("name", pk.name() != null ? pk.name() : "Passkey");
                m.put("createdAt", pk.createdAt().toString());
                m.put("lastUsedAt", pk.lastUsedAt() != null ? pk.lastUsedAt().toString() : null);
                m.put("backupEligible", pk.backupEligible());
                m.put("backupState", pk.backupState());
                return m;
            }).toList();
            ctx.json(Map.of("items", passkeys));
        });

        app.delete("/api/v1/users/{userId}/passkeys/{passkeyId}", ctx -> {
            AuthPrincipal p = ctx.attribute("principal");
            UUID userId = UUID.fromString(ctx.pathParam("userId"));
            UUID passkeyId = UUID.fromString(ctx.pathParam("passkeyId"));
            if (!p.userId().equals(userId)) {
                throw new ApiException(403, "FORBIDDEN", "Only self deletion is allowed");
            }
            int deleted = store.deletePasskey(userId, passkeyId);
            if (deleted == 0) {
                throw new ApiException(404, "NOT_FOUND", "Passkey not found");
            }
            auditService.log(ctx, "PASSKEY_DELETED", p, "PASSKEY", passkeyId.toString(), Map.of());
            ctx.json(Map.of("ok", true));
        });
    }
}
