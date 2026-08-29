package org.unlaxer.infra.volta.flow.mfa;

import org.junit.jupiter.api.Test;
import org.unlaxer.infra.volta.AppConfig;
import org.unlaxer.infra.volta.AppRegistry;
import org.unlaxer.infra.volta.SqlStore;
import org.unlaxer.infra.volta.TenancyPolicy;
import org.unlaxer.infra.volta.VoltaConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #70: MFA 完了後の open redirect — resolveRedirect が未検証の returnTo を
 * 拒否し、ALLOWED_REDIRECT_DOMAINS に許可されたホストのみ通すことを検証する。
 *
 * <p>未検証 returnTo は fall back 経路に入り store.findTenantsByUser を呼ぶため、
 * store 依存のない 2 ケース(検証済み・invite)で検証ロジックを担保し、未検証 case は
 * store=null で fall back に到達したことを NPE で間接確認する。
 */
class MfaVerifyProcessorRedirectTest {

    private final AppConfig config = new AppConfig(
            7070, "localhost", 54329, "volta_auth", "volta", "volta",
            "http://localhost:7070", "", "", "http://localhost:7070/callback",
            "", "", "", "", "",
            "localhost,127.0.0.1,app.example.com", false, "tok", "volta-auth", "volta-apps",
            300, 28800, "dev-only-secret-change-me", "volta-config.yaml", "",
            "postgres", "redis://localhost:6379", true, false, 3, 15,
            "none", "", 587, "", "", "noreply@example.com", "", false,
            "postgres", "", "volta-audit", "", "", "", "", "", "",
            "localhost", "volta-auth", "http://localhost:7070", "", "", "", "",
            "dev-only-hmac-key-change-me", "", "", "");

    private final AppRegistry appRegistry = new AppRegistry(List.of());
    private final TenancyPolicy tenancy = new TenancyPolicy((VoltaConfig) null);
    private final SqlStore store = null;  // fall back 経路でのみ呼ばれる

    @Test
    void allowlistedReturnToIsPassedThrough() {
        var session = new MfaFlowData.MfaSessionContext(
                UUID.randomUUID(), UUID.randomUUID(), "https://app.example.com/home");
        String redirect = MfaVerifyProcessor.resolveRedirect(
                session, config, store, appRegistry, tenancy);
        assertEquals("https://app.example.com/home", redirect);
    }

    @Test
    void invitePrefixedReturnToIsPassedThrough() {
        var session = new MfaFlowData.MfaSessionContext(
                UUID.randomUUID(), UUID.randomUUID(), "invite:ABC123");
        String redirect = MfaVerifyProcessor.resolveRedirect(
                session, config, store, appRegistry, tenancy);
        assertEquals("/invite/ABC123", redirect);
    }

    @Test
    void disallowedReturnToFallsBackAndReachesStoreLookup() {
        var session = new MfaFlowData.MfaSessionContext(
                UUID.randomUUID(), UUID.randomUUID(), "https://evil.example.com/");
        // 検証失敗 → fall back → store.findTenantsByUser が呼ばれる。
        // store=null なので NPE になる = fall back 経路に到達した証拠。
        assertThrows(NullPointerException.class, () ->
                MfaVerifyProcessor.resolveRedirect(session, config, store, appRegistry, tenancy));
    }
}
