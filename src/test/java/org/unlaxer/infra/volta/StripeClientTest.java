package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** SAAS-008: StripeClient form encoding + isConfigured behaviour. */
class StripeClientTest {

    @Test
    void unconfiguredRejectsCalls() {
        StripeClient c = new StripeClient("");
        assertFalse(c.isConfigured());
        var ex = assertThrows(ApiException.class,
                () -> c.createCheckoutSession("price_x", "https://a", "https://b", "t", null));
        assertEquals(503, ex.status());
        assertEquals("STRIPE_NOT_CONFIGURED", ex.code());
    }

    @Test
    void nullSecretIsTreatedAsUnconfigured() {
        StripeClient c = new StripeClient(null);
        assertFalse(c.isConfigured());
    }

    @Test
    void encodeFormRoundTrip() {
        var form = new LinkedHashMap<String, String>();
        form.put("mode", "subscription");
        form.put("line_items[0][price]", "price_ABC");
        form.put("metadata[tenant_id]", "00000000-0000-0000-0000-000000000001");
        String encoded = StripeClient.encodeForm(form);
        assertTrue(encoded.contains("mode=subscription"));
        // Brackets and dashes must be percent-encoded
        assertTrue(encoded.contains("line_items%5B0%5D%5Bprice%5D=price_ABC"));
        assertTrue(encoded.contains("metadata%5Btenant_id%5D=00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void encodeFormEscapesSpecialChars() {
        var form = new LinkedHashMap<String, String>();
        form.put("k", "value with spaces & ampersand");
        String encoded = StripeClient.encodeForm(form);
        // URLEncoder uses '+' for space.
        assertTrue(encoded.contains("value+with+spaces+%26+ampersand"));
    }

    @Test
    void encodeFormHandlesEmpty() {
        assertEquals("", StripeClient.encodeForm(new LinkedHashMap<>()));
    }

    @Test
    void encodeFormTreatsNullValueAsEmpty() {
        var form = new LinkedHashMap<String, String>();
        form.put("k", null);
        assertEquals("k=", StripeClient.encodeForm(form));
    }
}
