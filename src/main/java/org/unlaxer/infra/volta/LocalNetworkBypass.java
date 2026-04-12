package org.unlaxer.infra.volta;

import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Local-network bypass for ForwardAuth.
 *
 * When the client IP matches one of the configured CIDRs, /auth/verify returns 200
 * without requiring a session. Intended for LAN and Tailscale/Headscale access.
 *
 * Configured via LOCAL_BYPASS_CIDRS env var (comma-separated CIDR list).
 * Default: 192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10,127.0.0.1/32
 *
 * Set LOCAL_BYPASS_CIDRS="" to disable entirely.
 */
public final class LocalNetworkBypass {

    private static final String DEFAULT_CIDRS =
            "192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10,127.0.0.1/32";

    private static final System.Logger LOG = System.getLogger("volta.localbypass");

    private final List<int[]> cidrs; // each entry: [networkAddr, mask]

    public LocalNetworkBypass(String cidrsCsv) {
        this.cidrs = parse(cidrsCsv);
    }

    public static LocalNetworkBypass fromEnv() {
        String env = System.getenv("LOCAL_BYPASS_CIDRS");
        String csv = (env != null) ? env : DEFAULT_CIDRS;
        return new LocalNetworkBypass(csv);
    }

    /** Returns true if the request's client IP falls within any configured CIDR. */
    public boolean isLocalRequest(Context ctx) {
        if (cidrs.isEmpty()) return false;
        String ip = HttpSupport.clientIp(ctx);
        return matches(ip);
    }

    boolean matches(String ip) {
        int addr;
        try {
            addr = ipToInt(ip);
        } catch (Exception e) {
            return false; // IPv6 or malformed — not matched
        }
        for (int[] entry : cidrs) {
            if ((addr & entry[1]) == entry[0]) return true;
        }
        return false;
    }

    // ── private helpers ────────────────────────────────────────────────

    private static List<int[]> parse(String csv) {
        List<int[]> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) return result;
        for (String part : csv.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                String[] slash = part.split("/");
                int prefixLen = slash.length > 1 ? Integer.parseInt(slash[1].trim()) : 32;
                int mask = prefixLen == 0 ? 0 : (0xFFFFFFFF << (32 - prefixLen));
                int network = ipToInt(slash[0].trim()) & mask;
                result.add(new int[]{network, mask});
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Invalid CIDR in LOCAL_BYPASS_CIDRS, skipping: " + part);
            }
        }
        return result;
    }

    /** Parse dotted-decimal IPv4 to a signed 32-bit int. Throws for non-IPv4. */
    private static int ipToInt(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Not IPv4: " + ip);
        int result = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) throw new IllegalArgumentException("Bad octet: " + part);
            result = (result << 8) | octet;
        }
        return result;
    }
}
