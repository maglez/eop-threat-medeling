package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.entity.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.NotFacilitatorException;
import org.maglez.eop.entity.PlayerBuilder;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.SessionNotJoinableException;
import org.maglez.eop.entity.SessionStatus;
import org.maglez.eop.entity.TooFewPlayersException;

/**
 * Behaviour of the use case that takes a table out of the lobby.
 *
 * <p>The ordering assertion is the one that earns its keep. The start is written to
 * the database before it is announced, so a subscriber that reacts to the
 * announcement by re-reading the session cannot observe a table that is still in the
 * lobby. Announcing first would make that race real and intermittent.
 */
@DisplayName("StartSessionUseCase")
class StartSessionUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-02-01T09:45:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String FACILITATOR_TOKEN = PlayerBuilder.DEFAULT_TOKEN;

    private final List<String> order = new ArrayList<>();
    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher(order);

    private StartSessionUseCase useCaseFor(final InMemorySessionRepository repository) {
        return new StartSessionUseCase(repository, new ResolvePlayerUseCase(repository), publisher, FIXED);
    }

    @Test
    @DisplayName("puts a full enough table into play")
    void shouldStartPlayForAFacilitator() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(order, session);

        final var started = useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(started.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(started.updatedAt()).isEqualTo(NOW);
        assertThat(started.createdAt()).isEqualTo(session.createdAt());
        assertThat(started.players()).hasSize(3);
    }

    @Test
    @DisplayName("records the start in the database before announcing it")
    void shouldRecordBeforeAnnouncing() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(order).containsExactly("recordStarted", "publish");
    }

    @Test
    @DisplayName("announces the start once, naming the session and the moment")
    void shouldAnnounceTheStart() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(publisher.published()).hasSize(1);
        final var event = publisher.published().get(0);
        assertThat(event.type()).isEqualTo(SessionEventType.GAME_STARTED);
        assertThat(event.sessionId()).isEqualTo(session.sessionId());
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("leaves the stored session in play, not only the returned copy")
    void shouldPersistTheNewStatus() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(repository.findById(session.sessionId()).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("refuses a participant, and writes nothing while refusing")
    void shouldRefuseAParticipant() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(NotFacilitatorException.class)
                .isThrownBy(() -> useCaseFor(repository)
                        .execute(session.sessionId(), FACILITATOR_TOKEN + "-1"))
                .withMessageContaining("is not the facilitator");

        assertThat(repository.recordStartedCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("refuses a table of two, because the game needs three")
    void shouldRefuseATableOfTwo() {
        final var session = aSession().withPlayerCount(2).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(TooFewPlayersException.class)
                .isThrownBy(() -> useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN))
                .withMessageContaining("needs at least 3 to start");

        assertThat(repository.recordStartedCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("refuses a table that is already in play")
    void shouldRefuseASecondStart() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(SessionNotJoinableException.class)
                .isThrownBy(() -> useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN))
                .withMessageContaining("IN_PROGRESS");

        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("refuses an unrecognised credential before deciding anything else")
    void shouldRefuseAnUnknownCredential() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCaseFor(repository).execute(session.sessionId(), "not-a-credential"));

        assertThat(repository.recordStartedCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }
}
