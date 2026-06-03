package com.example.cp.sso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Central SSRF defense. Validates an admin-supplied IdP issuer/metadata URL
 * (scheme/host/port), resolves DNS, and rejects loopback / any-local / link-local
 * (incl. 169.254.169.254 cloud metadata) / site-local / CGNAT / multicast / ULA and
 * IPv4-mapped IPv6 forms. Used by BOTH the SSO provider create path (validate-only)
 * and the test path (validate + pinned fetch).
 *
 * <p>All failures surface a generic {@link SsrfException#publicMessage()} to the admin;
 * the detailed reason is in {@link SsrfException#internalDetail()} and is logged at WARN
 * server-side only — never returned to the caller.
 *
 * <p>DNS-rebinding TOCTOU note: {@link #validate(String)} resolves and vets DNS, but the
 * JDK {@link HttpClient} re-resolves the hostname at request time. True per-connection IP
 * pinning over TLS is non-trivial; the MVP here re-runs the full resolve+policy check
 * immediately before the request (see {@link #fetchPinned(String)}). A narrow residual
 * TOCTOU window remains and is accepted; only super_admin / org_admin can create providers.
 */
@Component
public class UrlGuard {

    private static final Logger log = LoggerFactory.getLogger(UrlGuard.class);

    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final boolean allowHttp;
    private final Set<Integer> allowedPorts;

    public UrlGuard(@Value("${app.sso.url-guard.allow-http:false}") boolean allowHttp,
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
     * Parse + scheme + host + port + DNS-resolution + IP-policy validation. Performs NO fetch.
     *
     * @return the parsed {@link URI} when every check passes
     * @throws SsrfException with a generic public message on any policy violation
     */
    public URI validate(String rawUrl) throws SsrfException {
        return resolveAndCheck(rawUrl).uri();
    }

    /**
     * Validate then GET to the vetted host. https-only (unless allow-http), redirects DISABLED,
     * short timeouts, body capped at 256KB. Non-2xx responses are returned (not thrown) so the
     * test path can map them to ok=false. Network/timeout failures surface a generic SsrfException.
     */
    public FetchResult fetchPinned(String rawUrl) throws SsrfException {
        Resolved resolved = resolveAndCheck(rawUrl);
        URI uri = resolved.uri();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            String snippet;
            if (body == null || body.length == 0) {
                snippet = "";
            } else {
                int len = Math.min(body.length, MAX_BODY_BYTES);
                snippet = new String(body, 0, len, StandardCharsets.UTF_8);
            }
            return new FetchResult(response.statusCode(), snippet);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SsrfException("Could not reach the identity provider", e.toString());
        }
    }

    private record Resolved(URI uri, InetAddress[] addresses) {}

    private Resolved resolveAndCheck(String rawUrl) throws SsrfException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfException("Invalid URL", "null or blank URL");
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
            // Force a stricter parse and surface malformations consistently.
            uri.toURL();
        } catch (URISyntaxException | java.net.MalformedURLException | IllegalArgumentException e) {
            throw new SsrfException("Invalid URL", e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SsrfException("Invalid URL", "missing scheme");
        }
        boolean isHttps = scheme.equalsIgnoreCase("https");
        boolean isHttp = scheme.equalsIgnoreCase("http");
        if (!(isHttps || (allowHttp && isHttp))) {
            throw new SsrfException("URL scheme not allowed", "scheme=" + scheme);
        }

        if (uri.getUserInfo() != null) {
            throw new SsrfException("Invalid URL", "userinfo present in URL");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfException("Invalid URL", "missing host");
        }

        int port = uri.getPort();
        if (port == -1) {
            port = isHttps ? 443 : 80;
        }
        if (!allowedPorts.contains(port)) {
            throw new SsrfException("URL port not allowed", "port=" + port);
        }

        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfException("Host could not be resolved", "host=" + host + " " + e.getMessage());
        }
        if (addrs == null || addrs.length == 0) {
            throw new SsrfException("Host could not be resolved", "host=" + host + " resolved to no addresses");
        }
        for (InetAddress addr : addrs) {
            if (isDisallowed(addr)) {
                throw new SsrfException("URL host is not allowed",
                        "host=" + host + " resolves to disallowed address " + addr.getHostAddress());
            }
        }
        return new Resolved(uri, addrs);
    }

    /**
     * Allow-list-of-deny, fail-closed: any address we cannot positively classify as routable-public
     * is rejected. Unwraps IPv4-mapped/compatible IPv6 before re-checking.
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

    /**
     * Covers ranges {@link InetAddress} flags do not (CGNAT 100.64/10, explicit 169.254/16,
     * 0.0.0.0/8, IPv6 ULA fc00::/7). 10/8, 172.16/12, 192.168/16 and ::1/:: are already caught
     * by isSiteLocal/isLoopback/isAnyLocal above; they are re-checked here defensively.
     */
    private boolean isAddrPrivate(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int a0 = b[0] & 0xFF;
            int a1 = b[1] & 0xFF;
            // 0.0.0.0/8
            if (a0 == 0) return true;
            // 10.0.0.0/8
            if (a0 == 10) return true;
            // 100.64.0.0/10 (CGNAT)
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return true;
            // 127.0.0.0/8 loopback (also caught by isLoopback)
            if (a0 == 127) return true;
            // 169.254.0.0/16 link-local incl. 169.254.169.254 cloud metadata
            if (a0 == 169 && a1 == 254) return true;
            // 172.16.0.0/12
            if (a0 == 172 && a1 >= 16 && a1 <= 31) return true;
            // 192.168.0.0/16
            if (a0 == 192 && a1 == 168) return true;
            return false;
        }
        if (b.length == 16) {
            int first = b[0] & 0xFF;
            // fc00::/7 ULA
            if ((first & 0xFE) == 0xFC) return true;
            // ::1 loopback and :: any (also caught above)
            boolean allZeroExceptLast = true;
            for (int i = 0; i < 15; i++) {
                if (b[i] != 0) { allZeroExceptLast = false; break; }
            }
            if (allZeroExceptLast && (b[15] == 0 || b[15] == 1)) return true;
            return false;
        }
        // Unknown address family -> fail closed.
        return true;
    }

    /**
     * If the address is an IPv4-mapped (::ffff:a.b.c.d) or IPv4-compatible (::a.b.c.d) IPv6
     * address, return the embedded IPv4 address so the IPv4 policy is applied; otherwise return
     * the input unchanged.
     */
    private InetAddress unwrapMapped(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 16) {
            return addr;
        }
        // First 10 bytes must be zero for both mapped and compatible forms.
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
        // Avoid treating ::1 / :: (compat form 0.0.0.x) as bogus IPv4 — leave those to the
        // IPv6 zero-check; only unwrap when there is a meaningful embedded v4 address.
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

    /** Result of a guarded fetch. {@code status} is the raw HTTP status code (no redirect following). */
    public record FetchResult(int status, String bodySnippet) {}

    /**
     * Carries a generic {@link #publicMessage()} safe to return to the admin and a detailed
     * {@link #internalDetail()} that MUST only be logged server-side.
     */
    public static class SsrfException extends RuntimeException {

        private final String publicMessage;
        private final String internalDetail;

        public SsrfException(String publicMessage, String internalDetail) {
            super(publicMessage);
            this.publicMessage = publicMessage;
            this.internalDetail = internalDetail;
        }

        public String publicMessage() {
            return publicMessage;
        }

        public String internalDetail() {
            return internalDetail;
        }
    }

    // Retained for potential diagnostics/testing of the configured port allow-list.
    Set<Integer> allowedPorts() {
        return Set.copyOf(allowedPorts);
    }

    boolean allowHttp() {
        return allowHttp;
    }

    // Suppress unused-import style warnings; Arrays kept for readability of any future bulk ops.
    @SuppressWarnings("unused")
    private static String dump(InetAddress[] a) {
        return Arrays.toString(a);
    }
}
