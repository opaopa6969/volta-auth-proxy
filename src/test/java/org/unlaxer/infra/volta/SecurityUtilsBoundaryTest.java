package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 境界値・異常系の追加カバレッジ for {@link SecurityUtils}.
 *
 * <p>既存 {@link SecurityUtilsTest} は happy path と null 系を押さえるが、
 * 認証プロトコル (PKCE / HMAC / 定時間比較) の境界値が未カバー。壊れると
 * タイミング攻ックや署名バイパスに繋がる回帰しやすい分岐:
 * <ul>
 *   <li>{@code pkceChallenge} の空文字入力(RFC 7636 で不正だが挙動は固定したい)</li>
 *   <li>{@code constantTimeEquals} の長さ差が常に false(早期 return の有無を問わず)</li>
 *   <li>{@code constantTimeEquals} の大文字小文字不一致</li>
 *   <li>{@code hmacSha256Hex} の known vector(HMAC-SHA256 の正しさの固定)</li>
 *   <li>{@code hmacSha256Hex} の空 payload / 空 secret でも例外なく安定</li>
 *   <li>{@code randomUrlSafe} の長が仕様どおり</li>
 *   <li>{@code sha256Hex} の空文字列入力の known vector</li>
 * </ul>
 */
class SecurityUtilsBoundaryTest {

    // ── pkceChallenge ──────────────────────────────────────────────────

    @Test
    void pkceChallenge_emptyInputProducesDeterministicHash() {
        // RFC 7636 では空 verifier は不正だが、実装は SHA-256("") の
        // base64url without padding を返す。挙動を固定しておかないと
        // 空入力の扱いが変わったときに壊れる。
        String challenge = SecurityUtils.pkceChallenge("");
        // SHA-256("") を base64url without padding で符号化した値。
        assertEquals("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU", challenge);
    }

    @Test
    void pkceChallenge_isUrlSafeBase64WithoutPadding() {
        // challenge は base64url without padding。+ / / = が出てはいけない。
        String challenge = SecurityUtils.pkceChallenge("some-verifier-value-123");
        assertFalse(challenge.contains("+"));
        assertFalse(challenge.contains("/"));
        assertFalse(challenge.contains("="));
        // 逆に、デコードできること(32 バイト = 43 文字の base64url)。
        assertEquals(32, Base64.getUrlDecoder().decode(challenge).length);
    }

    // ── constantTimeEquals ─────────────────────────────────────────────

    @Test
    void constantTimeEquals_differentLengthsReturnFalse() {
        // 長さが違うとき早期 false でも MessageDigest.isEqual でも結果は false。
        // タイミング差を除くのが目的だが、ここでは結果の正しさを固定する。
        assertFalse(SecurityUtils.constantTimeEquals("abc", "abcd"));
        assertFalse(SecurityUtils.constantTimeEquals("abcd", "abc"));
        assertFalse(SecurityUtils.constantTimeEquals("a", "ab"));
        assertFalse(SecurityUtils.constantTimeEquals("", "a"));
    }

    @Test
    void constantTimeEquals_isCaseSensitive() {
        // 大文字小文字を区別する(UTF-8 バイト比較)。これが壊れると
        // 大文字小文字の違いで署名バイパスが成立する。
        assertFalse(SecurityUtils.constantTimeEquals("ABC", "abc"));
        assertFalse(SecurityUtils.constantTimeEquals("Abc", "abc"));
        assertTrue(SecurityUtils.constantTimeEquals("abc", "abc"));
    }

    @Test
    void constantTimeEquals_unicodeStringsCompareByBytes() {
        // UTF-8 バイト列で比較するので、正規化の違いは等しくならない。
        // "ü" (U+00FC) と "u\u0308" (U+0075 + U+0308) は正規化で同じでも
        // バイト列が違い、constantTimeEquals は false を返す。
        assertFalse(SecurityUtils.constantTimeEquals("ü", "u\u0308"));
        assertTrue(SecurityUtils.constantTimeEquals("ü", "ü"));
    }

    // ── hmacSha256Hex ──────────────────────────────────────────────────

    @Test
    void hmacSha256Hex_knownVectorMatchesRfc4231TestCase1() {
        // RFC 4231 Test Case 1: key=0x0b*20, data="Hi There"
        // ただし実装は secret も payload も UTF-8 文字列として扱うので、
        // 0x0b*20 は作れない。代わりに既知の小ベクタで固定する。
        // HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
        String mac = SecurityUtils.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog");
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", mac);
    }

    @Test
    void hmacSha256Hex_emptyPayloadIsDeterministic() {
        // 空 payload でも例外なく安定して計算できること。
        String a = SecurityUtils.hmacSha256Hex("secret", "");
        String b = SecurityUtils.hmacSha256Hex("secret", "");
        assertEquals(a, b);
        // 64 文字の lowercase hex。
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
    }

    @Test
    void hmacSha256Hex_emptySecretThrows() {
        // 空 secret は javax.crypto が "Empty key" で IllegalArgumentException を投げ、
        // 実装は RuntimeException で包んで投げ直す。運用上は不正だが挙動を固定する。
        assertThrows(RuntimeException.class, () -> SecurityUtils.hmacSha256Hex("", "payload"));
    }

    @Test
    void hmacSha256Hex_nullPayloadThrowsWrappedRuntime() {
        // null payload は getBytes(UTF_8) で NPE になり、実装は catch(Exception) で
        // RuntimeException に包んで投げ直す。呼び出し元が NPE そのものでなく
        // RuntimeException を受け取ることを固定する。
        assertThrows(RuntimeException.class,
                () -> SecurityUtils.hmacSha256Hex("secret", null));
    }

    // ── sha256Hex ──────────────────────────────────────────────────────

    @Test
    void sha256Hex_emptyInputMatchesKnownVector() {
        // SHA-256("") の known vector。空入力でも固定値を返すこと。
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SecurityUtils.sha256Hex(""));
    }

    @Test
    void sha256Hex_nullThrows() {
        // null 入力は NPE。getBytes(UTF_8) で落ちる。挙動を固定。
        assertThrows(NullPointerException.class, () -> SecurityUtils.sha256Hex(null));
    }

    // ── randomUrlSafe ──────────────────────────────────────────────────

    @Test
    void randomUrlSafe_byteLengthMatchesRequested() {
        // base64url without padding の長さは ceil(n*4/3) で決まる。
        // 16 バイト → 22 文字、32 バイト → 43 文字、24 バイト → 32 文字。
        assertEquals(22, SecurityUtils.randomUrlSafe(16).length());
        assertEquals(43, SecurityUtils.randomUrlSafe(32).length());
        assertEquals(32, SecurityUtils.randomUrlSafe(24).length());
    }

    @Test
    void randomUrlSafe_zeroBytesProducesEmptyString() {
        // 0 バイト要求は空文字列。例外なく通ること。
        assertEquals("", SecurityUtils.randomUrlSafe(0));
    }

    @Test
    void randomUrlSafe_twoCallsProduceDifferentValues() {
        // 同じ長さで 2 回呼んでも異なる値(SecureRandom)。
        assertNotEquals(SecurityUtils.randomUrlSafe(16), SecurityUtils.randomUrlSafe(16));
    }

    @Test
    void randomUrlSafe_isUrlSafeWithoutPadding() {
        // 繰り返し呼んで + / / = が出ないことを複数回確認(確率論的だが
        // 100 回やれば出現率はほぼ 0 に近い)。
        for (int i = 0; i < 100; i++) {
            String v = SecurityUtils.randomUrlSafe(32);
            assertFalse(v.contains("+"), "contains + at iteration " + i);
            assertFalse(v.contains("/"), "contains / at iteration " + i);
            assertFalse(v.contains("="), "contains = at iteration " + i);
        }
    }
}
