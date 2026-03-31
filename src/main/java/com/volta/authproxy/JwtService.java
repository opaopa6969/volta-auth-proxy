package com.volta.authproxy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
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
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JwtService {
    private final AppConfig config;
    private final SqlStore store;
    private final KeyCipher keyCipher;
    private volatile RSAKey rsaKey;

    public JwtService(AppConfig config, SqlStore store) {
        this.config = config;
        this.store = store;
        this.keyCipher = new KeyCipher(config.jwtKeyEncryptionSecret());
        this.rsaKey = loadOrCreateKey();
    }

    public String issueToken(AuthPrincipal principal) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(config.jwtIssuer())
                .audience(List.of(config.jwtAudience()))
                .subject(principal.userId().toString())
                .expirationTime(Date.from(now.plusSeconds(config.jwtTtlSeconds())))
                .issueTime(Date.from(now))
                .jwtID(UUID.randomUUID().toString())
                .claim("volta_v", 1)
                .claim("volta_tid", principal.tenantId().toString())
                .claim("volta_roles", principal.roles())
                .claim("volta_display", principal.displayName())
                .claim("volta_tname", principal.tenantName())
                .claim("volta_tslug", principal.tenantSlug())
                .build();
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
            JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
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
            RSAKey publicKey = new RSAKey.Builder(rsaKey.toRSAPublicKey())
                    .keyID(rsaKey.getKeyID())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return JSONObjectUtils.toJSONString(new JWKSet(publicKey).toJSONObject());
        } catch (JOSEException e) {
            throw new RuntimeException(e);
        }
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
            return kid;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
