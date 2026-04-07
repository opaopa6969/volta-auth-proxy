package org.unlaxer.infra.volta;

public record OidcIdentity(
        String sub,           // "google:<sub>" | "github:<id>" | "microsoft:<sub>"
        String email,
        String displayName,
        boolean emailVerified,
        String returnTo,
        String inviteCode,
        String provider       // GOOGLE | GITHUB | MICROSOFT
) {
    /** Backwards-compatible alias. */
    public String googleSub() { return sub; }
}
