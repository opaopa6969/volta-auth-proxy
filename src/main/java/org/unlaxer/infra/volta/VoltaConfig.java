package org.unlaxer.infra.volta;

import java.util.List;
import java.util.Map;

/**
 * Typed representation of the {@code idp:} section in {@code volta-config.yaml}.
 *
 * <p>Credentials ({@code client_id}, {@code client_secret}) are resolved
 * from {@code ${ENV_VAR}} references by {@link ConfigLoader} before this
 * record is created.  An entry whose {@code clientId} is blank is treated
 * as disabled (the matching env var was not set).
 */
public record VoltaConfig(int version, List<IdpEntry> idp) {

    public record IdpEntry(String id, String clientId, String clientSecret,
                           Map<String, String> extra) {
        /** True when the client_id resolved to a non-blank value. */
        public boolean isEnabled() {
            return clientId != null && !clientId.isBlank();
        }
    }

    public static VoltaConfig empty() {
        return new VoltaConfig(1, List.of());
    }

    /** True when the YAML file contained an {@code idp:} section. */
    public boolean hasIdpSection() {
        return !idp.isEmpty();
    }
}
