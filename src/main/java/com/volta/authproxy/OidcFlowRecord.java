package com.volta.authproxy;

import java.time.Instant;

public record OidcFlowRecord(
        String state,
        String nonce,        // null for GitHub (no id_token)
        String codeVerifier, // null for GitHub (no PKCE)
        String returnTo,
        String inviteCode,
        Instant expiresAt,
        String provider      // GOOGLE | GITHUB | MICROSOFT
) {
}
