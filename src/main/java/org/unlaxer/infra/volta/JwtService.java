package org.unlaxer.infra.volta;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JwtService {
    private final AppConfig config;
    private final SqlStore store;
    private final KeyCipher keyCipher;
    // The active signing key. All newly minted JWTs are signed with this key.
    private volatile RSAKey rsaKey;
    // Verification key set, keyed by kid. Contains the active key plus any
    // "rotated" (retiring) keys still within their grace period, so JWTs issued
    // under a previous key remain verifiable until the old key is revoked.
    // This is what gets published to the JWKS endpoint.
    private volatile Map<String, RSAKey> verificationKeys;

    public JwtService(AppConfig config, SqlStore store) {
        this.config = config;
        this.store = store;
        this.keyCipher = new KeyCipher(config.jwtKeyEncryptionSecret());
        this.rsaKey = loadOrCreateKey();
        this.verificationKeys = loadVerificationKeys();
    }

    public String issueToken(AuthPrincipal principal) {
        return issueToken(principal, List.of(config.jwtAudience()), Map.of());
    }

    public String issueToken(AuthPrincipal principal, List<String> audience, Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(config.jwtIssuer())
                .audience(audience == null || audience.isEmpty() ? List.of(config.jwtAudience()) : audience)
                .subject(principal.userId().toString())
                .expirationTime(Date.from(now.plusSeconds(config.jwtTtlSeconds())))
                .issueTime(Date.from(now))
                .jwtID(UUID.randomUUID().toString())
                .claim("volta_v", 1)
                .claim("volta_tid", principal.tenantId().toString())
                .claim("volta_roles", principal.roles())
                .claim("volta_display", principal.displayName())
                .claim("volta_tname", principal.tenantName())
                .claim("volta_tslug", principal.tenantSlug());
        if (extraClaims != null) {
            for (Map.Entry<String, Object> entry : extraClaims.entrySet()) {
                builder.claim(entry.getKey(), entry.getValue());
            }
        }
        JWTClaimsSet claims = builder.build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims
            );
            jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("Unsupported JWT algorithm");
            }
            RSAKey verifyKey = selectVerificationKey(jwt.getHeader().getKeyID());
            JWSVerifier verifier = new RSASSAVerifier(verifyKey.toRSAPublicKey());
            if (!jwt.verify(verifier)) {
                throw new IllegalArgumentException("Invalid JWT signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!config.jwtIssuer().equals(claims.getIssuer())) {
                throw new IllegalArgumentException("Invalid issuer");
            }
            if (!claims.getAudience().contains(config.jwtAudience())) {
                throw new IllegalArgumentException("Invalid audience");
            }
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                throw new IllegalArgumentException("Token expired");
            }
            return claims.getClaims();
        } catch (ParseException | JOSEException e) {
            throw new IllegalArgumentException("Failed to verify token", e);
        }
    }

    public String jwksJson() {
        try {
            List<JWK> publicKeys = new ArrayList<>();
            // Publish every verification key (active + retiring) so relying
            // parties can validate JWTs signed with either the current or a
            // recently rotated key. The active key is listed first.
            for (RSAKey key : verificationKeys.values()) {
                publicKeys.add(new RSAKey.Builder(key.toRSAPublicKey())
                        .keyID(key.getKeyID())
                        .algorithm(JWSAlgorithm.RS256)
                        .build());
            }
            if (publicKeys.isEmpty()) {
                publicKeys.add(new RSAKey.Builder(rsaKey.toRSAPublicKey())
                        .keyID(rsaKey.getKeyID())
                        .algorithm(JWSAlgorithm.RS256)
                        .build());
            }
            return JSONObjectUtils.toJSONString(new JWKSet(publicKeys).toJSONObject());
        } catch (JOSEException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Picks the verification key for an incoming JWT. When the token carries a
     * {@code kid} we look it up in the published key set (active + retiring).
     * For backward compatibility with tokens that have no kid — or a single-key
     * deployment — we fall back to the active signing key.
     */
    private RSAKey selectVerificationKey(String kid) {
        RSAKey match = selectKey(this.verificationKeys, this.rsaKey, kid);
        if (match != this.rsaKey || kid == null || this.rsaKey.getKeyID().equals(kid)) {
            return match;
        }
        // Unknown kid: the key may have been added after this instance cached
        // its set (e.g. another node rotated). Reload once and retry.
        Map<String, RSAKey> reloaded = loadVerificationKeys();
        this.verificationKeys = reloaded;
        return selectKey(reloaded, this.rsaKey, kid);
    }

    /**
     * Pure key-selection used by {@link #verify}. Returns the key matching
     * {@code kid}; if {@code kid} is null or not present, falls back to the
     * active signing key (backward compatibility with single-key / no-kid
     * tokens). Package-private and static so it is unit-testable without a DB.
     */
    static RSAKey selectKey(Map<String, RSAKey> keys, RSAKey activeKey, String kid) {
        if (kid != null) {
            RSAKey match = keys.get(kid);
            if (match != null) {
                return match;
            }
        }
        return activeKey;
    }

    private Map<String, RSAKey> loadVerificationKeys() {
        Map<String, RSAKey> keys = new LinkedHashMap<>();
        for (SqlStore.SigningKeyRecord record : store.loadVerificationSigningKeys()) {
            RSAKey key = restoreKey(record);
            keys.put(key.getKeyID(), key);
        }
        // Always include the in-memory active key so a freshly created key is
        // usable for verification even before the next reload.
        if (rsaKey != null) {
            keys.putIfAbsent(rsaKey.getKeyID(), rsaKey);
        }
        return keys;
    }

    private RSAKey loadOrCreateKey() {
        return store.loadActiveSigningKey()
                .map(this::restoreKey)
                .orElseGet(this::createKey);
    }

    private RSAKey restoreKey(SqlStore.SigningKeyRecord keyRecord) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(keyCipher.decrypt(keyRecord.publicPem())))
            );
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyCipher.decrypt(keyRecord.privatePem())))
            );
            return new RSAKey.Builder((RSAPublicKey) publicKey)
                    .privateKey((RSAPrivateKey) privateKey)
                    .keyID(keyRecord.kid())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RSAKey createKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String kid = "key-" + Instant.now().truncatedTo(ChronoUnit.MINUTES).toString().replace(":", "-");
            RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(kid)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            store.saveSigningKey(
                    kid,
                    keyCipher.encrypt(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())),
                    keyCipher.encrypt(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()))
            );
            return key;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized String rotateKey() {
        RSAKey current = this.rsaKey;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String kid = "key-" + Instant.now().truncatedTo(ChronoUnit.MINUTES).toString().replace(":", "-");
            RSAKey next = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(kid)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            store.rotateSigningKey(
                    current.getKeyID(),
                    kid,
                    keyCipher.encrypt(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())),
                    keyCipher.encrypt(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()))
            );
            this.rsaKey = next;
            // Reload so the new active key and the just-retired (rotated) key are
            // both present in the verification set / JWKS during the grace period.
            this.verificationKeys = loadVerificationKeys();
            return kid;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String issueM2mToken(UUID clientPrincipalId, UUID tenantId, List<String> scopes, List<String> audience, String clientId) {
        AuthPrincipal principal = new AuthPrincipal(
                clientPrincipalId,
                "m2m@" + clientId,
                clientId,
                tenantId,
                "machine",
                "machine",
                scopes,
                true
        );
        return issueToken(principal, audience, Map.of("volta_client", true, "volta_client_id", clientId));
    }
}
