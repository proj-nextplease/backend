package com.nextplease.backend.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable rate-limit policies. All limits are "X requests per minute" per key
 * (per client IP for unauthenticated traffic, per user id once authenticated).
 *
 * <p>These guard against application-layer (L7) abuse: brute-force login, OTP /
 * email bombing, and endpoint flooding. They are NOT a defence against volumetric
 * (L3/L4) DDoS — that must be handled at the edge (Cloudflare / WAF / load balancer).
 */
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class RateLimitProperties {

    /** Master switch. When false, the filter passes every request through untouched. */
    private boolean enabled = true;

    /** Auth-sensitive endpoints (login, OTP request/verify, registration). Keyed by IP. */
    private int authPerMinute = 10;

    /** Write actions (POST/PUT/PATCH/DELETE) for authenticated users. Keyed by user id. */
    private int writePerMinute = 60;

    /** Global backstop applied to every request, keyed by IP. Catches blind flooding. */
    private int globalPerMinute = 240;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getAuthPerMinute() {
        return authPerMinute;
    }

    public void setAuthPerMinute(int authPerMinute) {
        this.authPerMinute = authPerMinute;
    }

    public int getWritePerMinute() {
        return writePerMinute;
    }

    public void setWritePerMinute(int writePerMinute) {
        this.writePerMinute = writePerMinute;
    }

    public int getGlobalPerMinute() {
        return globalPerMinute;
    }

    public void setGlobalPerMinute(int globalPerMinute) {
        this.globalPerMinute = globalPerMinute;
    }
}
