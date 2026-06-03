package com.example.cp.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the real client IP from {@code X-Forwarded-For} ONLY when the immediate peer
 * ({@link HttpServletRequest#getRemoteAddr()}) is within one of the configured
 * {@code app.audit.trusted-proxies} CIDR blocks; otherwise the direct socket peer is returned.
 *
 * <p>This replaces the previously-untrusted XFF parsing in {@code AuditInterceptor} and the
 * {@code getRemoteAddr()}-only logic in {@code JwtAuthFilter}, giving a single uniform,
 * non-spoofable client-IP source.</p>
 *
 * <p><b>XFF direction policy:</b> when the peer is trusted, the LEFTMOST (client) token of the
 * XFF list is returned. This is correct for a single trusted proxy in front of the app. With
 * chained proxies the leftmost token can be spoofed by the original client, so the trusted-proxy
 * list should reflect every hop and operators must understand this is a single-proxy assumption.</p>
 */
@Component
public class TrustedProxyResolver {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyResolver.class);

    private final List<IpAddressMatcher> trustedMatchers;

    public TrustedProxyResolver(AuditProperties props) {
        this.trustedMatchers = compile(props != null ? props.getTrustedProxies() : null);
    }

    private static List<IpAddressMatcher> compile(List<String> cidrs) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        if (cidrs == null) {
            return matchers;
        }
        for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            try {
                matchers.add(new IpAddressMatcher(cidr.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring invalid trusted-proxy CIDR '{}': {}", cidr, e.getMessage());
            }
        }
        return matchers;
    }

    /**
     * @return the resolved client IP, or {@code null} only when {@code req} is {@code null}.
     */
    public String resolveClientIp(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        String peer = req.getRemoteAddr();
        if (trustedMatchers.isEmpty() || peer == null || !isTrusted(peer)) {
            return peer;
        }
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd == null || fwd.isBlank()) {
            return peer;
        }
        try {
            int comma = fwd.indexOf(',');
            String client = (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
            return client.isEmpty() ? peer : client;
        } catch (RuntimeException e) {
            // Malformed XFF: fall back to the direct peer rather than trusting garbage.
            return peer;
        }
    }

    private boolean isTrusted(String ip) {
        for (IpAddressMatcher m : trustedMatchers) {
            try {
                if (m.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Address family mismatch between matcher and ip — treat as no match.
            }
        }
        return false;
    }
}
