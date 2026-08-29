package org.unlaxer.infra.volta.flow;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 境界値・異常系の追加カバレッジ for {@link OidcStateCodec}.
 *
 * <p>既存 {@link OidcStateCodecTest} は round-trip / tamper / 別鍵 / garbage の
 * 概観を押さえるが、OIDC state は改竄対策の要で壊れると open redirect や
 * CSRF に繋がるため、以下の境界を固定する:
 * <ul>
 *   <li>flowId / nonce に空文字を渡したときの挙動</li>
 *   <li>flowId に {@code :} を含むときの firstColon 解析順序</li>
 *   <li>nonce に {@code :} を含むとき flowId だけを取り出せること</li>
 *   <li>null state 入力</li>
 *   <li>mac 部分だけを改竄したときの拒否</li>
 * </ul>
 */
class OidcStateCodecBoundaryTest {

    private final OidcStateCodec codec = new OidcStateCodec("test-hmac-key-32chars-minimum!!");

    @Test
    void encode_emptyFlowIdRoundTrips() {
        // flowId="" でも payload=":nonce" になり、HMAC は計算される。
        // decode は firstColon=0 で flowId="" を返す。これが壊れると
        // 空の flow_id を許容する経路が壊れる。
        String state = codec.encode("", "nonce");
        Optional<String> decoded = codec.decode(state);
        assertTrue(decoded.isPresent());
        assertEquals("", decoded.get());
    }

    @Test
    void encode_emptyNonceRoundTrips() {
        // nonce="" のとき payload="flowId:"。decode は lastColon で
        // mac を分離し、firstColon で flowId を取り出す。
        String state = codec.encode("flow-1", "");
        Optional<String> decoded = codec.decode(state);
        assertTrue(decoded.isPresent());
        assertEquals("flow-1", decoded.get());
    }

    @Test
    void encode_bothEmptyRoundTrips() {
        // flowId="" nonce="" → payload=":" → lastColon で mac を分離し、
        // 残り ":" の firstColon=0 で flowId="" が返る。
        String state = codec.encode("", "");
        Optional<String> decoded = codec.decode(state);
        assertTrue(decoded.isPresent(), "flowId と nonce 両方空でも decode できる");
        assertEquals("", decoded.get());
    }

    @Test
    void flowIdWithColonsIsParsedByFirstColon() {
        // flowId に ":" が含まれる場合、firstColon で split するので
        // flowId 側に ":" が残る。コロン区切り形式なので flowId 自体に
        // コロンを含めるべきではないが、現実装の挙動を固定する。
        // 例: flowId="a:b", nonce="n" → payload="a:b:n" → firstColon=1 → flowId="a"
        String state = codec.encode("a:b", "n");
        Optional<String> decoded = codec.decode(state);
        assertTrue(decoded.isPresent());
        assertEquals("a", decoded.get());
    }

    @Test
    void nonceWithColonsDoesNotAffectFlowIdExtraction() {
        // nonce に ":" が含まれていても、payload は flowId + ":" + nonce なので
        // firstColon で flowId が正しく取れる。これが壊れると nonce に ":" を
        // 含む正当な state が拒否される。
        String state = codec.encode("flow-123", "n:o:t:t:h:e:r");
        Optional<String> decoded = codec.decode(state);
        assertTrue(decoded.isPresent());
        assertEquals("flow-123", decoded.get());
    }

    @Test
    void decode_nullStateReturnsEmpty() {
        // null 入力は例外ではなく empty。呼び出し元が NPE で落ちないこと。
        assertTrue(codec.decode(null).isEmpty());
    }

    @Test
    void encodeProducesUrlSafeBase64() {
        // state は URL query に載るので +, /, = が出てはいけない。
        String state = codec.encode("flow-with-special", "nonce-12345");
        assertFalse(state.contains("+"));
        assertFalse(state.contains("/"));
        assertFalse(state.contains("="));
    }

    @Test
    void decode_stateWithCorruptedMacReturnsEmpty() {
        // mac 部分だけを改竄: constantTimeEquals が false → empty。
        String state = codec.encode("flow-1", "nonce-1");
        // state は base64url。最後の数文字を入れ替えて mac を壊す。
        String tampered = state.substring(0, state.length() - 4)
                + (state.charAt(state.length() - 4) == 'A' ? "BBBB" : "AAAA");
        assertTrue(codec.decode(tampered).isEmpty());
    }
}
