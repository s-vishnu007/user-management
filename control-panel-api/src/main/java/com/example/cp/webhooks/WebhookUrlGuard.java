package com.example.cp.webhooks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SSRF chokepoint for the webhook delivery path. Mirrors the policy of {@code com.example.cp.sso.UrlGuard}
 * (https-only unless {@code allow-http}, allowed-port list, and a fail-closed allow-list-of-deny that
 * rejects loopback / any-local / link-local incl. {@code 169.254.169.254} / site-local / CGNAT /
 * multicast / IPv6-ULA and IPv4-mapped IPv6 forms), but is owned by the webhooks package so the
 * delivery scheduler can re-resolve and re-vet a destination immediately before EACH POST.
 *
 * <p><b>Why re-validate per delivery (DNS-rebind defense).</b> A webhook URL is validated once at
 * registration ({@code WebhookController} → {@code sso.UrlGuard.validate}). The JDK {@link
 * java.net.http.HttpClient} re-resolves the hostname at send time, so an org admin could register a
 * benign public host and later repoint its DNS at an internal address (cloud metadata, internal HTTPS
 * service). {@link #resolveAndPin(String)} re-runs the full resolve + IP-policy check right before the
 * request; if every resolved address is still routable-public the delivery proceeds, otherwise it is
 * rejected and counted as a failed attempt. (A narrow residual TOCTOU window between this check and the
 * client's own resolution is accepted, exactly as the SSO test path documents; deliveries are gated to
 * org admins and are blind — redirects disabled, body discarded.)
 */
@Component
public class WebhookUrlGuard {

    private final boolean allowHttp;
    private final Set<Integer> allowedPorts;

    public WebhookUrlGuard(@Value("${app.sso.url-guard.allow-http:false}") boolean allowHttp,
                           @Value("${app.sso.url-guard.allowed-ports:}") String allowedPortsCsv) {
        this.allowHttp = allowHttp;
        Set<Integer> ports = new LinkedHashSet<>();
        ports.add(443);
        if (allowHttp) {
            ports.add(80);
        }
        if (allowedPortsCsv != null && !allowedPortsCsv.isBlank()) {
            for (String tok : allowedPortsCsv.split(",")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                try {
                    ports.add(Integer.parseInt(t));
                } catch (NumberFormatException ignore) {
                    // Skip non-numeric tokens; fail-closed by simply not adding them.
                }
            }
        }
        this.allowedPorts = ports;
    }

    /**
     * Re-parse + scheme/host/port + DNS-resolution + IP-policy validation. Returns the target to send
     * to (the original URI, plus the explicit Host header so TLS/SNI/cert validation use the hostname).
     *
     * @throws SsrfException on any policy violation (never reach an internal address).
     */
    public PinnedTarget resolveAndPin(String rawUrl) throws SsrfException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfException("blank URL");
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
            uri.toURL();
        } catch (URISyntaxException | java.net.MalformedURLException | IllegalArgumentException e) {
            throw new SsrfException("invalid URL: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SsrfException("missing scheme");
        }
        boolean isHttps = scheme.equalsIgnoreCase("https");
        boolean isHttp = scheme.equalsIgnoreCase("http");
        if (!(isHttps || (allowHttp && isHttp))) {
            throw new SsrfException("scheme not allowed: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new SsrfException("userinfo present in URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfException("missing host");
        }
        int port = uri.getPort();
        int effectivePort = port == -1 ? (isHttps ? 443 : 80) : port;
        if (!allowedPorts.contains(effectivePort)) {
            throw new SsrfException("port not allowed: " + effectivePort);
        }

        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfException("host could not be resolved: " + host);
        }
        if (addrs == null || addrs.length == 0) {
            throw new SsrfException("host resolved to no addresses: " + host);
        }
        for (InetAddress addr : addrs) {
            if (isDisallowed(addr)) {
                throw new SsrfException("host resolves to disallowed address " + addr.getHostAddress());
            }
        }
        String hostHeader = port == -1 ? host : host + ":" + port;
        return new PinnedTarget(uri, hostHeader);
    }

    /**
     * Allow-list-of-deny, fail-closed: any address we cannot positively classify as routable-public is
     * rejected. Unwraps IPv4-mapped/compatible IPv6 before re-checking.
     */
    private boolean isDisallowed(InetAddress raw) {
        if (raw == null) {
            return true;
        }
        InetAddress addr = unwrapMapped(raw);
        if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        return isAddrPrivate(addr);
    }

    private boolean isAddrPrivate(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int a0 = b[0] & 0xFF;
            int a1 = b[1] & 0xFF;
            if (a0 == 0) return true;                              // 0.0.0.0/8
            if (a0 == 10) return true;                             // 10.0.0.0/8
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return true;   // 100.64.0.0/10 CGNAT
            if (a0 == 127) return true;                            // 127.0.0.0/8 loopback
            if (a0 == 169 && a1 == 254) return true;               // 169.254.0.0/16 incl. cloud metadata
            if (a0 == 172 && a1 >= 16 && a1 <= 31) return true;    // 172.16.0.0/12
            if (a0 == 192 && a1 == 168) return true;               // 192.168.0.0/16
            return false;
        }
        if (b.length == 16) {
            int first = b[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) return true;               // fc00::/7 ULA
            boolean allZeroExceptLast = true;
            for (int i = 0; i < 15; i++) {
                if (b[i] != 0) { allZeroExceptLast = false; break; }
            }
            if (allZeroExceptLast && (b[15] == 0 || b[15] == 1)) return true; // ::/::1
            return false;
        }
        return true; // unknown family -> fail closed
    }

    private InetAddress unwrapMapped(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 16) {
            return addr;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return addr;
            }
        }
        boolean mapped = (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF);
        boolean compat = (b[10] == 0 && b[11] == 0);
        if (!mapped && !compat) {
            return addr;
        }
        byte[] v4 = new byte[]{b[12], b[13], b[14], b[15]};
        if (compat) {
            int sum = (v4[0] & 0xFF) | (v4[1] & 0xFF) | (v4[2] & 0xFF) | (v4[3] & 0xFF);
            if (sum == 0 || (v4[0] == 0 && v4[1] == 0 && v4[2] == 0 && (v4[3] & 0xFF) <= 1)) {
                return addr;
            }
        }
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException e) {
            return addr;
        }
    }

    /** The vetted target for a delivery: the request URI to send to and the Host header to set. */
    public record PinnedTarget(URI requestUri, String hostHeader) {}

    /** Thrown when a webhook destination fails the SSRF re-check; carries an internal-only detail. */
    public static class SsrfException extends RuntimeException {
        private final String internalDetail;

        public SsrfException(String internalDetail) {
            super("webhook destination not allowed");
            this.internalDetail = internalDetail;
        }

        public String internalDetail() {
            return internalDetail;
        }
    }

    // Diagnostics seam for tests.
    Set<Integer> allowedPorts() {
        return Set.copyOf(allowedPorts);
    }
}
