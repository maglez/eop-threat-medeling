package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The binding rules are worth a test because a misconfigured heartbeat is silent.
 *
 * <p>An interval of zero would schedule a task that runs continuously, and a
 * negative one is rejected outright by the executor at construction — both of
 * which surface as a broken container rather than as a configuration error. The
 * guard clause turns a typo in {@code application.yml} into a startup failure
 * naming the property.
 */
@DisplayName("RealtimeProperties")
class RealtimePropertiesTest {

    @Test
    @DisplayName("a positive interval is kept exactly as given")
    void shouldAcceptAPositiveInterval() {
        final RealtimeProperties properties = new RealtimeProperties(Duration.ofMillis(200));

        assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("zero is refused, because a heartbeat that never waits is a busy loop")
    void shouldRejectZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RealtimeProperties(Duration.ZERO))
                .withMessageContaining("eop.realtime.heartbeat-interval must be positive");
    }

    @Test
    @DisplayName("a negative interval is refused")
    void shouldRejectANegativeInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RealtimeProperties(Duration.ofSeconds(-1)))
                .withMessageContaining("must be positive");
    }

    @Test
    @DisplayName("null passes the guard, so bean validation reports the missing value rather than the constructor")
    void shouldLeaveNullToBeanValidation() {
        final RealtimeProperties properties = new RealtimeProperties(null);

        assertThat(properties.heartbeatInterval()).isNull();
    }
}
