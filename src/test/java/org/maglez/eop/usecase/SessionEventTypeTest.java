package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire names carried by session events.
 *
 * <p>These strings are part of the published contract: a browser subscribes to
 * {@code player-joined} by that exact name, so renaming the constant must not
 * silently rename the event. Pinning them here makes that rename a build failure
 * rather than a front end that stops reacting.
 */
@DisplayName("SessionEventType")
class SessionEventTypeTest {

    @Test
    @DisplayName("names the arrival of a player in the hyphenated form the browser listens for")
    void shouldNameThePlayerJoinedEvent() {
        assertThat(SessionEventType.PLAYER_JOINED.wireName()).isEqualTo("player-joined");
    }

    @Test
    @DisplayName("names the start of play in the hyphenated form the browser listens for")
    void shouldNameTheGameStartedEvent() {
        assertThat(SessionEventType.GAME_STARTED.wireName()).isEqualTo("game-started");
    }

    @Test
    @DisplayName("carries only the two events the lifecycle publishes")
    void shouldCarryOnlyTheLifecycleEvents() {
        assertThat(SessionEventType.values())
                .containsExactly(SessionEventType.PLAYER_JOINED, SessionEventType.GAME_STARTED);
    }
}
