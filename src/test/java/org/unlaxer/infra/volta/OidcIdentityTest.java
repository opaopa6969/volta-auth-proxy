package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OidcIdentityTest {

    @Test
    void googleSubAliasReturnsSub() {
        var identity = new OidcIdentity(
                "google:123456",
                "user@example.com",
                "Test User",
                true,
                "/console/home",
                null,
                "GOOGLE"
        );
        assertEquals("google:123456", identity.sub());
        assertEquals("google:123456", identity.googleSub());
    }

    @Test
    void fieldAccessors() {
        var identity = new OidcIdentity(
                "github:789",
                "dev@example.com",
                "Dev User",
                false,
                null,
                "invite-code-abc",
                "GITHUB"
        );
        assertEquals("github:789", identity.sub());
        assertEquals("dev@example.com", identity.email());
        assertEquals("Dev User", identity.displayName());
        assertFalse(identity.emailVerified());
        assertNull(identity.returnTo());
        assertEquals("invite-code-abc", identity.inviteCode());
        assertEquals("GITHUB", identity.provider());
    }

    @Test
    void nullFieldsAllowed() {
        var identity = new OidcIdentity(null, null, null, false, null, null, null);
        assertNull(identity.sub());
        assertNull(identity.googleSub());
        assertNull(identity.email());
    }
}
