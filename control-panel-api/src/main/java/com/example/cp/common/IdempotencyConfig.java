package com.example.cp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the cross-cutting {@code Idempotency-Key} support (#81):
 *
 * <ul>
 *   <li>Registers {@link IdempotencyInterceptor} for mutating requests under {@code /api/**}.</li>
 *   <li>Registers a body-caching servlet {@link OncePerRequestFilter} so the interceptor can hash the
 *       request body in {@code preHandle} (via a pre-buffering {@link CachedBodyHttpServletRequest})
 *       and read the response body in {@code afterCompletion} (via a
 *       {@link ContentCachingResponseWrapper}). The filter only wraps requests that actually carry the
 *       header on a POST/PUT/PATCH, so non-idempotent traffic is untouched.</li>
 * </ul>
 *
 * <p>The interceptor's persistence is delegated to {@link IdempotencyInterceptor.Store}, a Spring bean
 * so its {@code @Transactional(REQUIRES_NEW)} methods are proxied. TTL is bound from
 * {@code app.idempotency.ttl} (default {@code P1D}).</p>
 */
@Configuration
@EnableConfigurationProperties(IdempotencyConfig.IdempotencyProperties.class)
public class IdempotencyConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor interceptor;

    /**
     * The {@link IdempotencyInterceptor.Store} is a standalone {@code @Component} and
     * {@link IdempotencyProperties} a {@code @ConfigurationProperties} bean, so neither is defined by
     * this configurer — injecting them here is cycle-free, and the interceptor is built eagerly in the
     * constructor so {@link #addInterceptors} always sees a fully-initialised instance.
     */
    public IdempotencyConfig(IdempotencyInterceptor.Store store, IdempotencyProperties properties) {
        this.interceptor = new IdempotencyInterceptor(store, Clock.systemUTC(), properties.getTtl());
    }

    /** Exposes the interceptor as a bean too (handy for explicit references / tests). */
    @Bean
    public IdempotencyInterceptor idempotencyInterceptor() {
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }

    /**
     * Servlet filter that pre-buffers the request body and caches the response body for idempotent
     * requests. Ordered just below the highest-precedence correlation-id filter and above Spring
     * Security so the wrapper is already in place when MVC (and the interceptor) run.
     */
    @Bean
    public FilterRegistrationBean<IdempotencyBodyCachingFilter> idempotencyBodyCachingFilter() {
        FilterRegistrationBean<IdempotencyBodyCachingFilter> reg =
                new FilterRegistrationBean<>(new IdempotencyBodyCachingFilter());
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    /**
     * Caches the request and response bodies only for a POST/PUT/PATCH that carries a non-blank
     * {@code Idempotency-Key} header. Everything else passes through unwrapped (zero overhead).
     */
    public static class IdempotencyBodyCachingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (!shouldCache(request)) {
                chain.doFilter(request, response);
                return;
            }
            // Eagerly buffer the request body so the interceptor can hash it in preHandle while the
            // controller can still read it afterwards (the buffer is replayable on every read).
            CachedBodyHttpServletRequest wrappedReq = new CachedBodyHttpServletRequest(request);
            ContentCachingResponseWrapper wrappedResp = new ContentCachingResponseWrapper(response);
            try {
                chain.doFilter(wrappedReq, wrappedResp);
            } finally {
                // Must copy the buffered body back to the real response or the client gets an empty body.
                wrappedResp.copyBodyToResponse();
            }
        }

        private static boolean shouldCache(HttpServletRequest request) {
            String method = request.getMethod();
            boolean mutating = HttpMethod.POST.matches(method)
                    || HttpMethod.PUT.matches(method)
                    || HttpMethod.PATCH.matches(method);
            if (!mutating) {
                return false;
            }
            String key = request.getHeader(IdempotencyInterceptor.HEADER);
            return key != null && !key.isBlank();
        }
    }

    /**
     * Buffers the entire request body up front (in the constructor) and serves it back on every
     * {@link #getInputStream()} / {@link #getReader()} call. Unlike
     * {@link org.springframework.web.util.ContentCachingRequestWrapper} (which only caches bytes as
     * they are consumed and does NOT replay them downstream), this guarantees both the interceptor's
     * {@code preHandle} hash and the controller's argument binding see the full body. The cached bytes
     * are exposed via {@link #getCachedBody()} for the interceptor.
     */
    public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        public byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream backing = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return backing.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("async reads are not supported on the cached body");
                }

                @Override
                public int read() {
                    return backing.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() != null
                    ? Charset.forName(getCharacterEncoding())
                    : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(cachedBody), charset));
        }
    }

    /** Configuration bound to {@code app.idempotency}. */
    @ConfigurationProperties(prefix = "app.idempotency")
    public static class IdempotencyProperties {

        /** How long a stored idempotency record remains replayable before the cleanup sweep may purge it. */
        private Duration ttl = Duration.ofDays(1);

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl != null ? ttl : Duration.ofDays(1);
        }
    }
}
