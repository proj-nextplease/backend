package com.nextplease.backend.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client IP, honouring the {@code X-Forwarded-For} header set by
 * a reverse proxy / CDN (e.g. Cloudflare, nginx). When the app sits behind such a
 * proxy the {@code request.getRemoteAddr()} is the proxy IP, not the visitor's, so
 * we must read the forwarded chain and take the first (original client) entry.
 *
 * <p>Note: {@code X-Forwarded-For} is client-spoofable when the app is exposed
 * directly to the internet. It is only trustworthy when a proxy you control sets
 * it. Keep this in mind once Cloudflare is in front — prefer {@code CF-Connecting-IP}
 * there if desired.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String cfConnectingIp = request.getHeader("CF-Connecting-IP");
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            return cfConnectingIp.trim();
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Format: "client, proxy1, proxy2" — the first entry is the original client.
            int comma = forwardedFor.indexOf(',');
            String first = comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor;
            if (!first.isBlank()) {
                return first.trim();
            }
        }

        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }
}
