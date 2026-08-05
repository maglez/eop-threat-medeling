package org.maglez.eop.adapter.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Works out which address a request came from, for rate limiting purposes.
 *
 * <p>This exists because the application is not reached directly. Caddy is the
 * only published entry point (ADR-017), so {@code getRemoteAddr()} reports the
 * proxy on every request, and a limiter keyed on it would count the whole internet
 * as one client.
 *
 * <p>The subtlety is which part of {@code X-Forwarded-For} to trust. A client can
 * send that header itself, and Caddy <em>appends</em> the address it observed to
 * whatever arrived. The first entry is therefore attacker-controlled and the last
 * entry is the one the proxy wrote, so the last entry is the only one worth
 * reading. Taking the first — which is the conventional advice, and correct when
 * the header is written rather than appended — would hand an attacker a fresh
 * limiter bucket per guess by varying a header.
 *
 * <p>If no forwarding header is present the connection did not come through the
 * proxy, and the peer address is then the truth.
 */
final class ClientAddresses {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientAddresses() {
    }

    /**
     * Resolves the address to key a rate limiter on.
     *
     * @param request the incoming request
     * @return the caller's address; never blank
     */
    static String of(final HttpServletRequest request) {
        final var forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            final var hops = forwarded.split(",");
            final var nearest = hops[hops.length - 1].strip();
            if (!nearest.isEmpty()) {
                return nearest;
            }
        }
        final var peer = request.getRemoteAddr();
        return peer == null || peer.isBlank() ? "unknown" : peer;
    }
}
