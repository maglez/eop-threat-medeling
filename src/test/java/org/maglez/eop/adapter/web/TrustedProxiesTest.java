package org.maglez.eop.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link TrustedProxies} is genuinely default-denied and that its
 * CIDR arithmetic is correct at every boundary.
 *
 * <p>The allow-list is a primary security control. An off-by-one in the prefix
 * mask would silently extend trust to addresses that were never configured, and
 * the only observable symptom would be that an attacker could rotate
 * {@code X-Forwarded-For} to escape the join throttle — the exact defect EOP-26
 * was filed to close. Deleting these tests removes the only machine-checked proof
 * that the boundary arithmetic is correct and that the default is truly denied.
 *
 * <p>The immutability test matters for the same reason: a caller that kept a
 * reference to the list it passed in and later mutated it could widen the allow-list
 * after startup validation had already accepted it.
 */
@DisplayName("TrustedProxies")
class TrustedProxiesTest {

    private static final String PROXY_ADDR = "192.168.1.1";

    private static final String PROXY_CIDR = "192.168.1.0/24";

    private static final String CLIENT_ADDR = "203.0.113.5";

    private static final String INSIDE_CIDR = "192.168.1.200";

    private static final String OUTSIDE_CIDR = "192.168.2.1";

    private static final String ERR_PREFIX = "eop.web.trusted-proxies";

    @Nested
    @DisplayName("default-denied — nothing is trusted without explicit configuration")
    class DefaultDenied {

        @Test
        @DisplayName("none() trusts nobody")
        void shouldTrustNobodyWithNone() {
            final var proxies = TrustedProxies.none();

            assertThat(proxies.isEmpty()).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }

        @Test
        @DisplayName("of(null) trusts nobody")
        void shouldTrustNobodyWithNull() {
            final var proxies = TrustedProxies.of(null);

            assertThat(proxies.isEmpty()).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }

        @Test
        @DisplayName("of(empty list) trusts nobody")
        void shouldTrustNobodyWithEmptyList() {
            final var proxies = TrustedProxies.of(List.of());

            assertThat(proxies.isEmpty()).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }

        @Test
        @DisplayName("null and blank entries are skipped rather than rejected")
        void shouldSkipNullAndBlankEntries() {
            final var entries = new ArrayList<String>();
            entries.add(null);
            entries.add("   ");
            entries.add("");

            assertThatCode(() -> TrustedProxies.of(entries)).doesNotThrowAnyException();
            assertThat(TrustedProxies.of(entries).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("literal address entries")
    class LiteralEntries {

        @Test
        @DisplayName("a literal entry matches only that exact host")
        void shouldMatchOnlyTheConfiguredHost() {
            final var proxies = TrustedProxies.of(List.of(PROXY_ADDR));

            assertThat(proxies.includes(PROXY_ADDR)).isTrue();
        }

        @Test
        @DisplayName("a literal entry does not match a neighbouring address")
        void shouldNotMatchNeighbour() {
            final var proxies = TrustedProxies.of(List.of(PROXY_ADDR));

            assertThat(proxies.includes("192.168.1.2")).isFalse();
        }

        @Test
        @DisplayName("a literal entry does not match a client address")
        void shouldNotMatchClientAddress() {
            final var proxies = TrustedProxies.of(List.of(PROXY_ADDR));

            assertThat(proxies.includes(CLIENT_ADDR)).isFalse();
        }
    }

    @Nested
    @DisplayName("CIDR block entries")
    class CidrEntries {

        @Test
        @DisplayName("an address inside the CIDR block is included")
        void shouldIncludeAddressInsideBlock() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes(INSIDE_CIDR)).isTrue();
        }

        @Test
        @DisplayName("an address outside the CIDR block is excluded")
        void shouldExcludeAddressOutsideBlock() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes(OUTSIDE_CIDR)).isFalse();
        }

        @Test
        @DisplayName("the network address itself is included")
        void shouldIncludeNetworkAddress() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes("192.168.1.0")).isTrue();
        }

        @Test
        @DisplayName("the broadcast address is included")
        void shouldIncludeBroadcastAddress() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes("192.168.1.255")).isTrue();
        }

        @Test
        @DisplayName("/0 matches every IPv4 address")
        void shouldMatchEverythingWithSlashZero() {
            final var proxies = TrustedProxies.of(List.of("0.0.0.0/0"));

            assertThat(proxies.includes(CLIENT_ADDR)).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isTrue();
        }

        @Test
        @DisplayName("/32 matches only the single host")
        void shouldMatchOnlySingleHostWithSlash32() {
            final var proxies = TrustedProxies.of(List.of("203.0.113.5/32"));

            assertThat(proxies.includes(CLIENT_ADDR)).isTrue();
            assertThat(proxies.includes("203.0.113.6")).isFalse();
        }

        @Test
        @DisplayName("an IPv4 CIDR does not match an IPv6 address")
        void shouldNotMatchIpv6WithIpv4Cidr() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes("::1")).isFalse();
        }

        @Test
        @DisplayName("an IPv6 CIDR does not match an IPv4 address")
        void shouldNotMatchIpv4WithIpv6Cidr() {
            final var proxies = TrustedProxies.of(List.of("::1/128"));

            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }
    }

    @Nested
    @DisplayName("rejection of invalid entries")
    class InvalidEntries {

        @Test
        @DisplayName("host bits set in CIDR is rejected with the configured property name in the message")
        void shouldRejectHostBitsSet() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.5/8")))
                    .withMessageContaining(ERR_PREFIX)
                    .withMessageContaining("10.0.0.5/8");
        }

        @Test
        @DisplayName("prefix length above 32 for IPv4 is rejected")
        void shouldRejectPrefixAbove32() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/33")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("prefix length above 128 for IPv6 is rejected")
        void shouldRejectIpv6PrefixAbove128() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("::1/129")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("non-numeric prefix length is rejected")
        void shouldRejectNonNumericPrefix() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/abc")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("negative prefix length is rejected")
        void shouldRejectNegativePrefix() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/-1")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("a non-IP string is rejected")
        void shouldRejectNonIpString() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("not-an-ip")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("a hostname with CIDR notation is rejected")
        void shouldRejectHostnameWithCidr() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("example.com/24")))
                    .withMessageContaining(ERR_PREFIX);
        }
    }

    @Nested
    @DisplayName("immutability — the allow-list cannot be widened after construction")
    class Immutability {

        @Test
        @DisplayName("mutating the list passed to of() does not change what the instance includes")
        void shouldNotBeAffectedByMutationOfSourceList() {
            final var mutable = new ArrayList<String>();
            mutable.add(PROXY_ADDR);
            final var proxies = TrustedProxies.of(mutable);

            mutable.add("10.0.0.1");

            assertThat(proxies.includes("10.0.0.1")).isFalse();
        }
    }
}
