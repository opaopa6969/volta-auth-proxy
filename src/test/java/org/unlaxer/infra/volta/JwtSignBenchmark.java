package org.unlaxer.infra.volta;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Microbenchmark for RS256 JWT signing — the hot path of /auth/verify.
 *
 * Run with: mvn test -Dtest=JwtSignBenchmark -Dbenchmark=true
 * Disabled by default; only runs when -Dbenchmark=true is set so CI is unaffected.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class JwtSignBenchmark {

    @Test
    void measureRs256SignThroughput() throws Exception {
        RSAKey key = generateKey("bench-key");
        RSASSASigner signer = new RSASSASigner(key.toPrivateKey());

        // Warmup
        for (int i = 0; i < 2_000; i++) {
            signOnce(signer, key.getKeyID());
        }

        // Measure
        int iterations = 20_000;
        long[] nanos = new long[iterations];
        // avoid GC noise: run a few rounds, take the best
        long bestTotal = Long.MAX_VALUE;
        int rounds = 5;
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                signOnce(signer, key.getKeyID());
            }
            long total = System.nanoTime() - start;
            if (total < bestTotal) bestTotal = total;
        }

        double avgNanos = bestTotal / (double) iterations;
        double opsPerSec = 1_000_000_000.0 / avgNanos;
        double avgMicros = avgNanos / 1000.0;

        System.out.println();
        System.out.println("=== RS256 JWT sign benchmark ===");
        System.out.printf("Iterations per round : %,d%n", iterations);
        System.out.printf("Rounds (best of)    : %d%n", rounds);
        System.out.printf("Avg per sign        : %.2f us%n", avgMicros);
        System.out.printf("Throughput (single) : %,.0f ops/s%n", opsPerSec);
        System.out.printf("Total (best round)  : %.3f ms%n", bestTotal / 1_000_000.0);
        System.out.println("================================");
    }

    private static String signOnce(RSASSASigner signer, String kid) throws JOSEException {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("volta-auth")
                .audience(List.of("volta-apps"))
                .subject(UUID.randomUUID().toString())
                .expirationTime(new Date(now.getTime() + 300_000))
                .issueTime(now)
                .jwtID(UUID.randomUUID().toString())
                .claim("volta_v", 1)
                .claim("volta_tid", UUID.randomUUID().toString())
                .claim("volta_roles", List.of("MEMBER"))
                .claim("volta_display", "bench-user")
                .claim("volta_tname", "tenant")
                .claim("volta_tslug", "slug")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).type(JOSEObjectType.JWT).build(),
                claims
        );
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static RSAKey generateKey(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair kp = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                    .privateKey((RSAPrivateKey) kp.getPrivate())
                    .keyID(kid)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
